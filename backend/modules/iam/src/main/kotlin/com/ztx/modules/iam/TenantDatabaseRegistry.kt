package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.util.LinkedHashMap

data class TenantDatabase(
    val tenant: TenantRecord,
    val database: PgDatabase
)

data class TenantTaskContext(
    val tenantId: java.util.UUID,
    val tenantCode: String,
    val databaseName: String,
    val database: PgDatabase
)

/**
 * 按控制面可信租户记录创建业务数据库连接池。
 * 请求中的数据库地址永远不会进入此注册表；只允许使用控制面保存的 database_name。
 */
class TenantDatabaseRegistry(
    private val vertx: Vertx,
    private val lifecycle: TenantLifecycleService,
    private val controlConfig: DatabaseConfig,
    private val maxPools: Int = 64
) : AutoCloseable {
    private val pools = LinkedHashMap<java.util.UUID, TenantDatabase>(16, 0.75f, true)

    init {
        require(maxPools in 1..512) { "maxPools 必须在 1 到 512 之间" }
    }

    fun resolve(code: String): Future<TenantDatabase> =
        lifecycle.activeTenant(code).map(::poolFor)

    fun resolve(tenantId: java.util.UUID): Future<TenantDatabase> =
        lifecycle.activeTenant(tenantId).map(::poolFor)

    private fun poolFor(tenant: TenantRecord): TenantDatabase = synchronized(pools) {
        pools[tenant.id]?.let { cached ->
            require(cached.tenant.databaseName == tenant.databaseName) { "租户数据库路由发生变化，拒绝复用旧连接池" }
            return@synchronized cached
        }
        val config = DatabaseConfig(
            host = controlConfig.host,
            port = controlConfig.port,
            database = tenant.databaseName,
            user = controlConfig.user,
            password = controlConfig.password,
            poolSize = controlConfig.poolSize,
            migrationLocations = "filesystem:database/tenant-migrations"
        )
        val created = TenantDatabase(tenant, PgDatabase(vertx, config))
        pools[tenant.id] = created
        while (pools.size > maxPools) {
            val eldest = pools.entries.iterator().next()
            pools.remove(eldest.key)
            eldest.value.database.close()
        }
        created
    }

    override fun close() {
        synchronized(pools) {
            pools.values.forEach { it.database.close() }
            pools.clear()
        }
    }
}
