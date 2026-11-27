package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PlatformAdmin(val id: UUID, val username: String, val mfaEnabled: Boolean)
data class PlatformLogin(val sessionToken: String, val admin: PlatformAdmin)
data class MfaEnrollment(val secret: String, val otpauthUri: String)
data class PlatformPasswordResetResult(val success: Boolean, val reason: String)
private data class LoginOutcome(val login: PlatformLogin?)
private data class AdminLookup(val admin: PlatformAdmin?)

/** 平台控制面认证；身份和会话只保存在控制面数据库。 */
class PlatformAuthService(
    private val database: PgDatabase,
    private val mfaEncryptionKey: String
) {
    fun initializationStatus(): Future<Boolean> =
        database.withTransaction { connection ->
            connection.preparedQuery("SELECT EXISTS (SELECT 1 FROM iam.platform_admin) AS initialized")
                .execute().map { rows -> rows.first().getBoolean("initialized") }
        }

    fun mfaEnabled(): Future<Boolean> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                "SELECT COALESCE((SELECT mfa_enabled FROM iam.platform_admin LIMIT 1), FALSE) AS enabled"
            ).execute().map { rows -> rows.first().getBoolean("enabled") }
        }

    fun recentAuditEvents(limit: Int = 100): Future<JsonArray> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT event.id::text AS id, event.event_type, event.outcome,
                          host(event.source_ip) AS source_ip, event.occurred_at,
                          admin.username AS admin_username
                   FROM audit.platform_security_event event
                   LEFT JOIN iam.platform_admin admin ON admin.id = event.admin_id
                   ORDER BY event.occurred_at DESC
                   LIMIT $1"""
            ).execute(Tuple.of(limit.coerceIn(1, 200))).map { rows ->
                JsonArray(rows.map { row ->
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("eventType", row.getString("event_type"))
                        .put("outcome", row.getString("outcome"))
                        .put("sourceIp", row.getString("source_ip"))
                        .put("adminUsername", row.getString("admin_username"))
                        .put("occurredAt", row.getOffsetDateTime("occurred_at").toString())
                })
            }
        }

    fun workerInstances(limit: Int = 20): Future<JsonArray> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT worker_id, started_at, last_seen_at,
                          CASE WHEN last_seen_at >= clock_timestamp() - INTERVAL '45 seconds'
                               THEN 'UP' ELSE 'DOWN' END AS status
                   FROM platform.worker_instance
                   WHERE last_seen_at >= clock_timestamp() - INTERVAL '24 hours'
                   ORDER BY last_seen_at DESC
                   LIMIT $1"""
            ).execute(Tuple.of(limit.coerceIn(1, 100))).map { rows ->
                JsonArray(rows.map { row ->
                    JsonObject()
                        .put("workerId", row.getString("worker_id"))
                        .put("startedAt", row.getOffsetDateTime("started_at").toString())
                        .put("lastSeenAt", row.getOffsetDateTime("last_seen_at").toString())
                        .put("status", row.getString("status"))
                })
            }
        }

    /**
     * 首次初始化只允许本机/SSH 隧道请求进入路由层；数据库事务锁确保并发请求只能创建一个管理员。
     * 初始化成功后管理员记录永久存在，服务重启不会重新开放初始化。
     */
    fun initialize(
        username: String, password: String, ip: String?, userAgent: String? = null
    ): Future<PlatformLogin> {
        require(username.matches(Regex("[A-Za-z0-9._-]{3,80}"))) { "管理员账号格式不正确" }
        validatePassword(password)
        val passwordHash = AuthSupport.hashPassword(password, PBKDF2_ITERATIONS)
        return database.withTransaction { connection ->
            connection.preparedQuery("SELECT pg_advisory_xact_lock(724193810233)").execute().compose {
                connection.preparedQuery("SELECT count(*) AS total FROM iam.platform_admin").execute().compose { admins ->
                    if (admins.first().getLong("total") > 0L) {
                        Future.failedFuture("平台管理员已初始化")
                    } else {
                        val id = UUID.randomUUID()
                        val rawSession = AuthSupport.randomToken(32)
                        connection.preparedQuery(
                            "INSERT INTO iam.platform_admin (id, username, password_hash) VALUES ($1, $2, $3)"
                        ).execute(Tuple.of(id, username, passwordHash)).compose {
                            connection.preparedQuery(
                                """INSERT INTO iam.platform_admin_session
                                   (id, admin_id, token_hash, expires_at, user_agent, ip_address)
                                   VALUES ($1, $2, $3, clock_timestamp() + INTERVAL '8 hours', $4, $5::inet)"""
                            ).execute(Tuple.of(UUID.randomUUID(), id, AuthSupport.sha256(rawSession),
                                userAgent?.take(512), AuthSupport.postgresInet(ip))).compose {
                                AuthSupport.audit(connection, AuthScope.PLATFORM, id, "ADMIN_INITIALIZED", "SUCCESS", ip)
                                    .map(PlatformLogin(rawSession, PlatformAdmin(id, username, false)))
                            }
                        }
                    }
                }
            }
        }
    }

    fun login(username: String, password: String, totp: String?, ip: String?, userAgent: String?): Future<PlatformLogin> {
        val attemptKey = AuthSupport.sha256((ip ?: "unknown") + "|" + username.trim().lowercase())
        return database.withTransaction { connection ->
            connection.preparedQuery("SELECT locked_until FROM iam.platform_login_attempt WHERE key_hash = $1")
                .execute(Tuple.of(attemptKey)).compose { attempts ->
                    val lockedUntil = attempts.firstOrNull()?.getOffsetDateTime("locked_until")
                    if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
                        AuthSupport.audit(connection, AuthScope.PLATFORM, null, "LOGIN", "DENIED", ip).map(LoginOutcome(null))
                    } else {
                        connection.preparedQuery(
                            """SELECT id::text AS id, username, password_hash, mfa_secret_ciphertext, mfa_enabled
                               FROM iam.platform_admin
                               WHERE lower(username) = lower($1) AND disabled_at IS NULL"""
                        ).execute(Tuple.of(username.trim())).compose { rows ->
                            val row = rows.firstOrNull()
                            val validPassword = row != null && AuthSupport.verifyPassword(password, row.getString("password_hash"))
                            val secret = if (row != null && row.getBoolean("mfa_enabled")) decrypt(row.getString("mfa_secret_ciphertext")) else null
                            val validMfa = secret == null || (totp != null && verifyTotp(secret, totp))
                            if (!validPassword || !validMfa) {
                                AuthSupport.recordFailure(connection, AuthScope.PLATFORM, attemptKey).compose {
                                    AuthSupport.audit(connection, AuthScope.PLATFORM, row?.getString("id")?.let(UUID::fromString),
                                        if (validPassword) "MFA_LOGIN" else "LOGIN", "FAILURE", ip)
                                }.map(LoginOutcome(null))
                            } else {
                                val authenticatedAdmin = requireNotNull(row)
                                val adminId = UUID.fromString(authenticatedAdmin.getString("id"))
                                val rawSession = AuthSupport.randomToken(32)
                                connection.preparedQuery("DELETE FROM iam.platform_login_attempt WHERE key_hash = $1")
                                    .execute(Tuple.of(attemptKey)).compose {
                                        connection.preparedQuery(
                                            """INSERT INTO iam.platform_admin_session
                                               (id, admin_id, token_hash, expires_at, user_agent, ip_address)
                                               VALUES ($1, $2, $3, clock_timestamp() + INTERVAL '8 hours', $4, $5::inet)"""
                                        ).execute(Tuple.of(UUID.randomUUID(), adminId,
                                            AuthSupport.sha256(rawSession), userAgent?.take(512), AuthSupport.postgresInet(ip)))
                                    }.compose {
                                        connection.preparedQuery(
                                            "UPDATE iam.platform_admin SET last_login_at = clock_timestamp() WHERE id = $1"
                                        ).execute(Tuple.of(adminId))
                                    }.compose {
                                        AuthSupport.audit(connection, AuthScope.PLATFORM, adminId, "LOGIN", "SUCCESS", ip)
                                    }.map(LoginOutcome(PlatformLogin(rawSession, PlatformAdmin(adminId, authenticatedAdmin.getString("username"), authenticatedAdmin.getBoolean("mfa_enabled")))))
                            }
                        }
                    }
                }
        }.compose { result ->
            if (result.login == null) Future.failedFuture("账号、密码或动态验证码不正确")
            else Future.succeededFuture(result.login)
        }
    }

    fun authenticate(rawToken: String): Future<PlatformAdmin?> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT admin.id::text AS id, admin.username, admin.mfa_enabled
                   FROM iam.platform_admin_session session
                   JOIN iam.platform_admin admin ON admin.id = session.admin_id
                   WHERE session.token_hash = $1 AND session.revoked_at IS NULL
                     AND session.expires_at > clock_timestamp() AND admin.disabled_at IS NULL"""
            ).execute(Tuple.of(AuthSupport.sha256(rawToken))).map { rows ->
                AdminLookup(rows.firstOrNull()?.let {
                    PlatformAdmin(UUID.fromString(it.getString("id")), it.getString("username"), it.getBoolean("mfa_enabled"))
                })
            }
        }.map { it.admin }

    fun logout(rawToken: String, ip: String?): Future<Void> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """UPDATE iam.platform_admin_session SET revoked_at = clock_timestamp()
                   WHERE token_hash = $1 AND revoked_at IS NULL RETURNING admin_id::text AS admin_id"""
            ).execute(Tuple.of(AuthSupport.sha256(rawToken))).compose { rows ->
                AuthSupport.audit(connection, AuthScope.PLATFORM, rows.firstOrNull()?.getString("admin_id")?.let(UUID::fromString), "LOGOUT", "SUCCESS", ip)
            }
        }

    fun changePassword(
        admin: PlatformAdmin, oldPassword: String, newPassword: String, currentToken: String, ip: String?
    ): Future<Void> {
        validatePassword(newPassword)
        val newHash = AuthSupport.hashPassword(newPassword, PBKDF2_ITERATIONS)
        return database.withTransaction { connection ->
            connection.preparedQuery("SELECT password_hash FROM iam.platform_admin WHERE id = $1")
                .execute(Tuple.of(admin.id)).compose { rows ->
                    val currentHash = rows.firstOrNull()?.getString("password_hash")
                    if (currentHash == null || !AuthSupport.verifyPassword(oldPassword, currentHash)) {
                        Future.failedFuture("当前密码不正确")
                    } else if (AuthSupport.verifyPassword(newPassword, currentHash)) {
                        Future.failedFuture("新密码不能与当前密码相同")
                    } else {
                        connection.preparedQuery(
                            "UPDATE iam.platform_admin SET password_hash = $2, updated_at = clock_timestamp() WHERE id = $1"
                        ).execute(Tuple.of(admin.id, newHash)).compose {
                            connection.preparedQuery(
                                """UPDATE iam.platform_admin_session SET revoked_at = clock_timestamp()
                                   WHERE admin_id = $1 AND token_hash <> $2 AND revoked_at IS NULL"""
                            ).execute(Tuple.of(admin.id, AuthSupport.sha256(currentToken)))
                        }.compose {
                            AuthSupport.audit(connection, AuthScope.PLATFORM, admin.id, "PASSWORD_CHANGED", "SUCCESS", ip)
                        }
                    }
                }
        }
    }

    fun enrollMfa(admin: PlatformAdmin): Future<MfaEnrollment> {
        require(mfaEncryptionKey.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "启用 MFA 前请在 application.yaml 的 security.mfa-encryption-key 中配置至少 32 字节随机密钥；可用 openssl rand -base64 32 生成"
        }
        val secret = base32(AuthSupport.randomBytes(20))
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """UPDATE iam.platform_admin SET mfa_secret_ciphertext = $2, mfa_enabled = FALSE,
                   updated_at = clock_timestamp() WHERE id = $1"""
            ).execute(Tuple.of(admin.id, encrypt(secret))).compose {
                AuthSupport.audit(connection, AuthScope.PLATFORM, admin.id, "MFA_ENROLLMENT_STARTED", "SUCCESS", null)
            }.map {
                val label = "ZTX:" + admin.username
                MfaEnrollment(secret, "otpauth://totp/" + label + "?secret=" + secret +
                    "&issuer=ZTX&algorithm=SHA1&digits=6&period=30")
            }
        }
    }

    fun enableMfa(admin: PlatformAdmin, code: String, ip: String?): Future<Void> =
        database.withTransaction { connection ->
            connection.preparedQuery("SELECT mfa_secret_ciphertext FROM iam.platform_admin WHERE id = $1")
                .execute(Tuple.of(admin.id)).compose { rows ->
                    val encrypted = rows.firstOrNull()?.getString("mfa_secret_ciphertext")
                    if (encrypted.isNullOrBlank() || !verifyTotp(decrypt(encrypted), code)) {
                        AuthSupport.audit(connection, AuthScope.PLATFORM, admin.id, "MFA_ENABLED", "FAILURE", ip).map<Boolean>(false)
                    } else {
                        connection.preparedQuery(
                            "UPDATE iam.platform_admin SET mfa_enabled = TRUE, updated_at = clock_timestamp() WHERE id = $1"
                        ).execute(Tuple.of(admin.id)).compose {
                            AuthSupport.audit(connection, AuthScope.PLATFORM, admin.id, "MFA_ENABLED", "SUCCESS", ip).map<Boolean>(true)
                        }
                    }
                }
        }.compose { enabled ->
            if (enabled) Future.succeededFuture() else Future.failedFuture("动态验证码不正确")
        }

    /**
     * 服务器部署人员通过独立命令重置唯一平台管理员密码。
     * MFA 字段保持原样；同一事务中撤销全部会话并写入包含本机操作者的审计记录。
     */
    fun resetPasswordFromServerCommand(password: String, operator: String): Future<PlatformPasswordResetResult> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id::text AS id, username, password_hash
                   FROM iam.platform_admin
                   FOR UPDATE"""
            ).execute().compose { rows ->
                val admin = rows.firstOrNull()
                val adminId = if (rows.count() == 1) UUID.fromString(requireNotNull(admin).getString("id")) else null
                val details = JsonObject()
                    .put("operator", operator.take(128))
                    .put("mechanism", "server-command")
                    .put("targetAdminCount", rows.count())
                if (adminId != null) details.put("targetUsername", admin!!.getString("username"))

                val failureReason = when {
                    rows.count() != 1 -> "UNIQUE_PLATFORM_ADMIN_REQUIRED"
                    password.length < 8 || !password.any(Char::isLetter) || !password.any(Char::isDigit) ->
                        "PASSWORD_POLICY_REJECTED"
                    AuthSupport.verifyPassword(password, requireNotNull(admin).getString("password_hash")) ->
                        "PASSWORD_UNCHANGED"
                    else -> null
                }
                if (failureReason != null) {
                    details.put("reason", failureReason)
                    writePasswordResetAudit(connection, adminId, "FAILURE", details)
                        .map(PlatformPasswordResetResult(false, failureReason))
                } else {
                    val targetId = requireNotNull(adminId)
                    val passwordHash = AuthSupport.hashPassword(password, PBKDF2_ITERATIONS)
                    connection.preparedQuery(
                        """UPDATE iam.platform_admin
                           SET password_hash = $2, updated_at = clock_timestamp()
                           WHERE id = $1"""
                    ).execute(Tuple.of(targetId, passwordHash)).compose {
                        connection.preparedQuery(
                            """UPDATE iam.platform_admin_session
                               SET revoked_at = clock_timestamp()
                               WHERE admin_id = $1 AND revoked_at IS NULL"""
                        ).execute(Tuple.of(targetId))
                    }.compose { revoked ->
                        details.put("revokedSessionCount", revoked.rowCount())
                        connection.preparedQuery("DELETE FROM iam.platform_login_attempt").execute().compose {
                            writePasswordResetAudit(connection, targetId, "SUCCESS", details)
                        }
                    }.map(PlatformPasswordResetResult(true, "SUCCESS"))
                }
            }
        }

    /** 交互式命令取消或输入确认不一致时也留下失败审计，不暴露目标账号是否存在。 */
    fun recordPasswordResetCommandFailure(
        operator: String,
        reason: String
    ): Future<PlatformPasswordResetResult> =
        database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id::text AS id, username
                   FROM iam.platform_admin
                   FOR UPDATE"""
            ).execute().compose { rows ->
                val admin = rows.firstOrNull()
                val adminId = if (rows.count() == 1) UUID.fromString(requireNotNull(admin).getString("id")) else null
                val details = JsonObject()
                    .put("operator", operator.take(128))
                    .put("mechanism", "server-command")
                    .put("targetAdminCount", rows.count())
                    .put("reason", reason)
                if (adminId != null) details.put("targetUsername", admin!!.getString("username"))
                writePasswordResetAudit(connection, adminId, "FAILURE", details)
                    .map(PlatformPasswordResetResult(false, reason))
            }
        }

    private fun writePasswordResetAudit(
        connection: SqlConnection,
        adminId: UUID?,
        outcome: String,
        details: JsonObject
    ): Future<Void> = connection.preparedQuery(
        """INSERT INTO audit.platform_security_event
           (id, admin_id, event_type, outcome, source_ip, details)
           VALUES ($1, $2, 'ADMIN_PASSWORD_RESET', $3, NULL, $4::jsonb)"""
    ).execute(Tuple.of(UUID.randomUUID(), adminId, outcome, details.encode())).mapEmpty()

    private fun encrypt(value: String): String {
        val nonce = AuthSupport.randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun encryptionKey(): SecretKeySpec {
        require(mfaEncryptionKey.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "启用 MFA 前请在 application.yaml 的 security.mfa-encryption-key 中配置至少 32 字节随机密钥；可用 openssl rand -base64 32 生成"
        }
        return SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(mfaEncryptionKey.toByteArray(StandardCharsets.UTF_8)), "AES"
        )
    }

    private fun verifyTotp(secret: String, code: String): Boolean {
        if (!code.matches(Regex("[0-9]{6}"))) return false
        val counter = Instant.now().epochSecond / 30
        return (-1L..1L).any { MessageDigest.isEqual(totpCode(secret, counter + it).toByteArray(), code.toByteArray()) }
    }

    private fun totpCode(secret: String, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(base32Decode(secret), "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun validatePassword(password: String) {
        require(password.length >= 8 && password.any(Char::isLetter) && password.any(Char::isDigit)) {
            "密码至少 8 位，且必须同时包含字母和数字"
        }
    }

    private fun base32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = StringBuilder()
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                result.append(alphabet[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) result.append(alphabet[(buffer shl (5 - bits)) and 31])
        return result.toString()
    }

    private fun base32Decode(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        value.uppercase().filterNot { it == '=' || it == ' ' }.forEach { char ->
            val digit = alphabet.indexOf(char)
            require(digit >= 0) { "MFA 密钥格式无效" }
            buffer = (buffer shl 5) or digit
            bits += 5
            if (bits >= 8) {
                output += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val PBKDF2_ITERATIONS = 310_000
    }
}
