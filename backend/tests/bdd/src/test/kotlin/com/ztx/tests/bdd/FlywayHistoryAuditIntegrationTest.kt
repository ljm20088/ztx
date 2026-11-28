package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.platform.common.DatabaseConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager

@Testcontainers(disabledWithoutDocker = true)
class FlywayHistoryAuditIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    /** Given Flyway 原生历史，When 同步到统一迁移治理表，Then 每条历史都必须精确对应一个可核对的 FLYWAY attempt。 */
    @Test
    fun mirrorsEveryFlywayHistoryRowWithoutCrossSourceOrDuplicateRecords() {
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        val migrator = FlywayMigrator(config)

        migrator.migrate()
        migrator.validateCurrent()

        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM public.flyway_schema_history h
                    LEFT JOIN meta.schema_migration m
                      ON m.source='FLYWAY'
                     AND m.version=COALESCE(h.version,'R__' || h.description)
                     AND m.doctype=''
                    LEFT JOIN meta.schema_migration_attempt a
                      ON a.migration_id=m.id
                     AND a.attempt_no=h.installed_rank
                    WHERE m.id IS NULL OR a.id IS NULL
                       OR a.status <> CASE WHEN h.success THEN 'SUCCEEDED' ELSE 'FAILED' END
                       OR a.checksum <> COALESCE(h.checksum::text,'')
                       OR a.plan_json->>'installedRank' <> h.installed_rank::text
                    """.trimIndent()
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "每条 Flyway 原生历史都必须有且只有可核对的统一 attempt")
                }

                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM meta.schema_migration_attempt a
                    JOIN meta.schema_migration m ON m.id=a.migration_id
                    WHERE m.source='FLYWAY'
                      AND NOT EXISTS (
                        SELECT 1 FROM public.flyway_schema_history h
                        WHERE h.installed_rank=a.attempt_no
                          AND m.version=COALESCE(h.version,'R__' || h.description)
                      )
                    """.trimIndent()
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "统一治理表不得存在无原生历史来源的 FLYWAY attempt")
                }

                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM (
                      SELECT COALESCE(version,'R__' || description) AS version
                      FROM public.flyway_schema_history
                      GROUP BY COALESCE(version,'R__' || description)
                    ) h
                    LEFT JOIN meta.schema_migration m
                      ON m.source='FLYWAY' AND m.version=h.version AND m.doctype=''
                    WHERE m.id IS NULL
                    """.trimIndent()
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "每个 Flyway migration version 都必须有当前状态记录")
                }

                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM meta.schema_migration
                    WHERE source='RUNTIME'
                      AND version IN (SELECT COALESCE(version,'R__' || description) FROM public.flyway_schema_history)
                """.trimIndent()
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "FLYWAY 与 RUNTIME 来源必须隔离，不能互相覆盖")
                }

                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM (
                      SELECT migration_id, attempt_no
                      FROM meta.schema_migration_attempt a
                      JOIN meta.schema_migration m ON m.id=a.migration_id
                      WHERE m.source='FLYWAY'
                      GROUP BY migration_id, attempt_no
                      HAVING count(*) <> 1
                    ) duplicates
                    """.trimIndent()
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "同一 installed_rank 不得重复生成 attempt")
                }

                // 重复同步不应改变原生历史与统一治理历史的对应关系。
                val nativeCount = statement.executeQuery("SELECT count(*) FROM public.flyway_schema_history").use {
                    it.next(); it.getInt(1)
                }
                val attemptCount = statement.executeQuery(
                    """
                    SELECT count(*) FROM meta.schema_migration_attempt a
                    JOIN meta.schema_migration m ON m.id=a.migration_id
                    WHERE m.source='FLYWAY'
                    """.trimIndent()
                ).use { it.next(); it.getInt(1) }
                assertEquals(nativeCount, attemptCount)
                assertTrue(nativeCount > 0)
            }
        }

        migrator.migrate()
        migrator.validateCurrent()
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                val nativeCount = statement.executeQuery("SELECT count(*) FROM public.flyway_schema_history").use {
                    it.next(); it.getInt(1)
                }
                val attemptCount = statement.executeQuery(
                    """
                    SELECT count(*) FROM meta.schema_migration_attempt a
                    JOIN meta.schema_migration m ON m.id=a.migration_id
                    WHERE m.source='FLYWAY'
                    """.trimIndent()
                ).use { it.next(); it.getInt(1) }
                assertEquals(nativeCount, attemptCount, "多次 migrate/validate 后历史映射仍必须幂等")
            }
        }
    }
}
