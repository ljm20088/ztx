package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.OutboxEvent
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.adapters.postgres.PostgresOutboxStore
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class PostgresOutboxTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    private lateinit var config: DatabaseConfig
    private lateinit var vertx: Vertx
    private lateinit var database: PgDatabase
    private lateinit var store: PostgresOutboxStore

    @BeforeEach
    fun setUp() {
        val projectDir = java.nio.file.Path.of(System.getProperty("ztx.projectDir"))
        config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort,
            database = postgres.databaseName, user = postgres.username,
            password = postgres.password, poolSize = 8,
            migrationLocations = "filesystem:${projectDir.resolve("database/migrations")}"
        )
        FlywayMigrator(config).migrate()
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS platform.outbox_probe (id UUID PRIMARY KEY, value TEXT NOT NULL)")
                statement.execute("TRUNCATE platform.processed_task, platform.background_task, platform.outbox_event, platform.outbox_probe CASCADE")
            }
        }
        vertx = Vertx.vertx()
        database = PgDatabase(vertx, config)
        store = PostgresOutboxStore(database)
    }

    @AfterEach
    fun tearDown() {
        database.close()
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    @Test
    fun outboxAndBusinessWriteShareOneTransaction() {
        // Given：业务写入与 Outbox 写入使用同一个数据库事务
        val operation = database.withTransaction { connection ->
            connection.preparedQuery("INSERT INTO platform.outbox_probe(id, value) VALUES ($1, $2)")
                .execute(Tuple.of(UUID.randomUUID().toString(), "business-write"))
                .compose { store.enqueue(connection, event("atomicity:${UUID.randomUUID()}")) }
                .compose { Future.failedFuture<UUID>("force transaction rollback") }
        }
        // When：业务事务在提交前回滚
        assertThrows(ExecutionException::class.java) { await(operation) }
        // Then：业务数据、Outbox 事件和任务记录都不存在
        assertEquals(0L, scalar("SELECT count(*) FROM platform.outbox_probe"))
        assertEquals(0L, scalar("SELECT count(*) FROM platform.outbox_event"))
        assertEquals(0L, scalar("SELECT count(*) FROM platform.background_task"))
    }

    @Test
    fun transactionCanCommitWhenWorkReturnsVoid() {
        // Given：事务工作只执行数据库写入，返回 Vert.x 的 Future<Void>
        val id = UUID.randomUUID()
        val operation = database.withTransaction { connection ->
            connection.preparedQuery("INSERT INTO platform.outbox_probe(id, value) VALUES ($1, $2)")
                .execute(Tuple.of(id, "void-transaction"))
                .mapEmpty()
        }

        // When：事务正常完成
        await(operation)

        // Then：Void 的 null 成功结果不会被事务包装层误判为异常
        assertEquals(1L, scalar("SELECT count(*) FROM platform.outbox_probe WHERE id = '$id'"))
    }

    @Test
    fun duplicateIdempotencyKeyDoesNotCreateAnotherEventOrTask() {
        // Given：调用方为同一业务事件重复使用幂等键
        val event = event("duplicate:${UUID.randomUUID()}")
        val ids = await(database.withTransaction { connection ->
            store.enqueue(connection, event).compose { first ->
                store.enqueue(connection, event).map { second -> first to second }
            }
        })
        // When / Then：两次返回同一事件 ID，且只产生一条事件和一条任务
        assertEquals(ids.first, ids.second)
        assertEquals(1L, scalar("SELECT count(*) FROM platform.outbox_event"))
        assertEquals(1L, scalar("SELECT count(*) FROM platform.background_task"))
    }

    @Test
    fun concurrentWorkersNeverReceiveTheSameTaskLease() {
        // Given：同一任务类型下有 20 条可执行任务
        await(database.withTransaction { connection ->
            (1..20).fold(Future.succeededFuture<Void>()) { previous, index ->
                previous.compose { store.enqueue(connection, event("concurrent:$index")).mapEmpty() }
            }
        })
        // When：两个 Worker 几乎同时领取不同批次
        val first = store.claim("worker-a", batchSize = 10, taskTypes = setOf("test.created"))
            .toCompletionStage().toCompletableFuture()
        val second = store.claim("worker-b", batchSize = 10, taskTypes = setOf("test.created"))
            .toCompletionStage().toCompletableFuture()
        CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS)
        val claimed = first.get() + second.get()
        // Then：两个 Worker 领取到的任务 ID 没有交集
        assertEquals(20, claimed.size)
        assertEquals(20, claimed.map { it.id }.distinct().size)
        // Then：JSONB 载荷通过 Vert.x JSON codec 解码后仍保留业务字段
        assertTrue(claimed.all { it.payload.getString("value")?.startsWith("concurrent:") == true })
        assertEquals(20L, scalar("SELECT count(*) FROM platform.background_task WHERE status = 'RUNNING'"))
    }

    @Test
    fun expiredLeaseCanBeRecoveredByAnotherWorker() {
        // Given：任务已被 Worker A 领取，但 Worker A 在租约到期前崩溃
        await(database.withTransaction { connection ->
            store.enqueue(connection, event("recovery:${UUID.randomUUID()}")).mapEmpty()
        })
        val first = await(store.claim("worker-a", batchSize = 1, leaseSeconds = 1, taskTypes = setOf("test.created"))).single()
        // When：租约到期后 Worker B 再次领取
        Thread.sleep(1200)
        val recovered = await(store.claim("worker-b", batchSize = 1, leaseSeconds = 1, taskTypes = setOf("test.created"))).single()
        // Then：同一任务可恢复，但旧 Worker 的租约令牌已经失效
        assertEquals(first.id, recovered.id)
        assertNotEquals(first.leaseToken, recovered.leaseToken)
        assertEquals(2, recovered.attemptCount)
    }

    @Test
    fun expiredLeaseCannotBeRenewedOrCommitConsumerEffects() {
        // Given：Worker A 领取任务后超过租约期限，且尚未被其他 Worker 接管
        val id = await(database.withTransaction { connection ->
            store.enqueue(connection, event("expired-owner:${UUID.randomUUID()}"))
        })
        val task = await(store.claim("worker-a", batchSize = 1, leaseSeconds = 1, taskTypes = setOf("test.created"))).single()
        Thread.sleep(1200)

        // When：过期 Worker 尝试续租、执行副作用或记录失败
        assertFalse(await(store.renew(task, leaseSeconds = 30)))
        val effectId = UUID.randomUUID()
        val staleProcess = database.withTransaction { connection ->
            store.process(connection, task) { transaction ->
                transaction.preparedQuery("INSERT INTO platform.outbox_probe(id, value) VALUES ($1, $2)")
                    .execute(Tuple.of(effectId.toString(), "stale-worker")).mapEmpty()
            }
        }
        assertThrows(ExecutionException::class.java) { await(staleProcess) }
        assertThrows(ExecutionException::class.java) {
            await(store.fail(task, IllegalStateException("stale worker failure")))
        }

        // Then：过期 Worker 的数据库副作用和幂等标记回滚，任务可由新 Worker 接管
        assertEquals(0L, scalar("SELECT count(*) FROM platform.outbox_probe WHERE id = '$effectId'"))
        assertEquals(0L, scalar("SELECT count(*) FROM platform.processed_task"))
        val recovered = await(store.claim("worker-b", batchSize = 1, leaseSeconds = 30, taskTypes = setOf("test.created"))).single()
        assertEquals(id, recovered.id)
        assertEquals(2, recovered.attemptCount)
        assertNotEquals(task.leaseToken, recovered.leaseToken)
    }

    @Test
    fun failedTaskCanBeManuallyReplayedAfterRetryLimit() {
        // Given：任务最大尝试次数为 1，首次领取后处理失败
        val id = await(database.withTransaction { connection ->
            store.enqueue(connection, event("dead:${UUID.randomUUID()}", maxAttempts = 1))
        })
        val claimed = await(store.claim("worker-a", batchSize = 1, taskTypes = setOf("test.created"))).single()
        // When：记录失败并由运维人员重放
        await(store.fail(claimed, IllegalStateException("temporary failure")))
        assertEquals("DEAD", textValue("SELECT status FROM platform.background_task WHERE id = '$id'"))
        assertTrue(await(store.replay(id, "operator-1")))
        // Then：任务回到 PENDING，人工操作人和重放次数留痕
        assertEquals("PENDING", textValue("SELECT status FROM platform.background_task WHERE id = '$id'"))
        assertEquals("operator-1", textValue("SELECT replayed_by FROM platform.background_task WHERE id = '$id'"))
        assertEquals(1L, scalar("SELECT replay_count FROM platform.background_task WHERE id = '$id'"))
        assertFalse(await(store.replay(id, "operator-2")))
    }

    @Test
    fun consumerDatabaseEffectsAndIdempotencyMarkerCommitTogether() {
        // Given：Worker 已领取一条任务，消费者副作用写入同一数据库
        val id = await(database.withTransaction { connection ->
            store.enqueue(connection, event("idempotent:${UUID.randomUUID()}"))
        })
        val task = await(store.claim("worker-a", batchSize = 1, taskTypes = setOf("test.created"))).single()
        val effectId = UUID.randomUUID()
        val handler: (SqlConnection) -> Future<Void> = { connection ->
            connection.preparedQuery("INSERT INTO platform.outbox_probe(id, value) VALUES ($1, $2)")
                .execute(Tuple.of(effectId.toString(), "handled")).mapEmpty()
        }
        // When：处理器在事务中完成副作用、幂等标记和任务状态
        await(database.withTransaction { connection -> store.process(connection, task, handler) })
        // Then：任务成功，且数据库副作用和处理标记各只有一条
        assertEquals("SUCCEEDED", textValue("SELECT status FROM platform.background_task WHERE id = '$id'"))
        assertEquals(1L, scalar("SELECT count(*) FROM platform.outbox_probe WHERE id = '$effectId'"))
        assertEquals(1L, scalar("SELECT count(*) FROM platform.processed_task"))
        // And：旧租约再次投递不能重复执行处理器，也不能覆盖已完成任务
        val duplicate = database.withTransaction { connection -> store.process(connection, task, handler) }
        assertThrows(ExecutionException::class.java) { await(duplicate) }
        assertEquals(1L, scalar("SELECT count(*) FROM platform.outbox_probe WHERE id = '$effectId'"))
    }

    @Test
    fun workerWithoutRegisteredTaskTypesDoesNotConsumePendingTasks() {
        // Given：数据库中存在任务，但当前 Worker 尚未注册任何处理器
        await(database.withTransaction { connection ->
            store.enqueue(connection, event("unhandled:${UUID.randomUUID()}")).mapEmpty()
        })
        // When：Worker 使用空任务类型集合领取任务
        val claimed = await(store.claim("worker-empty", taskTypes = emptySet()))
        // Then：不领取任务，待处理记录保持 PENDING
        assertTrue(claimed.isEmpty())
        assertEquals(1L, scalar("SELECT count(*) FROM platform.background_task WHERE status = 'PENDING'"))
        assertEquals(1L, scalar("SELECT pending_count FROM platform.background_task_metrics"))
    }

    private fun event(key: String, maxAttempts: Int = 8) = OutboxEvent(
        eventType = "test.created", payload = JsonObject().put("value", key),
        idempotencyKey = key, aggregateType = "test", aggregateId = key, maxAttempts = maxAttempts
    )

    private fun <T> await(future: Future<T>): T =
        future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun scalar(sql: String): Long =
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> result.next(); result.getLong(1) }
            }
        }

    private fun textValue(sql: String): String =
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> result.next(); result.getString(1) }
            }
        }
}
