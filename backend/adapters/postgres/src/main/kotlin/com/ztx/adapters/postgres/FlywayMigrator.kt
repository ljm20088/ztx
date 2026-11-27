package com.ztx.adapters.postgres

import com.ztx.platform.common.DatabaseConfig
import org.flywaydb.core.Flyway
import java.sql.DriverManager

class FlywayMigrator(private val config: DatabaseConfig) {
    private fun configuredFlyway() = Flyway.configure()
        .dataSource(config.jdbcUrl, config.user, config.password)
        .locations(config.migrationLocations)
        .validateMigrationNaming(true)
        .load()

    fun migrate(): Int = withMigrationLock {
        val executed = configuredFlyway().migrate().migrationsExecuted
        synchronizeFlywayHistory()
        executed
    }

    /** 正式部署只检查，不擅自执行待迁移脚本。 */
    fun validateCurrent() {
        val flyway = configuredFlyway()
        flyway.validate()
        val pending = flyway.info().pending()
        require(pending.isEmpty()) {
            "数据库存在 ${pending.size} 个待执行迁移，请先通过受控部署流程完成数据库迁移"
        }
        synchronizeFlywayHistory()
    }

    /**
     * Flyway 保留自己的原生执行历史；此处将成功记录镜像到统一治理表，
     * 与运行期 RUNTIME Schema Migration 共用查询、审计和运维入口。
     */

    /** Flyway 与运行期动态 DDL 共用同一 PostgreSQL advisory lock。 */
    private fun <T> withMigrationLock(work: () -> T): T {
        val connection = DriverManager.getConnection(config.jdbcUrl, config.user, config.password)
        try {
            connection.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock(hashtext('ztx.schema-migration'), hashtext('global'))")
            }
            return work()
        } finally {
            try {
                connection.createStatement().use { statement ->
                    statement.execute("SELECT pg_advisory_unlock(hashtext('ztx.schema-migration'), hashtext('global'))")
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun synchronizeFlywayHistory() {
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            val registryExists = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('meta.schema_migration') IS NOT NULL").use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
            if (!registryExists) return
            val historyExists = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL").use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
            if (!historyExists) return
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """INSERT INTO meta.schema_migration
                       (id,source,version,doctype,checksum,status,plan_json,diagnostics,started_at,completed_at)
                       SELECT gen_random_uuid(),'FLYWAY',
                              COALESCE(version,'R__' || description),'',
                              COALESCE(checksum::text,''),'SUCCEEDED',
                              jsonb_build_object('description',description,'installedBy',installed_by),
                              '[]'::jsonb,installed_on,installed_on
                       FROM public.flyway_schema_history
                       WHERE success=TRUE
                       ON CONFLICT(source,version,doctype) DO UPDATE SET
                         checksum=EXCLUDED.checksum,status='SUCCEEDED',
                         plan_json=EXCLUDED.plan_json,diagnostics='[]'::jsonb,
                         completed_at=EXCLUDED.completed_at,error_message=NULL"""
                )
            }
        }
    }
}
