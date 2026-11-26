package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.adapters.postgres.PostgresOutboxStore
import com.ztx.server.BackgroundTaskWorker
import com.ztx.modules.iam.PlatformAuthService
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Testcontainers(disabledWithoutDocker = true)
class PlatformAuthIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    // 平台首次初始化仅允许创建一个管理员，成功后自动登录且不可重复初始化。
    @Test
    fun firstRunInitializationSessionExpiryMfaAndLoginThrottlingAreEnforced() {
        // Given：全新的控制面数据库，尚未创建任何平台管理员
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            poolSize = 4,
            migrationLocations = "filesystem:${projectDir.resolve("database/migrations")}"
        )
        FlywayMigrator(config).migrate()
        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config)
        val service = PlatformAuthService(database, "test-mfa-encryption-key-32-bytes-minimum")
        var worker: BackgroundTaskWorker? = null

        try {
            // Given：Worker 以独立进程身份启动并持续上报心跳
            val workerInstance = BackgroundTaskWorker(vertx, database, PostgresOutboxStore(database), "bdd-worker")
            worker = workerInstance
            workerInstance.start().await()
            val workerInstances = service.workerInstances().await()
            assertEquals(1, workerInstances.size())
            assertEquals("UP", workerInstances.getJsonObject(0).getString("status"))

            // Given：平台尚未初始化，且初始化不再依赖一次性口令
            assertEquals(false, service.initializationStatus().await())

            // When：两个初始化请求并发提交不同账号
            val concurrentAttempts = listOf(
                service.initialize("platform.admin", "ZtxAdmin123", "127.0.0.1", "bdd-test"),
                service.initialize("second.admin", "ZtxAdmin123", "127.0.0.1", "bdd-test")
            )
            val outcomes = concurrentAttempts.map { attempt -> runCatching { attempt.await() } }

            // Then：数据库事务锁保证只能创建一个首个平台管理员
            assertEquals(1, outcomes.count { it.isSuccess })
            val initialized = outcomes.single { it.isSuccess }.getOrThrow()
            val admin = initialized.admin
            assertEquals(true, service.initializationStatus().await())
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM iam.platform_admin").use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }

            // Then：初始化成功后已自动建立会话，无需再次登录
            assertEquals(admin.id, service.authenticate(initialized.sessionToken).await()?.id)

            // Given：平台管理员已存在
            // Then：重复初始化被拒绝，不能创建第二个管理员或重置现有账号
            assertFailureContaining("平台管理员已初始化") {
                service.initialize("second.admin", "ZtxAdmin123", "127.0.0.1", "bdd-test").await()
            }

            // Given：尚未配置 MFA 加密密钥
            val serviceWithoutMfaKey = PlatformAuthService(database, "")
            // Then：普通用户名密码登录仍可使用；只有启用 MFA 时才要求配置密钥
            assertEquals(admin.id, serviceWithoutMfaKey.login(
                "platform.admin", "ZtxAdmin123", null, "127.0.0.1", "bdd-test"
            ).await().admin.id)
            assertThrows(IllegalArgumentException::class.java) {
                serviceWithoutMfaKey.enrollMfa(admin)
            }

            // When：管理员登录并验证服务端会话
            val login = service.login("platform.admin", "ZtxAdmin123", null, "127.0.0.1", "bdd-test").await()
            assertEquals(admin.id, service.authenticate(login.sessionToken).await()?.id)

            // When：管理员启用 TOTP MFA
            val enrollment = service.enrollMfa(admin).await()
            service.enableMfa(admin, currentTotp(enrollment.secret), "127.0.0.1").await()
            service.logout(login.sessionToken, "127.0.0.1").await()

            // Then：启用 MFA 后，不提供动态验证码不能登录
            assertFailureContaining("账号、密码或动态验证码不正确") {
                service.login("platform.admin", "ZtxAdmin123", null, "127.0.0.1", "bdd-test").await()
            }

            // When：提供有效动态验证码登录
            val mfaLogin = service.login(
                "platform.admin", "ZtxAdmin123", currentTotp(enrollment.secret), "127.0.0.1", "bdd-test"
            ).await()

            // Then：会话在有效期内可认证；超过有效期后服务端拒绝该会话
            assertNotNull(service.authenticate(mfaLogin.sessionToken).await())
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    "UPDATE iam.platform_admin_session SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE token_hash = ?"
                ).use { statement ->
                    statement.setString(1, sha256(mfaLogin.sessionToken))
                    statement.executeUpdate()
                }
            }
            assertNull(service.authenticate(mfaLogin.sessionToken).await())

            // Given：同一来源 IP 与账号在 15 分钟窗口内连续输错密码
            repeat(5) {
                // When / Then：连续错误凭据均被拒绝
                assertFailureContaining("账号、密码或动态验证码不正确") {
                    service.login("platform.admin", "WrongPass123", currentTotp(enrollment.secret),
                        "127.0.0.1", "bdd-test").await()
                }
            }

            // Then：达到失败阈值后，即使提供正确密码和 MFA 也必须暂时拒绝
            assertFailureContaining("账号、密码或动态验证码不正确") {
                service.login("platform.admin", "ZtxAdmin123", currentTotp(enrollment.secret),
                    "127.0.0.1", "bdd-test").await()
            }

            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM audit.platform_security_event").use { rows ->
                        rows.next()
                        assertTrue(rows.getInt(1) >= 13)
                    }
                }
            }
        } finally {
            worker?.close()
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }

    private fun currentTotp(secret: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        secret.forEach { char ->
            buffer = (buffer shl 5) or alphabet.indexOf(char)
            bits += 5
            if (bits >= 8) {
                bytes += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        val counter = System.currentTimeMillis() / 1000 / 30
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(bytes.toByteArray(), "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun assertFailureContaining(expectedMessage: String, action: () -> Unit) {
        val failure = assertThrows(Exception::class.java) { action() }
        val messages = generateSequence(failure as Throwable?) { it.cause }
            .mapNotNull { it.message }
        assertTrue(messages.any { expectedMessage in it }, "异常链中应包含：$expectedMessage")
    }

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS)
}
