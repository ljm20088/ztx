package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.PlatformAuthService
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.ExecutionException
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

    @Test
    fun oneTimeSetupLoginLogoutAndTotpAreEnforced() {
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

        try {
            // When：部署人员生成一次性凭据，并据此创建首个平台管理员
            val setupToken = service.createSetupCredential().await()
            val admin = service.initialize(setupToken, "platform.admin", "ZtxAdmin123", "127.0.0.1").await()

            // Then：同一初始化凭据不可重放
            assertThrows(ExecutionException::class.java) {
                service.initialize(setupToken, "second.admin", "ZtxAdmin123", "127.0.0.1").await()
            }

            // When：管理员登录并验证服务端会话
            val login = service.login("platform.admin", "ZtxAdmin123", null, "127.0.0.1", "bdd-test").await()
            assertEquals(admin.id, service.authenticate(login.sessionToken).await()?.id)

            // When：管理员启用 TOTP MFA
            val enrollment = service.enrollMfa(admin).await()
            service.enableMfa(admin, currentTotp(enrollment.secret), "127.0.0.1").await()
            service.logout(login.sessionToken, "127.0.0.1").await()

            // Then：启用 MFA 后，不提供动态验证码不能登录
            assertThrows(ExecutionException::class.java) {
                service.login("platform.admin", "ZtxAdmin123", null, "127.0.0.1", "bdd-test").await()
            }

            // When：提供有效动态验证码登录
            val mfaLogin = service.login(
                "platform.admin", "ZtxAdmin123", currentTotp(enrollment.secret), "127.0.0.1", "bdd-test"
            ).await()

            // Then：登录成功且退出后会话立即失效
            assertNotNull(service.authenticate(mfaLogin.sessionToken).await())
            service.logout(mfaLogin.sessionToken, "127.0.0.1").await()
            assertNull(service.authenticate(mfaLogin.sessionToken).await())

            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM audit.platform_security_event").use { rows ->
                        rows.next()
                        assertEquals(true, rows.getInt(1) >= 6)
                    }
                }
            }
        } finally {
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

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS)
}
