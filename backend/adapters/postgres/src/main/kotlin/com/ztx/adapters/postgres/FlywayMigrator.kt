package com.ztx.adapters.postgres

import com.ztx.platform.common.DatabaseConfig
import org.flywaydb.core.Flyway

class FlywayMigrator(private val config: DatabaseConfig) {
    fun migrate(): Int =
        Flyway.configure()
            .dataSource(config.jdbcUrl, config.user, config.password)
            .locations(config.migrationLocations)
            .validateMigrationNaming(true)
            .load()
            .migrate()
            .migrationsExecuted
}
