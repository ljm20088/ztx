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
                .put("fields", JsonArray().add(JsonObject().put("name", "code").put("type", "string").put("required", true)))
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
            val history = client.send(
                HttpRequest.newBuilder(URI.create("$base/api/v1/tenant/metadata/doctypes/MetadataTestItem/history")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, history.statusCode())
            assertEquals(2, JsonObject(history.body()).getJsonArray("items").size())
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
