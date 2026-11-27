package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.Future
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.util.UUID

data class TenantPrincipal(
    val tenantId: UUID,
    val tenantCode: String,
    val databaseName: String,
    val userId: UUID,
    val username: String,
    val role: String,
    val database: PgDatabase,
    val isGroupAdmin: Boolean = false
)

data class TenantLogin(val sessionToken: String, val principal: TenantPrincipal)

private data class TenantLoginOutcome(val login: TenantLogin?)
private data class TenantPrincipalLookup(val principal: TenantPrincipal?)
private data class TenantPasswordChangeOutcome(val changed: Boolean)

/** 租户身份只在租户自己的数据库内验证和保存；控制面只负责可信租户路由。 */
class TenantAuthService(private val databases: TenantDatabaseRegistry) {
    fun login(
        tenantCode: String, username: String, password: String, ip: String?, userAgent: String?
    ): Future<TenantLogin> {
        val normalizedUsername = username.trim()
        if (!normalizedUsername.matches(Regex("[A-Za-z0-9._-]{3,80}"))) {
            return Future.failedFuture("租户编码、账号或密码不正确")
        }
        return databases.resolve(tenantCode).compose { tenantDatabase ->
            val attemptKey = AuthSupport.sha256(
                "${tenantDatabase.tenant.id}|${ip ?: "unknown"}|${normalizedUsername.lowercase()}"
            )
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    "SELECT locked_until FROM iam.tenant_login_attempt WHERE key_hash = $1"
                ).execute(Tuple.of(attemptKey)).compose { attempts ->
                    val lockedUntil = attempts.firstOrNull()?.getOffsetDateTime("locked_until")
                    if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
                        AuthSupport.audit(connection, AuthScope.TENANT, null, "LOGIN", "DENIED", ip).map { TenantLoginOutcome(null) }
                    } else {
                        connection.preparedQuery(
                            """SELECT id::text AS id, username, password_hash, is_group_admin
                               FROM iam.tenant_admin
                               WHERE lower(username) = lower($1) AND disabled_at IS NULL"""
                        ).execute(Tuple.of(normalizedUsername)).compose { rows ->
                            val row = rows.firstOrNull()
                            val valid = row != null && AuthSupport.verifyPassword(password, row.getString("password_hash"))
                            if (!valid) {
                                AuthSupport.recordFailure(connection, AuthScope.TENANT, attemptKey).compose {
                                    AuthSupport.audit(connection, AuthScope.TENANT, row?.getString("id")?.let(UUID::fromString), "LOGIN", "FAILURE", ip)
                                }.map { TenantLoginOutcome(null) }
                            } else {
                                val adminRow = requireNotNull(row)
                                val userId = UUID.fromString(adminRow.getString("id"))
                                val token = AuthSupport.randomToken()
                                connection.preparedQuery(
                                    """INSERT INTO iam.tenant_admin_session
                                       (id, admin_id, token_hash, expires_at, user_agent, ip_address)
                                       VALUES ($1, $2, $3, clock_timestamp() + INTERVAL '8 hours', $4, $5::inet)"""
                                ).execute(Tuple.of(UUID.randomUUID(), userId, AuthSupport.sha256(token),
                                    userAgent?.take(512), AuthSupport.postgresInet(ip, invalidAsNull = true))).compose {
                                    connection.preparedQuery(
                                        "UPDATE iam.tenant_admin SET last_login_at = clock_timestamp() WHERE id = $1"
                                    ).execute(Tuple.of(userId))
                                }.compose {
                                    connection.preparedQuery("DELETE FROM iam.tenant_login_attempt WHERE key_hash = $1")
                                        .execute(Tuple.of(attemptKey))
                                }.compose {
                                    AuthSupport.audit(connection, AuthScope.TENANT, userId, "LOGIN", "SUCCESS", ip)
                                }.map {
                                    TenantLoginOutcome(TenantLogin(token, TenantPrincipal(
                                        tenantDatabase.tenant.id, tenantDatabase.tenant.code,
                                        tenantDatabase.tenant.databaseName, userId, adminRow.getString("username"),
                                        "TENANT_ADMIN", tenantDatabase.database, adminRow.getBoolean("is_group_admin")
                                    )))
                                }
                            }
                        }
                    }
                }
            }.compose { result ->
                if (result.login == null) Future.failedFuture("租户编码、账号或密码不正确")
                else Future.succeededFuture(result.login)
            }
        }.recover { Future.failedFuture("租户编码、账号或密码不正确") }
    }

    fun authenticate(tenantCode: String, rawToken: String): Future<TenantPrincipal?> =
        databases.resolve(tenantCode).compose { tenantDatabase ->
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    """SELECT admin.id::text AS id, admin.username, admin.is_group_admin
                       FROM iam.tenant_admin_session session
                       JOIN iam.tenant_admin admin ON admin.id = session.admin_id
                       WHERE session.token_hash = $1 AND session.revoked_at IS NULL
                         AND session.expires_at > clock_timestamp() AND admin.disabled_at IS NULL"""
                ).execute(Tuple.of(AuthSupport.sha256(rawToken))).map { rows ->
                    TenantPrincipalLookup(rows.firstOrNull()?.let { row ->
                        TenantPrincipal(
                            tenantDatabase.tenant.id, tenantDatabase.tenant.code,
                            tenantDatabase.tenant.databaseName, UUID.fromString(row.getString("id")),
                            row.getString("username"), "TENANT_ADMIN", tenantDatabase.database,
                            row.getBoolean("is_group_admin")
                        )
                    })
                }
            }
        }.map { it.principal }.recover { Future.succeededFuture(null) }

    fun logout(tenantCode: String, rawToken: String, ip: String? = null): Future<Void> =
        databases.resolve(tenantCode).compose { tenantDatabase ->
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    """UPDATE iam.tenant_admin_session SET revoked_at = clock_timestamp()
                       WHERE token_hash = $1 AND revoked_at IS NULL
                       RETURNING admin_id::text AS admin_id"""
                ).execute(Tuple.of(AuthSupport.sha256(rawToken))).compose { rows ->
                    val adminId = rows.firstOrNull()?.getString("admin_id")?.let(UUID::fromString)
                    if (adminId == null) Future.succeededFuture<Void>()
                    else AuthSupport.audit(connection, AuthScope.TENANT, adminId, "LOGOUT", "SUCCESS", ip)
                }
            }
        }.recover { Future.succeededFuture<Void>() }

    fun changePassword(
        principal: TenantPrincipal, currentToken: String, oldPassword: String, newPassword: String, ip: String?
    ): Future<Void> {
        if (newPassword.length < 8 || !newPassword.any(Char::isLetter) || !newPassword.any(Char::isDigit)) {
            return Future.failedFuture("密码至少 8 位，且必须同时包含字母和数字")
        }
        val newHash = AuthSupport.hashPassword(newPassword)
        return principal.database.withTransaction { connection ->
            connection.preparedQuery("SELECT password_hash FROM iam.tenant_admin WHERE id = $1 AND disabled_at IS NULL")
                .execute(Tuple.of(principal.userId)).compose { rows ->
                    val currentHash = rows.firstOrNull()?.getString("password_hash")
                    if (currentHash == null || !AuthSupport.verifyPassword(oldPassword, currentHash)) {
                        AuthSupport.audit(connection, AuthScope.TENANT, principal.userId, "PASSWORD_CHANGE", "FAILURE", ip)
                            .map { TenantPasswordChangeOutcome(false) }
                    } else if (AuthSupport.verifyPassword(newPassword, currentHash)) {
                        Future.failedFuture<TenantPasswordChangeOutcome>("新密码不能与当前密码相同")
                    } else {
                        connection.preparedQuery(
                            "UPDATE iam.tenant_admin SET password_hash = $2, updated_at = clock_timestamp() WHERE id = $1"
                        ).execute(Tuple.of(principal.userId, newHash)).compose {
                            connection.preparedQuery(
                                """UPDATE iam.tenant_admin_session SET revoked_at = clock_timestamp()
                                   WHERE admin_id = $1 AND token_hash <> $2 AND revoked_at IS NULL"""
                            ).execute(Tuple.of(principal.userId, AuthSupport.sha256(currentToken)))
                        }.compose {
                            AuthSupport.audit(connection, AuthScope.TENANT, principal.userId, "PASSWORD_CHANGED", "SUCCESS", ip)
                        }.map { TenantPasswordChangeOutcome(true) }
                    }
                }
        }.compose { result ->
            if (result.changed) Future.succeededFuture<Void>()
            else Future.failedFuture<Void>("当前密码不正确")
        }
    }

}
