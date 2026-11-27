package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import com.ztx.modules.metadata.MetadataQueryRoutes
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
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class MetadataQueryIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    /** Given 租户有两家企业的列表数据，When 用户查询并提交越权字段，Then 只返回授权企业数据且拒绝越权筛选。 */
    @Test
    fun appliesServerScopeAndRejectsUnlistedFilterFields() {
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort, database = postgres.databaseName,
            user = postgres.username, password = postgres.password, poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        FlywayMigrator(config).migrate()
        val companyAllowed = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val companyDenied = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val userId = UUID.randomUUID()
        val definition = JsonObject()
            .put("doctype", "QueryProbe")
            .put("scope", "TENANT")
            .put("table", JsonObject().put("schema", "org").put("name", "query_probe"))
            .put("fields", JsonArray()
                .add(JsonObject().put("name", "id").put("type", "uuid"))
                .add(JsonObject().put("name", "code").put("type", "string")
                    .put("readRoles", JsonArray().add("TENANT_USER")))
                .add(JsonObject().put("name", "company_id").put("type", "uuid")))
            .put("list", JsonObject()
                .put("columns", JsonArray().add("code"))
                .put("filters", JsonArray().add("code"))
                .put("sort", JsonArray().add("code"))
                .put("search", JsonArray().add("code")))
            .put("permissions", JsonObject().put("read", JsonArray().add("TENANT_USER")))

        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE org.query_probe (id uuid PRIMARY KEY, code text NOT NULL, company_id uuid NOT NULL)")
            }
            connection.prepareStatement("INSERT INTO org.query_probe(id, code, company_id) VALUES (?, ?, ?)").use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, "AX-01")
                statement.setObject(3, companyAllowed)
                statement.executeUpdate()
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, "AX-02")
                statement.setObject(3, companyDenied)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO meta.document_definition
                   (id, scope, doctype, version, status, definition_json, checksum, created_by, updated_by)
                   VALUES (?, 'TENANT', 'QueryProbe', 1, 'VALIDATED', ?::jsonb, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, definition.encode())
                statement.setString(3, "0".repeat(64))
                statement.setObject(4, userId)
                statement.setObject(5, userId)
                statement.executeUpdate()
            }
        }

        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val principal = TenantPrincipal(
            tenantId = UUID.randomUUID(),
            tenantCode = "query-test",
            databaseName = config.database,
            userId = userId,
            username = "business.user",
            role = "TENANT_USER",
            database = database,
            companyIds = setOf(companyAllowed)
        )
        val router = Router.router(vertx)
        router.route("/api/v1/*").handler { context ->
            context.put(TENANT_CONTEXT_KEY, principal)
            context.next()
        }
        MetadataQueryRoutes().register(router)
        val server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS)
        val client = HttpClient.newHttpClient()
        val base = "http://127.0.0.1:${server.actualPort()}/api/v1/tenant/query/QueryProbe"
        try {
            val valid = client.send(
                HttpRequest.newBuilder(URI.create(base)).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        JsonObject().put("page", 1).put("pageSize", 20)
                            .put("filter", JsonObject().put("field", "code").put("operator", "contains").put("value", "AX"))
                            .encode()
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, valid.statusCode())
            val result = JsonObject(valid.body())
            assertEquals(1L, result.getLong("total"))
            assertEquals(1, result.getJsonArray("items").size())
            assertEquals("AX-01", result.getJsonArray("items").getJsonObject(0).getString("code"))

            val forbidden = client.send(
                HttpRequest.newBuilder(URI.create(base)).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        JsonObject().put("filter", JsonObject().put("field", "company_id")
                            .put("operator", "eq").put("value", companyDenied.toString())).encode()
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, forbidden.statusCode())
            assertTrue(JsonObject(forbidden.body()).getJsonObject("error").getString("message").contains("字段不可筛选"))

            // Given 读权限未显式授予业务用户，When 请求列表，Then 默认仅允许租户管理员读取。
            connectionUpdateDefinition(config, definition.put("permissions", JsonObject()))
            val noPermission = client.send(
                HttpRequest.newBuilder(URI.create(base)).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, noPermission.statusCode())
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
    }

    private fun connectionUpdateDefinition(config: DatabaseConfig, definition: JsonObject) {
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.prepareStatement(
                "UPDATE meta.document_definition SET definition_json=?::jsonb WHERE doctype='QueryProbe'"
            ).use { statement ->
                statement.setString(1, definition.encode())
                statement.executeUpdate()
            }
        }
    }
}
