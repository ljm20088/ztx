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
import java.nio.file.Files
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
        val flywayMigrator = FlywayMigrator(config)
        flywayMigrator.migrate()
        val initialFlywayHistory = DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT count(*),count(DISTINCT installed_rank)
                       FROM public.flyway_schema_history"""
                ).use { rows -> rows.next(); rows.getInt(1) to rows.getInt(2) }
            }
        }
        val initialFlywayAttempts = DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM meta.schema_migration_attempt a JOIN meta.schema_migration m ON m.id=a.migration_id WHERE m.source='FLYWAY'"
                ).use { rows -> rows.next(); rows.getInt(1) }
            }
        }
        assertEquals(initialFlywayHistory.first, initialFlywayAttempts)
        // 重复部署/同步必须幂等，不得为同一 installed_rank 重复创建 attempt。
        flywayMigrator.migrate()
        val repeatedFlywayAttempts = DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM meta.schema_migration_attempt a JOIN meta.schema_migration m ON m.id=a.migration_id WHERE m.source='FLYWAY'"
                ).use { rows -> rows.next(); rows.getInt(1) }
            }
        }
        assertEquals(initialFlywayAttempts, repeatedFlywayAttempts)
        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val service = SchemaMigrationService()
        val actorId = UUID.randomUUID()

        // Given Flyway 持有全局 session advisory lock，When 运行期迁移尝试执行，Then 必须等待同一把锁释放。
        val flywayLockConnection = DriverManager.getConnection(config.jdbcUrl, config.user, config.password)
        try {
            flywayLockConnection.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock(hashtext('ztx.schema-migration'), hashtext('global'))")
            }
            val lockDefinition = JsonObject()
                .put("doctype", "LockProbe")
                .put("table", JsonObject().put("schema", "org").put("name", "lock_probe"))
                .put("fields", JsonArray().add(JsonObject().put("name", "code").put("type", "string")))
            val blockedMigration = service.migrate(database, "LockProbe", 1, lockDefinition, actorId)
                .toCompletionStage().toCompletableFuture()
            Thread.sleep(300)
            assertTrue(!blockedMigration.isDone, "运行期事务锁必须等待 Flyway 持有的同键 session 锁")
            flywayLockConnection.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_unlock(hashtext('ztx.schema-migration'), hashtext('global'))")
            }
            assertEquals("SUCCEEDED", blockedMigration.get(20, TimeUnit.SECONDS).status)
        } finally {
            runCatching {
                flywayLockConnection.createStatement().use { statement ->
                    statement.execute("SELECT pg_advisory_unlock(hashtext('ztx.schema-migration'), hashtext('global'))")
                }
            }
            flywayLockConnection.close()
        }

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
                    .add(JsonObject().put("name", "remark").put("type", "text"))
                    .add(JsonObject().put("name", "category").put("type", "string").put("defaultValue", "general")))

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

            // Given 唯一索引创建因重复数据失败，When 清理冲突数据后以相同版本和相同定义重试，Then FAILED 迁移可恢复为 SUCCEEDED。
            val retryDefinition = JsonObject()
                .put("doctype", "SchemaProbe")
                .put("table", JsonObject().put("schema", "org").put("name", "schema_probe"))
                .put("fields", JsonArray()
                    .add(JsonObject().put("name", "code").put("type", "string").put("required", true).put("unique", true))
                    .add(JsonObject().put("name", "company_id").put("type", "uuid").put("required", true))
                     .add(JsonObject().put("name", "remark").put("type", "text"))
                    .add(JsonObject().put("name", "category").put("type", "string").put("defaultValue", "general")))
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    """INSERT INTO org.schema_probe(id,code,company_id) VALUES
                       (?, 'DUPLICATE', ?), (?, 'DUPLICATE', ?)"""
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.randomUUID())
                    statement.setObject(3, UUID.randomUUID())
                    statement.setObject(4, UUID.randomUUID())
                    statement.executeUpdate()
                }
            }
            val retryFailure = runCatching {
                service.migrate(database, "SchemaProbe", 4, retryDefinition, actorId)
                    .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            }.exceptionOrNull()
            assertTrue(retryFailure != null)
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    """DELETE FROM org.schema_probe WHERE id = (
                       SELECT id FROM org.schema_probe WHERE code='DUPLICATE' ORDER BY id DESC LIMIT 1)"""
                ).use { it.executeUpdate() }
            }
            val retried = service.migrate(database, "SchemaProbe", 4, retryDefinition, actorId)
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("SUCCEEDED", retried.status)
            // Given 第一次迁移失败后使用相同 checksum 重试成功，Then 两次尝试的失败诊断和成功状态都必须保留。
            val attemptHistory = database.withTransaction { connection ->
                connection.preparedQuery(
                    """SELECT string_agg(a.status, ',' ORDER BY a.attempt_no) AS statuses,
                              max(a.error_message) FILTER (WHERE a.attempt_no=1) AS first_error
                       FROM meta.schema_migration_attempt a
                       JOIN meta.schema_migration m ON m.id=a.migration_id
                       WHERE m.source='RUNTIME' AND m.doctype='SchemaProbe' AND m.version='4'"""
                ).execute().map { rows -> rows.first().getString("statuses") to rows.first().getString("first_error") }
            }.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertEquals("FAILED,SUCCEEDED", attemptHistory.first)
            assertTrue(attemptHistory.second.contains("duplicate key", ignoreCase = true))
            val concurrent = (1..2).map {
                service.migrate(database, "SchemaProbe", 5, retryDefinition, actorId)
                    .toCompletionStage().toCompletableFuture()
            }
            val concurrentResults = concurrent.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(concurrentResults[0].id, concurrentResults[1].id)
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM meta.schema_migration WHERE source='RUNTIME' AND doctype='SchemaProbe' AND version='4' AND status='SUCCEEDED'"
                ).use { statement ->
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
                }
                connection.prepareStatement(
                    "SELECT count(*) FROM meta.schema_migration WHERE source='RUNTIME' AND doctype='SchemaProbe' AND version='5'"
                ).use { statement ->
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
                }
            }
            // Given DocType 物理结构与元数据一致，When 执行只读漂移检查，Then 不应把已配置的默认值误报为漂移。
            val cleanDrift = service.inspectDrift(database, "SchemaProbe", retryDefinition)
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertTrue(cleanDrift.isEmpty(), "一致的物理 Schema 不应产生漂移诊断：$cleanDrift")

            // Given 已发布表被人工修改，When 只读检查 Schema 漂移，Then 返回缺失列/类型诊断且不自动修复。
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE org.schema_probe ALTER COLUMN code TYPE UUID USING gen_random_uuid()")
                    statement.execute("ALTER TABLE org.schema_probe ALTER COLUMN company_id DROP NOT NULL")
                    statement.execute("ALTER TABLE org.schema_probe ALTER COLUMN category SET DEFAULT 'corrupted'")
                    statement.execute("ALTER TABLE org.schema_probe DROP COLUMN remark")
                    val uniqueIndex = statement.executeQuery(
                        """SELECT indexname FROM pg_indexes
                           WHERE schemaname='org' AND tablename='schema_probe'
                             AND indexdef LIKE 'CREATE UNIQUE INDEX%' AND indexdef LIKE '%(code)'"""
                    ).use { rows -> if (rows.next()) rows.getString(1) else null }
                    if (uniqueIndex != null) statement.execute("DROP INDEX \"org\".\"$uniqueIndex\"")
                }
            }
            val drift = service.inspectDrift(database, "SchemaProbe", retryDefinition)
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            assertTrue(drift.any { (it as JsonObject).getString("code") == "COLUMN_TYPE_MISMATCH" })
            assertTrue(drift.any { (it as JsonObject).getString("code") == "COLUMN_MISSING" })
            assertTrue(drift.any { (it as JsonObject).getString("code") == "COLUMN_NULLABILITY_MISMATCH" })
            assertTrue(drift.any { (it as JsonObject).getString("code") == "INDEX_MISSING" })
            assertTrue(drift.any { (it as JsonObject).getString("code") == "DEFAULT_MISMATCH" })
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """SELECT udt_name FROM information_schema.columns
                           WHERE table_schema='org' AND table_name='schema_probe' AND column_name='code'"""
                    ).use { rows -> rows.next(); assertEquals("uuid", rows.getString(1)) }
                }
            }

        } finally {
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
    }
    /** Given PostgreSQL 非事务性 Flyway 迁移失败，When 修复后显式 repair 并重试，Then 原失败尝试保留且新尝试成功。 */
    @Test
    fun recordsNonTransactionalFlywayFailureAndExplicitRepairRetry() {
        val databaseName = "ztx_flyway_retry_" + UUID.randomUUID().toString().replace("-", "")
        val adminUrl = "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/postgres"
        val migrationDir = Files.createTempDirectory("ztx-flyway-retry")
        try {
            DriverManager.getConnection(adminUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute("CREATE DATABASE \"$databaseName\"") }
            }
            val v1 = """
                CREATE SCHEMA meta;
                CREATE TABLE meta.schema_migration (
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, version VARCHAR(128) NOT NULL,
                    doctype VARCHAR(120) NOT NULL DEFAULT '', schema_name VARCHAR(63), table_name VARCHAR(63),
                    checksum VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL, plan_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                    diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb, actor_id UUID,
                    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
                    error_message TEXT, UNIQUE(source,version,doctype)
                );
                CREATE TABLE meta.schema_migration_attempt (
                    id UUID PRIMARY KEY, migration_id UUID NOT NULL REFERENCES meta.schema_migration(id),
                    attempt_no INTEGER NOT NULL, status VARCHAR(16) NOT NULL, checksum VARCHAR(128) NOT NULL,
                    plan_json JSONB NOT NULL, diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb, actor_id UUID,
                    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
                    error_message TEXT, UNIQUE(migration_id,attempt_no)
                );
                CREATE TABLE public.flyway_probe (id INTEGER PRIMARY KEY, code TEXT NOT NULL);
                INSERT INTO public.flyway_probe(id,code) VALUES (1,'duplicate'),(2,'duplicate');
            """.trimIndent()
            val v2 = """
                CREATE UNIQUE INDEX CONCURRENTLY ux_flyway_probe_code ON public.flyway_probe(code);
            """.trimIndent()
            val v3 = """
                CREATE TABLE public.flyway_after_retry (id INTEGER PRIMARY KEY);
            """.trimIndent()
            Files.writeString(migrationDir.resolve("V1__flyway_retry_base.sql"), v1)
            Files.writeString(migrationDir.resolve("V2__flyway_retry_unique_index.sql"), v2)
            Files.writeString(migrationDir.resolve("V3__flyway_after_retry.sql"), v3)
            val config = DatabaseConfig(
                host = postgres.host, port = postgres.firstMappedPort, database = databaseName,
                user = postgres.username, password = postgres.password, poolSize = 2,
                migrationLocations = "filesystem:$migrationDir"
            )
            val migrator = FlywayMigrator(config)
            val firstRun = runCatching { migrator.migrate() }
            assertTrue(firstRun.isFailure, "重复数据应使非事务性唯一索引迁移失败")
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    "SELECT success FROM public.flyway_schema_history WHERE version='2'"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(!rows.getBoolean(1), "Flyway 原生历史应保留失败记录")
                    }
                }
                connection.prepareStatement(
                    """SELECT m.status,a.status FROM meta.schema_migration m
                       JOIN meta.schema_migration_attempt a ON a.migration_id=m.id
                       WHERE m.source='FLYWAY' AND m.version='2' AND a.attempt_no=2"""
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("FAILED", rows.getString(1))
                        assertEquals("FAILED", rows.getString(2))
                    }
                }
                connection.createStatement().use { statement ->
                    statement.execute("DROP INDEX CONCURRENTLY IF EXISTS public.ux_flyway_probe_code")
                    statement.execute("DELETE FROM public.flyway_probe WHERE id=2")
                }
            }

            migrator.repairFailedMigrations()
            migrator.migrate()
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    """SELECT string_agg(a.status,',' ORDER BY a.attempt_no)
                       FROM meta.schema_migration_attempt a
                       JOIN meta.schema_migration m ON m.id=a.migration_id
                       WHERE m.source='FLYWAY' AND m.version='2'"""
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("FAILED,SUCCEEDED", rows.getString(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT to_regclass('public.flyway_after_retry') IS NOT NULL"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean(1), "失败迁移之后的 PENDING 迁移也应在 repair 后正常执行")
                    }
                }
                connection.prepareStatement(
                    "SELECT status FROM meta.schema_migration WHERE source='FLYWAY' AND version='2'"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("SUCCEEDED", rows.getString(1))
                    }
                }
            }
        } finally {
            runCatching {
                DriverManager.getConnection(adminUrl, postgres.username, postgres.password).use { connection ->
                    connection.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$databaseName\" WITH (FORCE)") }
                }
            }
            Files.deleteIfExists(migrationDir.resolve("V1__flyway_retry_base.sql"))
            Files.deleteIfExists(migrationDir.resolve("V2__flyway_retry_unique_index.sql"))
            Files.deleteIfExists(migrationDir.resolve("V3__flyway_after_retry.sql"))
            Files.deleteIfExists(migrationDir)
        }
    }


}
