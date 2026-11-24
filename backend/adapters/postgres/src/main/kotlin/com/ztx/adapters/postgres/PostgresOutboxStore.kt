package com.ztx.adapters.postgres

import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.UUID

data class OutboxEvent(
    val eventType: String,
    val payload: JsonObject,
    val idempotencyKey: String,
    val aggregateType: String? = null,
    val aggregateId: String? = null,
    val correlationId: String? = null,
    val requestedBy: String? = null,
    val maxAttempts: Int = 8
) {
    init {
        require(eventType.isNotBlank()) { "eventType 不能为空" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey 不能为空" }
        require(maxAttempts in 1..100) { "maxAttempts 必须在 1 到 100 之间" }
    }
}

data class ClaimedBackgroundTask(
    val id: UUID,
    val taskType: String,
    val payload: JsonObject,
    val idempotencyKey: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val leaseToken: UUID
)

/** PostgreSQL Outbox 与任务状态存储。enqueue 必须复用业务事务的 SqlConnection。 */
class PostgresOutboxStore(private val database: PgDatabase) {
    fun enqueue(connection: SqlConnection, event: OutboxEvent): Future<UUID> {
        val eventId = UUID.randomUUID()
        return connection.preparedQuery(
            """
            INSERT INTO platform.outbox_event
                (id, event_type, payload, aggregate_type, aggregate_id, idempotency_key, correlation_id)
            VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """.trimIndent()
        ).execute(Tuple.of(
            eventId.toString(), event.eventType, event.payload.encode(),
            event.aggregateType, event.aggregateId, event.idempotencyKey, event.correlationId
        )).compose { inserted ->
            if (inserted.rowCount() == 0) {
                connection.preparedQuery(
                    "SELECT id FROM platform.outbox_event WHERE idempotency_key = $1"
                ).execute(Tuple.of(event.idempotencyKey)).map { rows ->
                    require(rows.size() == 1) { "Outbox 幂等记录不存在" }
                    UUID.fromString(rows.first().getString("id"))
                }
            } else {
                connection.preparedQuery(
                    """
                    INSERT INTO platform.background_task
                        (id, outbox_event_id, task_type, idempotency_key, max_attempts, requested_by)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    """.trimIndent()
                ).execute(Tuple.of(
                    eventId.toString(), eventId.toString(), event.eventType,
                    event.idempotencyKey, event.maxAttempts, event.requestedBy
                )).map(eventId)
            }
        }
    }

    fun claim(
        workerId: String,
        batchSize: Int = 10,
        leaseSeconds: Int = 60,
        taskTypes: Set<String>? = null
    ): Future<List<ClaimedBackgroundTask>> {
        require(workerId.isNotBlank()) { "workerId 不能为空" }
        require(batchSize in 1..100) { "batchSize 必须在 1 到 100 之间" }
        require(leaseSeconds in 1..3600) { "leaseSeconds 必须在 1 到 3600 之间" }
        if (taskTypes != null && taskTypes.isEmpty()) return Future.succeededFuture(emptyList())

        val types = taskTypes?.toList()?.sorted()
        val typeFilter = types?.let {
            "AND task_type IN (${it.indices.joinToString { index -> "$${index + 4}" }})"
        } ?: ""
        val parameters = mutableListOf<Any>(workerId, batchSize, leaseSeconds)
        types?.let(parameters::addAll)

        return database.withTransaction { connection ->
            connection.preparedQuery(
                """
                UPDATE platform.background_task
                SET status = 'DEAD', lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                    last_error = COALESCE(last_error, 'Worker 租约过期且已达到最大尝试次数'),
                    updated_at = clock_timestamp()
                WHERE status = 'RUNNING' AND lease_until <= clock_timestamp()
                  AND attempt_count >= max_attempts
                """.trimIndent()
            ).execute().compose {
                connection.preparedQuery(CLAIM_SQL.replace("/*TASK_TYPE_FILTER*/", typeFilter))
                    .execute(Tuple.of(*parameters.toTypedArray()))
                    .map { rows ->
                        rows.map { row ->
                            ClaimedBackgroundTask(
                                id = UUID.fromString(row.getString("id")),
                                taskType = row.getString("task_type"),
                                payload = JsonObject(row.getString("payload")),
                                idempotencyKey = row.getString("idempotency_key"),
                                attemptCount = row.getInteger("attempt_count"),
                                maxAttempts = row.getInteger("max_attempts"),
                                leaseToken = UUID.fromString(row.getString("lease_token"))
                            )
                        }.toList()
                    }
            }
        }
    }

    fun renew(task: ClaimedBackgroundTask, leaseSeconds: Int = 60): Future<Boolean> {
        require(leaseSeconds in 1..3600) { "leaseSeconds 必须在 1 到 3600 之间" }
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """
                UPDATE platform.background_task
                SET lease_until = clock_timestamp() + ($3 * INTERVAL '1 second'),
                    updated_at = clock_timestamp()
                WHERE id = $1 AND status = 'RUNNING' AND lease_token = $2
                """.trimIndent()
            ).execute(Tuple.of(task.id.toString(), task.leaseToken.toString(), leaseSeconds))
                .map { it.rowCount() == 1 }
        }
    }

    fun process(
        connection: SqlConnection,
        task: ClaimedBackgroundTask,
        handler: (SqlConnection) -> Future<Void>
    ): Future<Void> =
        connection.preparedQuery(
            """
            INSERT INTO platform.processed_task (task_type, idempotency_key)
            VALUES ($1, $2) ON CONFLICT DO NOTHING
            """.trimIndent()
        ).execute(Tuple.of(task.taskType, task.idempotencyKey)).compose { marker ->
            val handling = if (marker.rowCount() == 0) Future.succeededFuture<Void>() else handler(connection)
            handling.compose {
                connection.preparedQuery(
                    """
                    UPDATE platform.background_task
                    SET status = 'SUCCEEDED', lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                        last_error = NULL, completed_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = $1 AND status = 'RUNNING' AND lease_token = $2
                    """.trimIndent()
                ).execute(Tuple.of(task.id.toString(), task.leaseToken.toString())).compose { updated ->
                    if (updated.rowCount() == 1) Future.succeededFuture()
                    else Future.failedFuture("任务租约已失效，拒绝提交处理结果")
                }
            }
        }

    fun fail(
        task: ClaimedBackgroundTask,
        error: Throwable,
        baseDelaySeconds: Int = 2,
        maxDelaySeconds: Int = 900
    ): Future<Void> {
        require(baseDelaySeconds in 1..300) { "baseDelaySeconds 必须在 1 到 300 之间" }
        require(maxDelaySeconds in baseDelaySeconds..86400) { "maxDelaySeconds 配置无效" }
        val summary = (error.message ?: error::class.simpleName ?: "未知错误")
            .replace(Regex("[\\r\\n\\t]+"), " ").take(2000)
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """
                UPDATE platform.background_task
                SET status = CASE WHEN attempt_count >= max_attempts THEN 'DEAD' ELSE 'RETRY_WAIT' END,
                    available_at = clock_timestamp() +
                        (LEAST($3::double precision * POWER(2, GREATEST(attempt_count - 1, 0)),
                               $4::double precision) * INTERVAL '1 second'),
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                    last_error = $5, updated_at = clock_timestamp()
                WHERE id = $1 AND status = 'RUNNING' AND lease_token = $2
                """.trimIndent()
            ).execute(Tuple.of(
                task.id.toString(), task.leaseToken.toString(),
                baseDelaySeconds, maxDelaySeconds, summary
            )).compose { updated ->
                if (updated.rowCount() == 1) Future.succeededFuture()
                else Future.failedFuture("任务租约已失效，拒绝更新失败状态")
            }
        }
    }

    fun replay(taskId: UUID, operator: String): Future<Boolean> {
        require(operator.isNotBlank()) { "operator 不能为空" }
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """
                UPDATE platform.background_task
                SET status = 'PENDING', attempt_count = 0, available_at = clock_timestamp(),
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                    last_error = NULL, completed_at = NULL, replayed_by = $2,
                    replayed_at = clock_timestamp(), replay_count = replay_count + 1,
                    updated_at = clock_timestamp()
                WHERE id = $1 AND status = 'DEAD'
                """.trimIndent()
            ).execute(Tuple.of(taskId.toString(), operator)).map { it.rowCount() == 1 }
        }
    }

    private companion object {
        const val CLAIM_SQL = """
            WITH candidates AS (
                SELECT id
                FROM platform.background_task
                WHERE (
                    (status IN ('PENDING', 'RETRY_WAIT') AND available_at <= clock_timestamp())
                    OR
                    (status = 'RUNNING' AND lease_until <= clock_timestamp()
                     AND attempt_count < max_attempts)
                )
                /*TASK_TYPE_FILTER*/
                ORDER BY available_at, created_at
                LIMIT $2
                FOR UPDATE SKIP LOCKED
            )
            UPDATE platform.background_task AS task
            SET status = 'RUNNING', attempt_count = task.attempt_count + 1,
                lease_owner = $1, lease_token = gen_random_uuid(),
                lease_until = clock_timestamp() + ($3 * INTERVAL '1 second'),
                updated_at = clock_timestamp()
            FROM candidates, platform.outbox_event AS event
            WHERE task.id = candidates.id AND event.id = task.outbox_event_id
            RETURNING task.id::text AS id, task.task_type, event.payload::text AS payload,
                      task.idempotency_key, task.attempt_count, task.max_attempts,
                      task.lease_token::text AS lease_token
        """
    }
}
