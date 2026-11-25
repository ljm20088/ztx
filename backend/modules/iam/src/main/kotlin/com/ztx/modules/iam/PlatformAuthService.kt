package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.Future
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
private data class LoginOutcome(val login: PlatformLogin?)
private data class AdminLookup(val admin: PlatformAdmin?)

/** 平台控制面认证；身份和会话只保存在控制面数据库。 */
class PlatformAuthService(
    private val database: PgDatabase,
    private val mfaEncryptionKey: String
) {
    @Volatile private var activeSetupCredential: String? = null
    @Volatile private var setupCredentialExpiresAt: Instant? = null

    fun initializationStatus(): Future<Boolean> =
        database.withTransaction { connection ->
            connection.preparedQuery("SELECT EXISTS (SELECT 1 FROM iam.platform_admin) AS initialized")
                .execute().map { rows -> rows.first().getBoolean("initialized") }
        }

    /** 仅供 loopback 安装管理入口读取；明文只保存在当前进程内存。 */
    fun currentSetupCredential(): Future<String?> {
        val token = activeSetupCredential
            ?.takeIf { setupCredentialExpiresAt?.isAfter(Instant.now()) == true }
            ?: return Future.succeededFuture<String?>(null)
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT 1 FROM iam.platform_admin_setup_token
                   WHERE token_hash = $1 AND consumed_at IS NULL AND expires_at > clock_timestamp()"""
            ).execute(Tuple.of(AuthSupport.sha256(token))).map { rows -> rows.size() == 1 }
        }.map { isCurrent -> if (isCurrent) token else null }
    }

    fun createSetupCredential(): Future<String> {
        val token = AuthSupport.randomToken(32)
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """WITH lock AS MATERIALIZED (SELECT pg_advisory_xact_lock(724193810233))
                   SELECT count(*) AS total FROM iam.platform_admin CROSS JOIN lock"""
            ).execute().compose { rows ->
                if (rows.first().getLong("total") > 0L) {
                    Future.failedFuture("平台管理员已初始化，拒绝再次生成初始化凭据")
                } else {
                    connection.preparedQuery(
                        "UPDATE iam.platform_admin_setup_token SET consumed_at = clock_timestamp() WHERE consumed_at IS NULL"
                    ).execute().compose {
                        connection.preparedQuery(
                            """INSERT INTO iam.platform_admin_setup_token (id, token_hash, expires_at)
                               VALUES ($1, $2, clock_timestamp() + INTERVAL '15 minutes')"""
                        ).execute(Tuple.of(UUID.randomUUID(), AuthSupport.sha256(token))).map(token)
                    }
                }
            }
        }.map { issued ->
            activeSetupCredential = issued
            setupCredentialExpiresAt = Instant.now().plusSeconds(15 * 60)
            issued
        }
    }

    fun initialize(
        token: String, username: String, password: String, ip: String?, userAgent: String? = null
    ): Future<PlatformLogin> {
        require(username.matches(Regex("[A-Za-z0-9._-]{3,80}"))) { "管理员账号格式不正确" }
        validatePassword(password)
        val passwordHash = AuthSupport.hashPassword(password, PBKDF2_ITERATIONS)
        return database.withTransaction { connection ->
            connection.preparedQuery(
                """WITH lock AS MATERIALIZED (SELECT pg_advisory_xact_lock(724193810233))
                   SELECT token.id::text AS id FROM iam.platform_admin_setup_token token CROSS JOIN lock
                   WHERE token.token_hash = $1 AND token.consumed_at IS NULL
                     AND token.expires_at > clock_timestamp()
                   FOR UPDATE OF token"""
            ).execute(Tuple.of(AuthSupport.sha256(token))).compose { tokens ->
                if (tokens.size() != 1) return@compose Future.failedFuture("初始化凭据无效或已过期")
                connection.preparedQuery("SELECT count(*) AS total FROM iam.platform_admin").execute().compose { admins ->
                    if (admins.first().getLong("total") > 0L) return@compose Future.failedFuture("平台管理员已初始化")
                    val id = UUID.randomUUID()
                    val rawSession = AuthSupport.randomToken(32)
                    connection.preparedQuery(
                        "INSERT INTO iam.platform_admin (id, username, password_hash) VALUES ($1, $2, $3)"
                    ).execute(Tuple.of(id, username, passwordHash)).compose {
                        connection.preparedQuery(
                            """UPDATE iam.platform_admin_setup_token SET consumed_at = clock_timestamp()
                               WHERE id = $1 AND consumed_at IS NULL"""
                        ).execute(Tuple.of(UUID.fromString(tokens.first().getString("id")))).compose { consumed ->
                            if (consumed.rowCount() != 1) Future.failedFuture("初始化凭据已被使用")
                            else connection.preparedQuery(
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
        }.map { login ->
            activeSetupCredential = null
            setupCredentialExpiresAt = null
            login
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
