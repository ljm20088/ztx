package com.ztx.adapters.postgres

import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlConnection

class PgDatabase(vertx: Vertx, config: DatabaseConfig) : DatabaseHealth, AutoCloseable {
    private val pool: Pool = PgBuilder.pool()
        .with(PoolOptions().setMaxSize(config.poolSize))
        .connectingTo(
            PgConnectOptions()
                .setHost(config.host)
                .setPort(config.port)
                .setDatabase(config.database)
                .setUser(config.user)
                .setPassword(config.password)
        )
        .using(vertx)
        .build()

    override fun ping(): Future<Void> = pool.query("SELECT 1").execute().mapEmpty()

    /**
     * Vert.x Future<Void> 成功时的结果本来就是 null。
     * 事务包装层必须保留 Void 语义，不能把正常完成误判为事务返回 null 的错误。
     */
    fun <T> withTransaction(work: (SqlConnection) -> Future<T>): Future<T> =
        pool.withTransaction { connection -> work(connection) }

    override fun close() {
        pool.close()
    }
}
