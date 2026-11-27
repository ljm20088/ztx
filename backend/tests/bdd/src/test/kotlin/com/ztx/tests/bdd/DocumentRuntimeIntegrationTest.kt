package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.document.DocumentBehavior
import com.ztx.modules.document.DocumentBehaviorContext
import com.ztx.modules.document.DocumentBehaviorRegistry
import com.ztx.modules.document.DocumentExtensionMode
import com.ztx.modules.document.DocumentRuntimeRoutes
import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
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
class DocumentRuntimeIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    private class DocumentProbeBehavior : DocumentBehavior {
        override val doctype = "DocumentProbe"
        override val mode = DocumentExtensionMode.METADATA_WITH_KOTLIN_FILE

        override fun beforeAction(
            context: DocumentBehaviorContext,
            before: JsonObject,
            action: String
        ): Future<Void> {
            if (action == "submit" && before.getString("code") == "BLOCK") {
                return Future.failedFuture<Void>(IllegalArgumentException("业务规则不允许提交"))
            }
            return Future.succeededFuture()
        }
    }

    /** Given 已校验单据元数据和 Kotlin Hook，When 创建、修改、提交及触发扩展失败，Then 状态、权限和审计保持事务一致。 */
    @Test
    fun executesDocumentLifecycleAndKotlinHooksInOneTransaction() {
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort, database = postgres.databaseName,
            user = postgres.username, password = postgres.password, poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        FlywayMigrator(config).migrate()
        val adminId = UUID.randomUUID()
        val companyId = UUID.randomUUID()
        val definition = JsonObject()
            .put("doctype", "DocumentProbe")
            .put("scope", "TENANT")
            .put("table", JsonObject().put("schema", "org").put("name", "document_probe"))
            .put("fields", JsonArray()
                .add(JsonObject().put("name", "code").put("type", "string").put("required", true))
                .add(JsonObject().put("name", "company_id").put("type", "uuid").put("required", true)))
            .put("permissions", JsonObject()
                .put("read", JsonArray().add("TENANT_ADMIN"))
                .put("create", JsonArray().add("TENANT_ADMIN"))
                .put("update", JsonArray().add("TENANT_ADMIN"))
                .put("submit", JsonArray().add("TENANT_ADMIN")))
            .put("extension", JsonObject()
                .put("mode", "METADATA_WITH_KOTLIN_FILE")
                .put("implementation", DocumentProbeBehavior::class.qualifiedName))

        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.prepareStatement(
                "INSERT INTO iam.tenant_admin(id, username, password_hash, is_group_admin) VALUES (?, ?, ?, FALSE)"
            ).use { statement ->
                statement.setObject(1, adminId)
                statement.setString(2, "document.runtime.admin")
                statement.setString(3, "test-only-password-hash")
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE TABLE org.document_probe (
                       id uuid PRIMARY KEY, docstatus integer NOT NULL DEFAULT 0,
                       created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                       updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                       code text NOT NULL, company_id uuid NOT NULL)"""
                )
            }
            connection.prepareStatement(
                """INSERT INTO meta.document_definition
                   (id, scope, doctype, version, status, definition_json, checksum, created_by, updated_by)
                   VALUES (?, 'TENANT', 'DocumentProbe', 1, 'VALIDATED', ?::jsonb, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, definition.encode())
                statement.setString(3, "1".repeat(64))
                statement.setObject(4, adminId)
                statement.setObject(5, adminId)
                statement.executeUpdate()
            }
        }

        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val principal = TenantPrincipal(
            tenantId = UUID.randomUUID(),
            tenantCode = "document-test",
            databaseName = config.database,
            userId = adminId,
            username = "document.runtime.admin",
            role = "TENANT_ADMIN",
            database = database,
            companyIds = setOf(companyId)
        )
        val router = Router.router(vertx)
        router.route("/api/v1/*").handler { context ->
            context.put(TENANT_CONTEXT_KEY, principal)
            context.next()
        }
        DocumentRuntimeRoutes(DocumentBehaviorRegistry(listOf(DocumentProbeBehavior()))).register(router)
        val server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS)
        val client = HttpClient.newHttpClient()
        val base = "http://127.0.0.1:${server.actualPort()}/api/v1/tenant/documents/DocumentProbe"
        try {
            val created = sendJson(client, base, "POST",
                JsonObject().put("code", "PO-001").put("company_id", companyId.toString()))
            assertEquals(201, created.statusCode())
            val createdBody = JsonObject(created.body())
            val documentId = createdBody.getString("id")
            assertEquals(0, createdBody.getInteger("docstatus"))

            val updated = sendJson(client, "$base/$documentId", "PUT", JsonObject().put("code", "PO-002"))
            assertEquals(200, updated.statusCode())
            assertEquals("PO-002", JsonObject(updated.body()).getString("code"))

            val submitted = sendJson(client, "$base/$documentId/actions/submit", "POST", JsonObject())
            assertEquals(200, submitted.statusCode())
            assertEquals(1, JsonObject(submitted.body()).getInteger("docstatus"))

            val blocked = sendJson(client, base, "POST",
                JsonObject().put("code", "BLOCK").put("company_id", companyId.toString()))
            assertEquals(201, blocked.statusCode())
            val blockedId = JsonObject(blocked.body()).getString("id")
            val rejected = sendJson(client, "$base/$blockedId/actions/submit", "POST", JsonObject())
            assertEquals(400, rejected.statusCode())
            assertTrue(JsonObject(rejected.body()).getJsonObject("error").getString("message").contains("不允许提交"))

            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement("SELECT docstatus FROM org.document_probe WHERE id=?").use { statement ->
                    statement.setObject(1, UUID.fromString(blockedId))
                    statement.executeQuery().use { result ->
                        result.next()
                        assertEquals(0, result.getInt("docstatus"))
                    }
                }
                connection.prepareStatement(
                    "SELECT count(*) FROM audit.document_event WHERE document_id=? AND action='SUBMIT'"
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(blockedId))
                    statement.executeQuery().use { result ->
                        result.next()
                        assertEquals(0, result.getInt(1))
                    }
                }
            }
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
    }

    private fun sendJson(
        client: HttpClient,
        url: String,
        method: String,
        body: JsonObject
    ): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("content-type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body.encode()))
            .build(),
        HttpResponse.BodyHandlers.ofString()
    )
}
