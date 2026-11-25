package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.Future
import io.vertx.pgclient.data.Inet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class TenantPrincipal(
    val tenantId: UUID,
    val tenantCode: String,
    val databaseName: String,
    val userId: UUID,
    val username: String,
    val role: String,
    val database: PgDatabase
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
            val attemptKey = sha256(
                "${tenantDatabase.tenant.id}|${ip ?: "unknown"}|${normalizedUsername.lowercase()}"
            )
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    "SELECT locked_until FROM iam.tenant_login_attempt WHERE key_hash = $1"
                ).execute(Tuple.of(attemptKey)).compose { attempts ->
                    val lockedUntil = attempts.firstOrNull()?.getOffsetDateTime("locked_until")
                    if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
                        audit(connection, null, "LOGIN", "DENIED", ip).map { TenantLoginOutcome(null) }
                    } else {
                        connection.preparedQuery(
                            """SELECT id::text AS id, username, password_hash
                               FROM iam.tenant_admin
                               WHERE lower(username) = lower($1) AND disabled_at IS NULL"""
                        ).execute(Tuple.of(normalizedUsername)).compose { rows ->
                            val row = rows.firstOrNull()
                            val valid = row != null && verifyPassword(password, row.getString("password_hash"))
                            if (!valid) {
                                recordFailure(connection, attemptKey).compose {
                                    audit(connection, row?.getString("id")?.let(UUID::fromString), "LOGIN", "FAILURE", ip)
                                }.map<TenantLogin?>(null)
                            } else {
                                val adminRow = requireNotNull(row)
                                val userId = UUID.fromString(adminRow.getString("id"))
                                val token = randomToken()
                                connection.preparedQuery(
                                    """INSERT INTO iam.tenant_admin_session
                                       (id, admin_id, token_hash, expires_at, user_agent, ip_address)
                                       VALUES ($1, $2, $3, clock_timestamp() + INTERVAL '8 hours', $4, $5::inet)"""
                                ).execute(Tuple.of(UUID.randomUUID(), userId, sha256(token),
                                    userAgent?.take(512), postgresInet(ip))).compose {
                                    connection.preparedQuery(
                                        "UPDATE iam.tenant_admin SET last_login_at = clock_timestamp() WHERE id = $1"
                                    ).execute(Tuple.of(userId))
                                }.compose {
                                    connection.preparedQuery("DELETE FROM iam.tenant_login_attempt WHERE key_hash = $1")
                                        .execute(Tuple.of(attemptKey))
                                }.compose {
                                    audit(connection, userId, "LOGIN", "SUCCESS", ip)
                                }.map {
                                    TenantLoginOutcome(TenantLogin(token, TenantPrincipal(
                                        tenantDatabase.tenant.id, tenantDatabase.tenant.code,
                                        tenantDatabase.tenant.databaseName, userId, adminRow.getString("username"),
                                        "TENANT_ADMIN", tenantDatabase.database
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
                    """SELECT admin.id::text AS id, admin.username
                       FROM iam.tenant_admin_session session
                       JOIN iam.tenant_admin admin ON admin.id = session.admin_id
                       WHERE session.token_hash = $1 AND session.revoked_at IS NULL
                         AND session.expires_at > clock_timestamp() AND admin.disabled_at IS NULL"""
                ).execute(Tuple.of(sha256(rawToken))).map { rows ->
                    TenantPrincipalLookup(rows.firstOrNull()?.let { row ->
                        TenantPrincipal(
                            tenantDatabase.tenant.id, tenantDatabase.tenant.code,
                            tenantDatabase.tenant.databaseName, UUID.fromString(row.getString("id")),
                            row.getString("username"), "TENANT_ADMIN", tenantDatabase.database
                        )
                    })
                }
            }
        }.map { it.principal }.recover { Future.succeededFuture(null) }

    fun logout(tenantCode: String, rawToken: String): Future<Void> =
        databases.resolve(tenantCode).compose { tenantDatabase ->
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    "UPDATE iam.tenant_admin_session SET revoked_at = clock_timestamp() WHERE token_hash = $1 AND revoked_at IS NULL"
                ).execute(Tuple.of(sha256(rawToken))).mapEmpty()
            }
        }.recover { Future.succeededFuture() }

    fun changePassword(
        principal: TenantPrincipal, currentToken: String, oldPassword: String, newPassword: String, ip: String?
    ): Future<Void> {
        if (newPassword.length < 8 || !newPassword.any(Char::isLetter) || !newPassword.any(Char::isDigit)) {
            return Future.failedFuture("密码至少 8 位，且必须同时包含字母和数字")
        }
        val newHash = hashPassword(newPassword)
        return principal.database.withTransaction { connection ->
            connection.preparedQuery("SELECT password_hash FROM iam.tenant_admin WHERE id = $1 AND disabled_at IS NULL")
                .execute(Tuple.of(principal.userId)).compose { rows ->
                    if (rows.size() != 1 || !verifyPassword(oldPassword, rows.first().getString("password_hash"))) {
                        audit(connection, principal.userId, "PASSWORD_CHANGE", "FAILURE", ip)
                            .map { TenantPasswordChangeOutcome(false) }
                    } else {
                        connection.preparedQuery(
                            "UPDATE iam.tenant_admin SET password_hash = $2, updated_at = clock_timestamp() WHERE id = $1"
                        ).execute(Tuple.of(principal.userId, newHash)).compose {
                            connection.preparedQuery(
                                """UPDATE iam.tenant_admin_session SET revoked_at = clock_timestamp()
                                   WHERE admin_id = $1 AND token_hash <> $2 AND revoked_at IS NULL"""
                            ).execute(Tuple.of(principal.userId, sha256(currentToken)))
                        }.compose {
                            audit(connection, principal.userId, "PASSWORD_CHANGED", "SUCCESS", ip)
                        }.map { TenantPasswordChangeOutcome(true) }
                    }
                }
        }.compose { result ->
            if (result.changed) Future.succeededFuture()
            else Future.failedFuture("当前密码不正确")
        }
    }

    private fun recordFailure(connection: SqlConnection, key: String): Future<Void> =
        connection.preparedQuery(
            """INSERT INTO iam.tenant_login_attempt (key_hash, failed_count) VALUES ($1, 1)
               ON CONFLICT (key_hash) DO UPDATE SET
                 failed_count = CASE WHEN iam.tenant_login_attempt.window_started_at < clock_timestamp() - INTERVAL '15 minutes'
                   THEN 1 ELSE iam.tenant_login_attempt.failed_count + 1 END,
                 window_started_at = CASE WHEN iam.tenant_login_attempt.window_started_at < clock_timestamp() - INTERVAL '15 minutes'
                   THEN clock_timestamp() ELSE iam.tenant_login_attempt.window_started_at END,
                 locked_until = CASE WHEN
                   (CASE WHEN iam.tenant_login_attempt.window_started_at < clock_timestamp() - INTERVAL '15 minutes'
                     THEN 1 ELSE iam.tenant_login_attempt.failed_count + 1 END) >= 5
                   THEN clock_timestamp() + INTERVAL '15 minutes' ELSE NULL END,
                 updated_at = clock_timestamp()"""
        ).execute(Tuple.of(key)).mapEmpty()

    private fun audit(
        connection: SqlConnection, userId: UUID?, event: String, outcome: String, ip: String?
    ): Future<Void> =
        connection.preparedQuery(
            """INSERT INTO audit.tenant_security_event
               (id, tenant_admin_id, event_type, outcome, source_ip)
               VALUES ($1, $2, $3, $4, $5::inet)"""
        ).execute(Tuple.of(UUID.randomUUID(), userId, event, outcome, postgresInet(ip))).mapEmpty()

    private fun verifyPassword(password: String, encoded: String): Boolean = runCatching {
        val parts = encoded.split('$')
        require(parts.size == 4 && parts[0] == "pbkdf2")
        val iterations = parts[1].toInt()
        require(iterations in 100_000..1_000_000)
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        MessageDigest.isEqual(actual, expected)
    }.getOrDefault(false)

    private fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return listOf("pbkdf2", "310000", Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hash)).joinToString("$")
    }

    private fun postgresInet(ip: String?): Inet? = ip?.let {
        runCatching { Inet().setAddress(InetAddress.getByName(it)) }.getOrNull()
    }

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object { val random = SecureRandom() }
}
