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
        var migrationFailure: Throwable? = null
        try {
            configuredFlyway().migrate().migrationsExecuted
        } catch (error: Throwable) {
            migrationFailure = error
            throw error
        } finally {
            try {
                synchronizeFlywayHistory()
            } catch (syncError: Throwable) {
                // 同步失败不能掩盖 Flyway 原始迁移异常；成功迁移后的同步失败则必须显式失败。
                if (migrationFailure != null) migrationFailure.addSuppressed(syncError)
                else throw syncError
            }
        }
    }

    /** 正式部署只检查，不擅自执行待迁移脚本。 */
    fun validateCurrent() = withMigrationLock {
        val flyway = configuredFlyway()
        flyway.validate()
        val pending = flyway.info().pending()
        require(pending.isEmpty()) {
            "数据库存在 ${pending.size} 个待执行迁移，请先通过受控部署流程完成数据库迁移"
        }
        synchronizeFlywayHistory()
    }

    /**
     * 将 Flyway 原生历史镜像到统一治理表。
     *
     * installed_rank 作为稳定的 attempt_no：重复同步通过唯一约束幂等；
     * master 只反映每个版本/Repeatable migration 的最近一次执行状态，
     * attempt 表则保留成功与失败的每次执行记录。
     *
     * V9 尚未创建 attempt 表的首次升级期间跳过镜像；Flyway 完成全部迁移后再统一同步。
     */
    private fun synchronizeFlywayHistory() {
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            val registryExists = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('meta.schema_migration') IS NOT NULL").use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
            if (!registryExists) return
            val attemptTableExists = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('meta.schema_migration_attempt') IS NOT NULL").use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
            val historyExists = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL").use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
            if (!historyExists || !attemptTableExists) return

            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    // 先建立/更新每个迁移版本的当前状态，失败记录不会被过滤掉。
                    statement.executeUpdate(
                        """INSERT INTO meta.schema_migration
                           (id,source,version,doctype,checksum,status,plan_json,diagnostics,started_at,completed_at,error_message)
                           SELECT gen_random_uuid(),'FLYWAY',migration_version,'',
                                  COALESCE(checksum::text,''),CASE WHEN success THEN 'SUCCEEDED' ELSE 'FAILED' END,
                                  jsonb_build_object('description',description,'script',script,
                                      'installedBy',installed_by,'installedRank',installed_rank),
                                  CASE WHEN success THEN '[]'::jsonb ELSE
                                      jsonb_build_array(jsonb_build_object('code','FLYWAY_FAILED','message',
                                          'Flyway migration failed: ' || description)) END,
                                  installed_on,installed_on,
                                  CASE WHEN success THEN NULL ELSE 'Flyway migration failed: ' || description END
                           FROM (
                               SELECT DISTINCT ON (COALESCE(version,'R__' || description))
                                      COALESCE(version,'R__' || description) AS migration_version,
                                      checksum,success,description,script,installed_by,installed_rank,installed_on
                               FROM public.flyway_schema_history
                               ORDER BY COALESCE(version,'R__' || description),installed_rank DESC
                           ) latest
                           ON CONFLICT(source,version,doctype) DO UPDATE SET
                             checksum=EXCLUDED.checksum,status=EXCLUDED.status,
                             plan_json=EXCLUDED.plan_json,diagnostics=EXCLUDED.diagnostics,
                             started_at=EXCLUDED.started_at,completed_at=EXCLUDED.completed_at,
                             error_message=EXCLUDED.error_message"""
                    )

                    // 每条原生历史记录对应一个 attempt；重复同步只更新原 attempt，不重复插入。
                    statement.executeUpdate(
                        """INSERT INTO meta.schema_migration_attempt
                           (id,migration_id,attempt_no,status,checksum,plan_json,diagnostics,started_at,completed_at,error_message)
                           SELECT gen_random_uuid(),m.id,h.installed_rank,
                                  CASE WHEN h.success THEN 'SUCCEEDED' ELSE 'FAILED' END,
                                  COALESCE(h.checksum::text,''),
                                  jsonb_build_object('description',h.description,'script',h.script,
                                      'installedBy',h.installed_by,'installedRank',h.installed_rank),
                                  CASE WHEN h.success THEN '[]'::jsonb ELSE
                                      jsonb_build_array(jsonb_build_object('code','FLYWAY_FAILED','message',
                                          'Flyway migration failed: ' || h.description)) END,
                                  h.installed_on,h.installed_on,
                                  CASE WHEN h.success THEN NULL ELSE 'Flyway migration failed: ' || h.description END
                           FROM public.flyway_schema_history h
                           JOIN meta.schema_migration m
                             ON m.source='FLYWAY'
                            AND m.version=COALESCE(h.version,'R__' || h.description)
                            AND m.doctype=''
                           ON CONFLICT(migration_id,attempt_no) DO UPDATE SET
                             status=EXCLUDED.status,checksum=EXCLUDED.checksum,
                             plan_json=EXCLUDED.plan_json,diagnostics=EXCLUDED.diagnostics,
                             started_at=EXCLUDED.started_at,completed_at=EXCLUDED.completed_at,
                             error_message=EXCLUDED.error_message"""
                    )
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

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
