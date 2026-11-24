package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.platform.common.DatabaseConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager

@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    @Test
    fun migrationCreatesPlatformSchemas() {
        // Given：PostgreSQL 18.6 容器已启动，数据库为空
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            poolSize = 2,
            migrationLocations = "filesystem:${projectDir.resolve("database/migrations")}"
        )
        // When：通过 Flyway 执行版本化迁移
        FlywayMigrator(config).migrate()
        // Then：平台模块 Schema 已建立
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('platform', 'iam', 'meta', 'audit')"
                ).use { result ->
                    result.next()
                    assertEquals(4, result.getInt(1))
                }
            }
        }
    }
}
