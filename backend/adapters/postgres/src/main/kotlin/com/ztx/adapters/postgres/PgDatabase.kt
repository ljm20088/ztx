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

    fun <T : Any> withTransaction(work: (SqlConnection) -> Future<T>): Future<T> =
        pool.withTransaction<T?> { connection ->
            work(connection).map<T?> { result -> result }
        }.map { result -> requireNotNull(result) { "数据库事务不能返回 null" } }

    override fun close() {
        pool.close()
    }
}
