package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import com.ztx.modules.metadata.MetadataRoutes
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class MetadataManagementIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    // Given 租户管理员拥有元数据管理入口，When 创建、校验并读取定义历史，Then 草稿和变更记录均持久化。
    @Test
    fun persistsAndValidatesTenantMetadataDrafts() {
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort, database = postgres.databaseName,
            user = postgres.username, password = postgres.password, poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        FlywayMigrator(config).migrate()
        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val principal = TenantPrincipal(
            UUID.randomUUID(), "metadata-test", config.database, UUID.randomUUID(),
            "tenant.admin", "TENANT_ADMIN", database
        )
        val router = Router.router(vertx)
        router.route("/api/v1/*").handler { context ->
            context.put(TENANT_CONTEXT_KEY, principal)
            context.next()
        }
        MetadataRoutes().register(router)
        val server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS)
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.actualPort()}"
            val definition = JsonObject()
                .put("doctype", "MetadataTestItem")
                .put("scope", "TENANT")
                .put("table", JsonObject().put("schema", "org").put("name", "metadata_test_item"))
                .put("fields", JsonArray().add(JsonObject().put("name", "code").put("type", "string").put("required", true).put("defaultValue", "NEW")) )
                .put("form", JsonObject().put("fields", JsonArray().add("code")))
                .put("list", JsonObject().put("columns", JsonArray().add("code")))
            val create = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(definition.encode())).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(201, create.statusCode())
            val validate = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/MetadataTestItem/validate"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, validate.statusCode())
            assertTrue(JsonObject(validate.body()).getBoolean("valid"))
            // Given 字段默认值随元数据草稿保存，When 读取已发布定义，Then 默认值应原样持久化并返回。
            val readDefinition = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/MetadataTestItem")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, readDefinition.statusCode())
            assertEquals("NEW", JsonObject(readDefinition.body()).getJsonObject("definition")
                .getJsonArray("fields").getJsonObject(0).getString("defaultValue"))
            assertEquals("code", JsonObject(readDefinition.body()).getJsonObject("definition")
                .getJsonObject("form").getJsonArray("fields").getString(0))
            assertEquals("code", JsonObject(readDefinition.body()).getJsonObject("definition")
                .getJsonObject("list").getJsonArray("columns").getString(0))
            // Given 元数据校验成功，When 设计器激活该定义，Then 真实物理表和统一运行期迁移历史必须先成功创建。
            val publishedSchema = database.withTransaction { connection ->
                connection.preparedQuery(
                    """SELECT
                       EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='org' AND table_name='metadata_test_item') AS table_exists,
                       EXISTS (SELECT 1 FROM meta.schema_migration WHERE source='RUNTIME' AND doctype='MetadataTestItem' AND version='1' AND status='SUCCEEDED') AS migration_succeeded"""
                ).execute().map { rows -> rows.first().getBoolean("table_exists") to rows.first().getBoolean("migration_succeeded") }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertTrue(publishedSchema.first)
            assertTrue(publishedSchema.second)
            // Given 当前 DocType 版本已发布，When 尝试原地覆盖定义，Then 必须拒绝并保持已激活版本不变。
            val editPublished = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/MetadataTestItem"))
                    .header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(definition.copy().put("label", "changed").encode())).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(409, editPublished.statusCode())
            val history = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/MetadataTestItem/history")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, history.statusCode())
            val historyItems = JsonObject(history.body()).getJsonArray("items")
            assertEquals(2, historyItems.size())
            // Given 成功发布已完成，Then 历史动作必须明确记录为 PUBLISH，而不是只验证记录数量。
            assertTrue(historyItems.toList().any { (it as JsonObject).getString("action") == "PUBLISH" })

            // Given 同一租户 DocType 草稿，When 两名管理员并发发布，Then 两个请求均幂等成功且只产生一条 PUBLISH 历史。
            val concurrentDefinition = JsonObject()
                .put("doctype", "ConcurrentProbe").put("scope", "TENANT")
                .put("table", JsonObject().put("schema", "org").put("name", "concurrent_probe"))
                .put("fields", JsonArray().add(JsonObject().put("name", "code").put("type", "string")))
            val concurrentCreate = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(concurrentDefinition.encode())).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(201, concurrentCreate.statusCode())
            val concurrentRequest = HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/ConcurrentProbe/validate"))
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build()
            val firstPublish = client.sendAsync(concurrentRequest, HttpResponse.BodyHandlers.ofString())
            val secondPublish = client.sendAsync(concurrentRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, firstPublish.get(20, TimeUnit.SECONDS).statusCode())
            assertEquals(200, secondPublish.get(20, TimeUnit.SECONDS).statusCode())
            val concurrentPublishCount = database.withTransaction { connection ->
                connection.query("SELECT count(*) FROM meta.document_definition_history WHERE doctype='ConcurrentProbe' AND action='PUBLISH'")
                    .execute().map { it.first().getLong(0) }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals(1L, concurrentPublishCount)

            // Given Schema DDL 已提交但元数据激活事务被数据库触发器拒绝，When 管理员修复阻断后重试，Then 已成功迁移应被幂等复用并完成激活。
            val recoveryDefinition = JsonObject()
                .put("doctype", "RecoveryProbe").put("scope", "TENANT")
                .put("table", JsonObject().put("schema", "org").put("name", "recovery_probe"))
                .put("fields", JsonArray().add(JsonObject().put("name", "code").put("type", "string")))
            val recoveryCreate = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(recoveryDefinition.encode())).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(201, recoveryCreate.statusCode())
            database.withTransaction { connection ->
                connection.query("""CREATE OR REPLACE FUNCTION meta.reject_runtime_publish() RETURNS trigger
                    LANGUAGE plpgsql AS 'BEGIN IF NEW.doctype = ''RecoveryProbe'' AND NEW.status = ''VALIDATED'' THEN
                    RAISE EXCEPTION ''forced activation failure''; END IF; RETURN NEW; END'""").execute()
                    .compose { connection.query("CREATE TRIGGER reject_runtime_publish BEFORE UPDATE ON meta.document_definition FOR EACH ROW EXECUTE FUNCTION meta.reject_runtime_publish()").execute() }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            val failedActivation = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/RecoveryProbe/validate"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, failedActivation.statusCode())
            val recoveryState = database.withTransaction { connection ->
                connection.preparedQuery("""SELECT d.status, m.status AS migration_status,
                    EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='org' AND table_name='recovery_probe') AS table_exists
                    FROM meta.document_definition d JOIN meta.schema_migration m ON m.doctype=d.doctype AND m.version=d.version::text
                    WHERE d.doctype='RecoveryProbe' AND d.scope='TENANT' AND m.source='RUNTIME'""")
                    .execute().map { rows -> Triple(rows.first().getString("status"), rows.first().getString("migration_status"), rows.first().getBoolean("table_exists")) }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("DRAFT", recoveryState.first)
            assertEquals("SUCCEEDED", recoveryState.second)
            assertTrue(recoveryState.third)
            database.withTransaction { connection ->
                connection.query("DROP TRIGGER reject_runtime_publish ON meta.document_definition").execute()
                    .compose { connection.query("DROP FUNCTION meta.reject_runtime_publish()").execute() }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            val recoveredPublish = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/RecoveryProbe/validate"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, recoveredPublish.statusCode())
            assertTrue(JsonObject(recoveredPublish.body()).getBoolean("valid"))
            val recoveredCount = database.withTransaction { connection ->
                connection.query("SELECT count(*) FROM meta.schema_migration WHERE source='RUNTIME' AND doctype='RecoveryProbe' AND version='1' AND status='SUCCEEDED'")
                    .execute().map { it.first().getLong(0) }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals(1L, recoveredCount)
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
    }

    // Given 租户管理员尝试声明平台级元数据，When 提交创建请求，Then 服务端拒绝越权写入。
    @Test
    fun rejectsPlatformScopeThroughTenantApi() {
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort, database = postgres.databaseName,
            user = postgres.username, password = postgres.password, poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        FlywayMigrator(config).migrate()
        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val principal = TenantPrincipal(
            UUID.randomUUID(), "metadata-test", config.database, UUID.randomUUID(),
            "tenant.admin", "TENANT_ADMIN", database
        )
        val router = Router.router(vertx)
        router.route("/api/v1/*").handler { context ->
            context.put(TENANT_CONTEXT_KEY, principal)
            context.next()
        }
        MetadataRoutes().register(router)
        val server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS)
        try {
            val definition = JsonObject().put("doctype", "ForbiddenPlatformItem").put("scope", "PLATFORM")
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.actualPort()}/api/v1/tenant/metadata/doctypes"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(definition.encode())).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, response.statusCode())
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
    }
}
