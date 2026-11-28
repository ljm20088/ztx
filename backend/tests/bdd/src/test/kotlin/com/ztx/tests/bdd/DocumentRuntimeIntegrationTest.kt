package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.document.Db
import com.ztx.modules.document.DbProvider
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

        override fun beforeUpdate(context: DocumentBehaviorContext, before: JsonObject, changes: JsonObject): Future<Void> {
            if (changes.getString("code") == "BLOCK_UPDATE") {
                return Future.failedFuture(IllegalArgumentException("业务规则不允许修改"))
            }
            return Future.succeededFuture()
        }

        override fun beforeAction(
            context: DocumentBehaviorContext,
            before: JsonObject,
            action: String
        ): Future<Void> {
            if (action != "submit") return Future.succeededFuture()
            return context.db.getValue(context.doctype, context.documentId!!, "code").compose { code ->
                if (code == "BLOCK") Future.failedFuture<Void>(IllegalArgumentException("业务规则不允许提交"))
                else Future.succeededFuture()
            }
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
                 .add(JsonObject().put("name", "company_id").put("type", "uuid").put("required", true))
                .add(JsonObject().put("name", "payload").put("type", "json").put("loadOnDemand", true))
                .add(JsonObject().put("name", "remark").put("type", "string")))
            .put("permissions", JsonObject()
                .put("read", JsonArray().add("TENANT_ADMIN"))
                .put("create", JsonArray().add("TENANT_ADMIN"))
                .put("update", JsonArray().add("TENANT_ADMIN"))
                .put("submit", JsonArray().add("TENANT_ADMIN"))
                .put("cancel", JsonArray().add("TENANT_ADMIN")))
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
                       id uuid PRIMARY KEY, docstatus integer NOT NULL DEFAULT 0, version integer NOT NULL DEFAULT 1,
                       created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                       updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                       code text NOT NULL, company_id uuid NOT NULL, payload jsonb, remark text)"""
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
                JsonObject().put("code", "PO-001").put("company_id", companyId.toString())
                    .put("payload", JsonObject().put("content", "large-document-body")).put("remark", "initial remark"))
            assertEquals(201, created.statusCode())
            val createdBody = JsonObject(created.body())
            val documentId = createdBody.getString("id")
            val originalVersion = createdBody.getInteger("version")
            assertEquals(0, createdBody.getInteger("docstatus"))
            assertEquals(1, originalVersion)

            val updated = sendJson(client, "$base/$documentId", "PUT",
                JsonObject().put("version", originalVersion).put("code", "PO-002"))
            assertEquals(200, updated.statusCode())
            val updatedBody = JsonObject(updated.body())
            assertEquals("PO-002", updatedBody.getString("code"))
            assertEquals(2, updatedBody.getInteger("version"))

            // Given 两个会话读取同一版本，When 后提交的会话尝试保存旧版本，Then 明确冲突且不覆盖先保存的数据。
            val staleUpdate = sendJson(client, "$base/$documentId", "PUT",
                JsonObject().put("version", originalVersion).put("code", "STALE"))
            assertEquals(409, staleUpdate.statusCode())
            assertTrue(JsonObject(staleUpdate.body()).getJsonObject("error").getString("message").contains("其他用户修改"))

            // Given 当前 Hook/业务请求已绑定租户事务，When 使用 db.get/list/new/update，Then 操作复用同一连接并返回动态 Document 或原始 JSON。
            val loaded = database.withTransaction { connection ->
                Db(principal, connection).get("DocumentProbe", UUID.fromString(documentId))
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("PO-002", loaded.getString("code"))
            assertEquals(2, loaded.version)
            // Given Document 默认不加载大字段，When 通过字段 API 显式读取，Then 仅按需返回大字段内容。
            val deferredReadRejected = runCatching { loaded["payload"] }.exceptionOrNull()
            assertTrue(deferredReadRejected is IllegalStateException)
            val payload = DbProvider().transaction(principal) { db ->
                db.getValue("DocumentProbe", UUID.fromString(documentId), "payload")
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("large-document-body", (payload as JsonObject).getString("content"))
            val fieldResponse = client.send(
                HttpRequest.newBuilder(URI.create("$base/$documentId/fields/payload")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, fieldResponse.statusCode())
            assertEquals("large-document-body", JsonObject(fieldResponse.body()).getJsonObject("value").getString("content"))

            val listed = database.withTransaction { connection ->
                Db(principal, connection).list("DocumentProbe", JsonObject().put("page", 1))
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals(1L, listed.getLong("total"))
            assertEquals("PO-002", listed.getJsonArray("items").getJsonObject(0).getString("code"))

            val newDocument = database.withTransaction { connection ->
                Db(principal, connection).new("DocumentProbe")
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals(null, newDocument.id)
            assertEquals(0, newDocument.version)

            // Given db.new 创建的内存 Document，When 显式 insert，Then 创建 Hook、写入与审计通过统一门面完成。
            val facadeCreated = database.withTransaction { connection ->
                val db = Db(principal, connection)
                db.new("DocumentProbe").compose { draft ->
                    draft["code"] = "PO-DB-INSERT"
                    draft["company_id"] = companyId
                    db.insert(draft)
                }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("PO-DB-INSERT", facadeCreated.getString("code"))
            assertEquals(1, facadeCreated.version)

            // Given 统一 db.update 的业务 Hook 拒绝修改，When 尝试保存，Then 数据保持不变且事务回滚。
            facadeCreated["code"] = "BLOCK_UPDATE"
            val updateRejected = runCatching {
                database.withTransaction { connection -> Db(principal, connection).update(facadeCreated) }
                    .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            }.exceptionOrNull()
            assertTrue(updateRejected != null)
            val afterRejected = database.withTransaction { connection ->
                Db(principal, connection).get("DocumentProbe", facadeCreated.id!!)
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("PO-DB-INSERT", afterRejected.getString("code"))

            // Given Document 已通过统一 db 门面创建，When 执行 submit/cancel，Then 生命周期、版本和审计仍由同一 Runtime 管理。
            val facadeSubmitted = DbProvider().transaction(principal) { db -> db.submit(afterRejected) }
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals(1, facadeSubmitted.docstatus)
            val facadeCancelled = DbProvider().transaction(principal) { db -> db.cancel(facadeSubmitted) }
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals(2, facadeCancelled.docstatus)

            loaded["code"] = "PO-DB-003"
            loaded["remark"] = null
            val dbUpdated = database.withTransaction { connection ->
                Db(principal, connection).update(loaded)
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("PO-DB-003", dbUpdated.getString("code"))
            assertEquals(null, dbUpdated["remark"])
            assertEquals(3, dbUpdated.version)

            val scopedRead = DbProvider().transaction(principal) { db ->
                db.transaction { scoped ->
                    scoped.exists("DocumentProbe", UUID.fromString(documentId)).compose { exists ->
                        scoped.getAll("DocumentProbe", listOf(UUID.fromString(documentId))).map { documents ->
                            exists to documents
                        }
                    }
                }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertTrue(scopedRead.first)
            assertEquals(1, scopedRead.second.size)
            assertEquals("PO-DB-003", scopedRead.second.single().getString("code"))

            val submitted = sendJson(client, "$base/$documentId/actions/submit", "POST",
                JsonObject().put("version", dbUpdated.version))
            assertEquals(200, submitted.statusCode())
            assertEquals(1, JsonObject(submitted.body()).getInteger("docstatus"))

            val blocked = sendJson(client, base, "POST",
                JsonObject().put("code", "BLOCK").put("company_id", companyId.toString()))
            assertEquals(201, blocked.statusCode())
            val blockedId = JsonObject(blocked.body()).getString("id")
            val rejected = sendJson(client, "$base/$blockedId/actions/submit", "POST",
                JsonObject().put("version", JsonObject(blocked.body()).getInteger("version")))
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
