# PostgreSQL Outbox 与后台任务

## 设计边界

ZTX 使用 PostgreSQL 同时承担 Outbox 持久化和后台任务调度，不引入 Redis、Kafka 或 RabbitMQ。

- `platform.outbox_event`：保存与业务写入同事务提交的不可变事件载荷。
- `platform.background_task`：保存任务状态、尝试次数、下次执行时间、租约、失败摘要和人工重放记录。
- `platform.processed_task`：按任务类型和幂等键记录已完成的消费者处理，和消费者的数据库副作用、任务成功状态在同一事务提交。
- `platform.background_task_metrics`：提供各状态任务数、过期租约数和最老待处理任务年龄的 SQL 指标快照。

## 写入事件

业务代码必须将业务写入和 Outbox 写入放进同一个 `PgDatabase.withTransaction`：

```kotlin
database.withTransaction { connection ->
    connection.preparedQuery(
        "INSERT INTO app.some_record (id, name) VALUES ($1, $2)"
    ).execute(Tuple.of(recordId, name)).compose {
        outboxStore.enqueue(
            connection,
            OutboxEvent(
                eventType = "app.record.created",
                payload = JsonObject().put("recordId", recordId),
                idempotencyKey = "app.record.created:$recordId",
                aggregateType = "some_record",
                aggregateId = recordId
            )
        )
    }
}
```

重复使用相同幂等键时，系统返回已存在事件的 ID，不会创建第二个任务。

## 消费与状态流转

```text
PENDING -> RUNNING -> SUCCEEDED
                  -> RETRY_WAIT -> RUNNING
                  -> DEAD
RUNNING（租约过期） -> RUNNING（由其他 Worker 重新领取）
RUNNING（租约过期且达到最大尝试次数） -> DEAD
DEAD -> PENDING（人工重放）
```

- Worker 使用 `FOR UPDATE SKIP LOCKED` 原子领取任务，并写入 Worker 标识、随机租约令牌和租约截止时间。
- 领取时增加尝试次数。Worker 崩溃后，租约到期的任务可由其他实例重新领取；长任务通过定期续租避免被其他实例提前接管。
- 失败后按指数退避计算下次执行时间，默认基数 2 秒、上限 15 分钟；达到最大尝试次数后进入 DEAD。
- 人工重放只允许对 DEAD 任务执行，必须记录操作人和重放次数。
- 处理器使用 Worker 传入的同一个 `SqlConnection` 完成数据库副作用；处理标记和任务成功状态与副作用一起提交。
- 外部 HTTP、邮件等非事务副作用必须把任务幂等键传给下游系统；本地数据库事务无法单独保证外部系统的 exactly-once。
- Worker 只领取已注册处理器对应的任务类型；当前基座尚无具体业务消费者，空处理器集合不会误领或消耗待处理任务。

## Worker 启动

```bash
ZTX_APP_ROLE=worker ./gradlew :apps:server:run --args='--ztx.profile=dev'
```

API Server 和 Worker 是同一应用的两种运行角色。使用后台任务能力时需要运行 Worker；只提供同步 API 的开发阶段可以只启动 Server。生产环境按任务吞吐量部署一个或多个 Worker 实例。

## 运维查询

```sql
SELECT * FROM platform.background_task_metrics;
```

查看租约过期任务：

```sql
SELECT id, task_type, attempt_count, max_attempts, lease_owner, lease_until
FROM platform.background_task
WHERE status = 'RUNNING' AND lease_until <= clock_timestamp()
ORDER BY lease_until;
```

查看死信任务：

```sql
SELECT id, task_type, idempotency_key, attempt_count, last_error, created_at
FROM platform.background_task
WHERE status = 'DEAD'
ORDER BY updated_at DESC;
```

当前实现提供可查询的持久化状态指标快照；建议持续监控待处理/重试/运行/死信数量、最老待处理任务等待时长、租约过期数量、执行耗时、重试次数和失败率。Micrometer 指标导出由可观测性工作项统一接入。
