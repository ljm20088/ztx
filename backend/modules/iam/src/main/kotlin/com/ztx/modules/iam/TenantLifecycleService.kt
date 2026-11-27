package com.ztx.modules.iam

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

data class TenantRecord(
    val id: UUID, val code: String, val name: String, val status: String,
    val databaseName: String, val lastError: String?
)
data class TenantProvisioningResult(val tenant: TenantRecord, val setupToken: String? = null)

/** 平台控制面租户生命周期；租户数据库名称由服务端生成，不接受客户端提供连接信息。 */
class TenantLifecycleService(
    private val vertx: Vertx,
    private val controlDatabase: PgDatabase,
    private val databaseConfig: DatabaseConfig,
    private val tenantMigrationLocations: String = "filesystem:database/tenant-migrations"
) {
    fun list(): Future<List<TenantRecord>> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id::text, code, name, status, database_name, last_error
                   FROM platform.tenant ORDER BY created_at DESC"""
            ).execute().map { rows -> rows.map(::toTenantRecord) }
        }

    fun create(code: String, name: String, actor: PlatformAdmin): Future<TenantProvisioningResult> {
        val normalizedCode = code.trim().lowercase()
        val normalizedName = name.trim()
        if (!normalizedCode.matches(Regex("[a-z][a-z0-9_-]{1,39}"))) {
            return Future.failedFuture("租户编码须为 2–40 位小写字母、数字、下划线或连字符，且以字母开头")
        }
        if (normalizedName.length !in 2..160) return Future.failedFuture("租户名称须为 2–160 个字符")
        val id = UUID.randomUUID()
        val dbName = databaseName(normalizedCode, id)
        return controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """INSERT INTO platform.tenant(id, code, name, status, database_name)
                   VALUES ($1, $2, $3, 'PENDING', $4) ON CONFLICT (code) DO NOTHING"""
            ).execute(Tuple.of(id, normalizedCode, normalizedName, dbName)).compose {
                connection.preparedQuery(
                    """SELECT id::text, code, name, status, database_name, last_error
                       FROM platform.tenant WHERE code = $1"""
                ).execute(Tuple.of(normalizedCode)).map { rows ->
                    require(rows.size() == 1) { "租户创建失败" }
                    toTenantRecord(rows.first())
                }
            }
        }.compose { tenant ->
            when (tenant.status) {
                "ACTIVE", "INACTIVE" -> Future.succeededFuture(TenantProvisioningResult(tenant))
                else -> provision(tenant, actor)
            }
        }
    }

    fun retry(tenantId: UUID, actor: PlatformAdmin): Future<TenantProvisioningResult> =
        find(tenantId).compose { tenant ->
            require(tenant.status == "FAILED" || tenant.status == "PROVISIONING") {
                "只有开通失败或租约过期的租户可以重试"
            }
            provision(tenant, actor)
        }

    fun enable(tenantId: UUID, actor: PlatformAdmin): Future<TenantRecord> =
        changeStatus(tenantId, "INACTIVE", "ACTIVE", actor)

    fun disable(tenantId: UUID, actor: PlatformAdmin): Future<TenantRecord> =
        changeStatus(tenantId, "ACTIVE", "INACTIVE", actor)

    /** 从控制面解析启用租户的可信注册信息；数据库名称只从此处读取。 */
    fun activeTenant(code: String): Future<TenantRecord> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id::text, code, name, status, database_name, last_error
                   FROM platform.tenant WHERE code = $1 AND status = 'ACTIVE'"""
            ).execute(Tuple.of(code.trim().lowercase())).map { rows ->
                require(rows.size() == 1) { "租户不存在或已停用" }
                toTenantRecord(rows.first())
            }
        }

    fun activeTenant(tenantId: UUID): Future<TenantRecord> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id::text, code, name, status, database_name, last_error
                   FROM platform.tenant WHERE id = $1 AND status = 'ACTIVE'"""
            ).execute(Tuple.of(tenantId)).map { rows ->
                require(rows.size() == 1) { "租户不存在或已停用" }
                toTenantRecord(rows.first())
            }
        }

    /** 业务请求必须通过此检查取得路由；不得信任浏览器提交的数据库名称。 */
    fun activeDatabaseName(code: String): Future<String> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                "SELECT database_name FROM platform.tenant WHERE code = $1 AND status = 'ACTIVE'"
            ).execute(Tuple.of(code.trim().lowercase())).map { rows ->
                require(rows.size() == 1) { "租户不存在或已停用" }
                rows.first().getString("database_name")
            }
        }


    /** 租户管理员凭租户开通时签发的一次性凭据设置首个账号。 */
    fun initializeTenantAdmin(code: String, token: String, username: String, password: String): Future<Void> {
        val normalizedUsername = username.trim()
        if (!normalizedUsername.matches(Regex("[A-Za-z0-9._-]{3,80}"))) {
            return Future.failedFuture("管理员账号格式不正确")
        }
        if (password.length < 8 || !password.any(Char::isLetter) || !password.any(Char::isDigit)) {
            return Future.failedFuture("密码至少 8 位，且必须同时包含字母和数字")
        }
        return activeDatabaseName(code).compose { dbName ->
            val blockingSupplier: () -> Unit = {
                val tenantConfig = DatabaseConfig(
                    host = databaseConfig.host, port = databaseConfig.port, database = dbName,
                    user = databaseConfig.user, password = databaseConfig.password,
                    poolSize = databaseConfig.poolSize, migrationLocations = tenantMigrationLocations
                )
                DriverManager.getConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
                    connection.autoCommit = false
                    try {
                        val tokenHash = AuthSupport.sha256(token)
                        val tokenId = connection.prepareStatement(
                            """SELECT id FROM iam.tenant_admin_setup_token
                               WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > clock_timestamp()
                               FOR UPDATE"""
                        ).use { statement ->
                            statement.setString(1, tokenHash)
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
                        } ?: throw IllegalArgumentException("租户管理员初始化凭据无效或已过期")
                        val hasAdmin = connection.createStatement().executeQuery(
                            "SELECT EXISTS (SELECT 1 FROM iam.tenant_admin)"
                        ).use { rows -> rows.next() && rows.getBoolean(1) }
                        require(!hasAdmin) { "租户管理员已初始化" }
                        val encoded = AuthSupport.hashPassword(password)
                        connection.prepareStatement(
                            "INSERT INTO iam.tenant_admin(id, username, password_hash, is_group_admin) VALUES (?, ?, ?, TRUE)"
                        ).use { statement ->
                            statement.setObject(1, UUID.randomUUID())
                            statement.setString(2, normalizedUsername)
                            statement.setString(3, encoded)
                            statement.executeUpdate()
                        }
                        connection.prepareStatement(
                            "UPDATE iam.tenant_admin_setup_token SET consumed_at = clock_timestamp() WHERE id = ?"
                        ).use { statement ->
                            statement.setObject(1, tokenId)
                            check(statement.executeUpdate() == 1) { "租户管理员初始化凭据已被使用" }
                        }
                        connection.commit()
                    } catch (error: Throwable) {
                        connection.rollback()
                        throw error
                    }
                }
                Unit
            }
            val blocking: Future<Unit> = vertx.executeBlocking(blockingSupplier, false)
            blocking.mapEmpty()
        }
    }

    private fun provision(tenant: TenantRecord, actor: PlatformAdmin): Future<TenantProvisioningResult> =
        claim(tenant.id).compose { claimed ->
            if (!claimed) return@compose find(tenant.id).map { TenantProvisioningResult(it) }
            val taskId = UUID.randomUUID()
            createTask(taskId, tenant.id).compose {
                val provisionSupplier: () -> String = { provisionDatabase(tenant) }
                val provisionFuture: Future<String> = vertx.executeBlocking(provisionSupplier, false)
                provisionFuture.compose { token ->
                    finishTask(taskId, tenant.id, true, null, actor, "TENANT_PROVISIONED")
                        .map { TenantProvisioningResult(it, token) }
                }.recover { error ->
                    val message = (error.message ?: error.javaClass.simpleName).take(2000)
                    finishTask(taskId, tenant.id, false, message, actor, "TENANT_PROVISION_FAILED")
                        .compose { Future.failedFuture("租户开通失败：$message") }
                }
            }
        }

    private fun provisionDatabase(tenant: TenantRecord): String {
        createDatabaseIfMissing(tenant.databaseName)
        val tenantConfig = DatabaseConfig(
            host = databaseConfig.host, port = databaseConfig.port, database = tenant.databaseName,
            user = databaseConfig.user, password = databaseConfig.password,
            poolSize = databaseConfig.poolSize, migrationLocations = tenantMigrationLocations
        )
        FlywayMigrator(tenantConfig).migrate()
        val setupToken = AuthSupport.randomToken()
        DriverManager.getConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
            connection.prepareStatement(
                """INSERT INTO platform.tenant_info(id, code, name) VALUES (?, ?, ?)
                   ON CONFLICT (id) DO NOTHING"""
            ).use { statement ->
                statement.setObject(1, tenant.id)
                statement.setString(2, tenant.code)
                statement.setString(3, tenant.name)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """DELETE FROM iam.tenant_admin_setup_token WHERE consumed_at IS NULL"""
            ).use { it.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO iam.tenant_admin_setup_token(id, token_hash, expires_at)
                   VALUES (?, ?, clock_timestamp() + INTERVAL '24 hours')"""
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, AuthSupport.sha256(setupToken))
                statement.executeUpdate()
            }
        }
        return setupToken
    }

    private fun createDatabaseIfMissing(name: String) {
        require(name.matches(Regex("ztx_t_[a-z0-9_]{1,48}"))) { "生成的数据库名称不合法" }
        DriverManager.getConnection(databaseConfig.jdbcUrl, databaseConfig.user, databaseConfig.password).use { connection ->
            connection.createStatement().use { statement ->
                try { statement.execute("CREATE DATABASE \"$name\"") }
                catch (error: SQLException) { if (error.sqlState != "42P04") throw error }
            }
        }
    }

    private fun claim(id: UUID): Future<Boolean> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """UPDATE platform.tenant SET status = 'PROVISIONING', last_error = NULL,
                   provisioning_lease_until = clock_timestamp() + INTERVAL '15 minutes',
                   updated_at = clock_timestamp()
                   WHERE id = $1 AND (status = 'PENDING' OR status = 'FAILED' OR
                     (status = 'PROVISIONING' AND provisioning_lease_until < clock_timestamp()))
                   RETURNING id"""
            ).execute(Tuple.of(id)).map { it.size() == 1 }
        }

    private fun createTask(taskId: UUID, tenantId: UUID): Future<Int> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """INSERT INTO platform.tenant_provisioning_task(id, tenant_id, status, attempt)
                   SELECT $1, $2, 'RUNNING',
                     COALESCE((SELECT MAX(attempt) + 1 FROM platform.tenant_provisioning_task WHERE tenant_id = $2), 1)
                   RETURNING attempt"""
            ).execute(Tuple.of(taskId, tenantId)).map { it.first().getInteger("attempt") }
        }

    private fun finishTask(
        taskId: UUID, tenantId: UUID, success: Boolean, error: String?,
        actor: PlatformAdmin, action: String
    ): Future<TenantRecord> =
        controlDatabase.withTransaction { connection ->
            val status = if (success) "ACTIVE" else "FAILED"
            val taskStatus = if (success) "SUCCEEDED" else "FAILED"
            connection.preparedQuery(
                """UPDATE platform.tenant_provisioning_task SET status = $1,
                   error_message = $2, completed_at = clock_timestamp() WHERE id = $3"""
            ).execute(Tuple.of(taskStatus, error, taskId)).compose {
                connection.preparedQuery(
                    """UPDATE platform.tenant SET status = $1, last_error = $2,
                       provisioning_lease_until = NULL,
                       provisioned_at = CASE WHEN $1::varchar = 'ACTIVE' THEN clock_timestamp() ELSE provisioned_at END,
                       updated_at = clock_timestamp() WHERE id = $3"""
                ).execute(Tuple.of(status, error, tenantId))
            }.compose {
                connection.preparedQuery(
                    """INSERT INTO audit.tenant_event(id, tenant_id, actor_admin_id, action, details)
                       VALUES ($1, $2, $3, $4, jsonb_build_object('error', $5::text))"""
                ).execute(Tuple.of(UUID.randomUUID(), tenantId, actor.id, action, error))
            }.compose {
                connection.preparedQuery(
                    """SELECT id::text, code, name, status, database_name, last_error
                       FROM platform.tenant WHERE id = $1"""
                ).execute(Tuple.of(tenantId)).map { toTenantRecord(it.first()) }
            }
        }

    private fun changeStatus(id: UUID, expected: String, target: String, actor: PlatformAdmin): Future<TenantRecord> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """UPDATE platform.tenant SET status = $1, updated_at = clock_timestamp()
                   WHERE id = $2 AND status = $3
                   RETURNING id::text, code, name, status, database_name, last_error"""
            ).execute(Tuple.of(target, id, expected)).compose { rows ->
                if (rows.size() != 1) return@compose Future.failedFuture("租户状态已变化或当前状态不允许此操作")
                val tenant = toTenantRecord(rows.first())
                connection.preparedQuery(
                    "INSERT INTO audit.tenant_event(id, tenant_id, actor_admin_id, action) VALUES ($1, $2, $3, $4)"
                ).execute(Tuple.of(UUID.randomUUID(), id, actor.id, "TENANT_$target")).map { tenant }
            }
        }

    private fun find(id: UUID): Future<TenantRecord> =
        controlDatabase.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id::text, code, name, status, database_name, last_error
                   FROM platform.tenant WHERE id = $1"""
            ).execute(Tuple.of(id)).map { rows ->
                require(rows.size() == 1) { "租户不存在" }
                toTenantRecord(rows.first())
            }
        }

    private fun toTenantRecord(row: Row) = TenantRecord(
        UUID.fromString(row.getString("id")), row.getString("code"), row.getString("name"),
        row.getString("status"), row.getString("database_name"), row.getString("last_error")
    )

    private fun databaseName(code: String, id: UUID): String {
        val slug = code.replace(Regex("[^a-z0-9]"), "_").take(32).trim('_').ifBlank { "tenant" }
        return "ztx_t_${slug}_${id.toString().replace("-", "").take(8)}"
    }

}
