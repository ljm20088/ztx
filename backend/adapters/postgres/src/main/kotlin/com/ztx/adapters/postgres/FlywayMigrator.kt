package com.ztx.adapters.postgres

import com.ztx.platform.common.DatabaseConfig
import org.flywaydb.core.Flyway

class FlywayMigrator(private val config: DatabaseConfig) {
    private fun configuredFlyway() = Flyway.configure()
        .dataSource(config.jdbcUrl, config.user, config.password)
        .locations(config.migrationLocations)
        .validateMigrationNaming(true)
        .load()

    fun migrate(): Int = configuredFlyway().migrate().migrationsExecuted

    /** 正式部署只检查，不擅自执行待迁移脚本。 */
    fun validateCurrent() {
        val flyway = configuredFlyway()
        flyway.validate()
        val pending = flyway.info().pending()
        require(pending.isEmpty()) {
            "数据库存在 ${pending.size} 个待执行迁移，请先通过受控部署流程完成数据库迁移"
        }
    }
}
