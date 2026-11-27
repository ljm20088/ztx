package com.ztx.tests.bdd

import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.document.DocumentBehavior
import com.ztx.modules.document.DocumentBehaviorContext
import com.ztx.modules.document.DocumentExtensionMode
import com.ztx.modules.iam.TenantPrincipal
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class PostgresTransactionTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    /** Given Kotlin 业务扩展在当前事务内写入后校验失败，When Hook 返回失败，Then 业务写入必须整体回滚。 */
    @Test
    fun failedDocumentBehaviorRollsBackWrites() {
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
        val principal = TenantPrincipal(
            UUID.randomUUID(), "document-test", config.database, UUID.randomUUID(),
            "tenant.admin", "TENANT_ADMIN", database
        )
        val behavior = object : DocumentBehavior {
            override val doctype = "transaction_probe"
            override val mode = DocumentExtensionMode.METADATA_WITH_KOTLIN_FILE

            override fun beforeCreate(context: DocumentBehaviorContext, document: JsonObject): Future<Void> =
                context.connection.query("INSERT INTO transaction_probe(id) VALUES (1)").execute()
                    .mapEmpty()
                    .compose<Void> { Future.failedFuture<Void>("扩展校验失败，验证事务回滚") }
        }

        try {
            // When：Document Runtime 在一个事务中执行扩展 Hook，Hook 写入后返回失败
            val operation = database.withTransaction { connection ->
                behavior.beforeCreate(
                    DocumentBehaviorContext("transaction_probe", JsonObject(), principal, connection),
                    JsonObject()
                )
            }
            assertThrows(ExecutionException::class.java) {
                operation.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            }

            // Then：扩展 Hook 的写入和 Document 写入处于同一事务，失败后没有部分数据
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
