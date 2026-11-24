package com.ztx.tests.bdd

import com.ztx.adapters.postgres.PgDatabase
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class PostgresTransactionTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    @Test
    fun failedTransactionRollsBackWrites() {
        // Given：PostgreSQL 已启动，测试表在事务外创建
        val config = DatabaseConfig(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            poolSize = 2,
            migrationLocations = "filesystem:database/migrations"
        )
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE transaction_probe (id integer PRIMARY KEY)") }
        }

        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        try {
            // When：事务写入一行后返回失败，触发事务回滚
            val operation = database.withTransaction { connection ->
                connection.query("INSERT INTO transaction_probe(id) VALUES (1)").execute()
                    .compose { Future.failedFuture<Int>("force rollback for test") }
            }
            assertThrows(ExecutionException::class.java) {
                operation.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            }

            // Then：事务失败后，数据库中没有留下部分写入
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM transaction_probe").use { result ->
                        result.next()
                        assertEquals(0, result.getInt(1))
                    }
                }
            }
        } finally {
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }
}
