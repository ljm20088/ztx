package com.ztx.server

import com.ztx.adapters.postgres.ClaimedBackgroundTask
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.adapters.postgres.PostgresOutboxStore
import com.ztx.modules.iam.TenantDatabaseRegistry
import com.ztx.modules.iam.TenantTaskContext
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory

fun interface BackgroundTaskHandler {
    /** 处理器使用传入连接完成数据库副作用，使副作用、幂等标记和任务状态同事务提交。 */
    fun handle(task: ClaimedBackgroundTask, connection: SqlConnection, tenant: TenantTaskContext?): Future<Void>
}

/** PostgreSQL 任务轮询器；多实例并发由 SKIP LOCKED、租约和租约令牌协调。 */
class BackgroundTaskWorker(
    private val vertx: Vertx,
    private val database: PgDatabase,
    private val store: PostgresOutboxStore,
    private val workerId: String,
    private val handlers: Map<String, BackgroundTaskHandler> = emptyMap(),
    private val pollIntervalMs: Long = 1000,
    private val batchSize: Int = 1,
    private val leaseSeconds: Int = 60,
    private val tenantDatabases: TenantDatabaseRegistry? = null
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(BackgroundTaskWorker::class.java)
    private var timerId: Long? = null
    private var heartbeatTimerId: Long? = null
    @Volatile private var polling = false

    init {
        require(workerId.isNotBlank()) { "workerId 不能为空" }
        require(pollIntervalMs >= 100) { "pollIntervalMs 不能小于 100 毫秒" }
        require(batchSize in 1..100) { "batchSize 必须在 1 到 100 之间" }
        require(leaseSeconds in 1..3600) { "leaseSeconds 必须在 1 到 3600 之间" }
    }

    fun start(): Future<Void> {
        check(timerId == null) { "Worker 已启动" }
        return updateHeartbeat().onSuccess {
            timerId = vertx.setPeriodic(pollIntervalMs) { poll() }
            heartbeatTimerId = vertx.setPeriodic(15_000L) {
                updateHeartbeat().onFailure { error -> logger.warn("Worker 心跳写入失败，workerId={}", workerId, error) }
            }
            poll()
        }
    }

    private fun updateHeartbeat(): Future<Void> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """INSERT INTO platform.worker_instance (worker_id, started_at, last_seen_at)
                   VALUES ($1, clock_timestamp(), clock_timestamp())
                   ON CONFLICT (worker_id) DO UPDATE SET last_seen_at = clock_timestamp()"""
            ).execute(Tuple.of(workerId)).mapEmpty()
        }

    private fun poll() {
        if (polling) return
        polling = true
        store.claim(workerId, batchSize, leaseSeconds, handlers.keys)
            .compose { tasks -> tasks.fold(Future.succeededFuture<Void>()) { previous, task ->
                previous.compose { processOne(task) }
            } }
            .onComplete { result ->
                polling = false
                if (result.failed()) logger.error("后台任务轮询失败", result.cause())
            }
    }

    private fun processOne(task: ClaimedBackgroundTask): Future<Void> {
        val handler = handlers[task.taskType] ?: return Future.succeededFuture()
        val heartbeatId = vertx.setPeriodic((leaseSeconds * 1000L / 3).coerceAtLeast(100)) {
            store.renew(task, leaseSeconds)
                .onSuccess { renewed -> if (!renewed) logger.warn("后台任务租约已失效，taskId={}", task.id) }
                .onFailure { error -> logger.warn("后台任务租约续期失败，taskId={}", task.id, error) }
        }
        val tenantId = task.tenantId
        val tenantContext = when {
            tenantId == null -> Future.succeededFuture<TenantTaskContext?>(null)
            tenantDatabases == null -> Future.failedFuture("租户任务缺少可信租户数据库注册表")
            else -> tenantDatabases.resolve(tenantId).map { resolved ->
                TenantTaskContext(resolved.tenant.id, resolved.tenant.code,
                    resolved.tenant.databaseName, resolved.database)
            }
        }
        return tenantContext.compose { tenant ->
            database.withTransaction { connection ->
                store.process(connection, task) { transaction -> handler.handle(task, transaction, tenant) }
            }
        }.recover { error ->
            store.fail(task, error)
                .onFailure { updateError -> logger.error("任务失败状态写入失败，taskId={}", task.id, updateError) }
                .mapEmpty()
        }.onComplete {
            vertx.cancelTimer(heartbeatId)
        }
    }

    override fun close() {
        timerId?.let(vertx::cancelTimer)
        heartbeatTimerId?.let(vertx::cancelTimer)
        timerId = null
        heartbeatTimerId = null
    }
}
