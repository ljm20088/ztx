package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.metadata.SchemaMigrationService
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    /** Given 租户数据库已执行 Flyway，When 动态 DocType 增加字段或发生不兼容变更，Then 迁移历史统一可查且失败不破坏原 Schema。 */
    @Test
    fun migratesRuntimeSchemaSafelyAndSharesFlywayHistory() {
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort, database = postgres.databaseName,
            user = postgres.username, password = postgres.password, poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        FlywayMigrator(config).migrate()
        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val service = SchemaMigrationService()
        val actorId = UUID.randomUUID()
        val original = JsonObject()
            .put("doctype", "SchemaProbe")
            .put("table", JsonObject().put("schema", "org").put("name", "schema_probe"))
            .put("fields", JsonArray()
                .add(JsonObject().put("name", "code").put("type", "string").put("required", true))
                .add(JsonObject().put("name", "company_id").put("type", "uuid").put("required", true)))

        try {
            // Given 一个尚无物理表的动态 DocType，When 执行第一版迁移，Then 创建标准 PostgreSQL 表和系统列。
            val first = service.migrate(database, "SchemaProbe", 1, original, actorId)
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("SUCCEEDED", first.status)
            assertEquals("EXPAND_ONLY", first.plan.getString("policy"))

            val expanded = JsonObject()
                .put("doctype", "SchemaProbe")
                .put("table", JsonObject().put("schema", "org").put("name", "schema_probe"))
                .put("fields", JsonArray()
                    .add(JsonObject().put("name", "code").put("type", "string").put("required", true))
                    .add(JsonObject().put("name", "company_id").put("type", "uuid").put("required", true))
                    .add(JsonObject().put("name", "remark").put("type", "text")))

            // When 第二版只增加可选字段，Then 只追加物理列且重复执行同一版本是幂等的。
            val second = service.migrate(database, "SchemaProbe", 2, expanded, actorId)
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            val repeated = service.migrate(database, "SchemaProbe", 2, expanded, actorId)
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("SUCCEEDED", second.status)
            assertEquals(second.id, repeated.id)

            // Given 已有 code:text 物理列，When 新版本试图将其改成 UUID，Then 拒绝危险类型变更并记录 FAILED。
            val incompatible = JsonObject()
                .put("doctype", "SchemaProbe")
                .put("table", JsonObject().put("schema", "org").put("name", "schema_probe"))
                .put("fields", JsonArray()
                    .add(JsonObject().put("name", "code").put("type", "uuid").put("required", true))
                    .add(JsonObject().put("name", "company_id").put("type", "uuid").put("required", true))
                    .add(JsonObject().put("name", "remark").put("type", "text")))
            val failure = runCatching {
                service.migrate(database, "SchemaProbe", 3, incompatible, actorId)
                    .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            }.exceptionOrNull()
            assertTrue(failure != null)

            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    """SELECT udt_name FROM information_schema.columns
                       WHERE table_schema='org' AND table_name='schema_probe' AND column_name='code'"""
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("text", rows.getString(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT status FROM meta.schema_migration WHERE source='RUNTIME' AND doctype='SchemaProbe' AND version='3'"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("FAILED", rows.getString(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT count(*) FROM meta.schema_migration WHERE source='FLYWAY' AND version='6'"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        } finally {
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
    }
}
