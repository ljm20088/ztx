package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.adapters.postgres.PostgresOutboxStore
import com.ztx.server.BackgroundTaskWorker
import com.ztx.server.PlatformAdminResetCommand
import com.ztx.modules.iam.PlatformAuthRoutes
import com.ztx.modules.iam.PlatformAuthService
import com.ztx.platform.common.AppConfig
import com.ztx.platform.common.DatabaseConfig
import com.ztx.platform.common.RuntimeRole
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.ByteBuffer
import java.net.Socket
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
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
            // Given：平台初始化 HTTP 路由在本机回环地址监听
            val authRouter = Router.router(vertx)
            PlatformAuthRoutes(service, secureCookies = false).register(authRouter)
            val authServer = vertx.createHttpServer().requestHandler(authRouter).listen(0, "127.0.0.1").await()
            try {
                // When：从真实回环连接读取首次初始化状态
                val localStatus = rawHttpRequest(
                    authServer.actualPort(), "GET", "/api/v1/platform/auth/status",
                    "127.0.0.1:${authServer.actualPort()}"
                )

                // Then：本机请求可读取状态，并获知当前允许初始化
                assertTrue(localStatus.startsWith("HTTP/1.1 200"))
                assertTrue(localStatus.contains("\"canInitialize\":true"))

                // When：通过伪造的代理转发头提交初始化请求
                val forwardedRequest = rawHttpRequest(
                    authServer.actualPort(), "POST", "/api/v1/platform/auth/initialize",
                    "127.0.0.1:${authServer.actualPort()}", "X-Forwarded-For: 127.0.0.1"
                )

                // Then：服务端拒绝该请求，且没有创建管理员
                assertTrue(forwardedRequest.startsWith("HTTP/1.1 403"))
                assertFalse(service.initializationStatus().await())

                // When：回环 TCP 连接使用伪造的公网 Host
                val spoofedHostRequest = rawHttpRequest(
                    authServer.actualPort(), "POST", "/api/v1/platform/auth/initialize", "ztx.example.com"
                )

                // Then：服务端仍拒绝初始化
                assertTrue(spoofedHostRequest.startsWith("HTTP/1.1 403"))
                assertFalse(service.initializationStatus().await())
            } finally {
                authServer.close().await()
            }

            // Given：Worker 以独立进程身份启动并持续上报心跳
            val workerInstance = BackgroundTaskWorker(vertx, database, PostgresOutboxStore(database), "bdd-worker")
            worker = workerInstance
            workerInstance.start().await()
            val workerInstances = service.workerInstances().await()
            assertEquals(1, workerInstances.size())
            assertEquals("UP", workerInstances.getJsonObject(0).getString("status"))

            // Given：平台尚未初始化，且初始化不再依赖一次性口令
            assertEquals(false, service.initializationStatus().await())

            // When：尚无管理员时执行受控密码重置
            val missingAdminReset = service.resetPasswordFromServerCommand("ZtxRecovery456", "ztx-deployer").await()

            // Then：拒绝重置且不创建管理员
            assertFalse(missingAdminReset.success)
            assertEquals(false, service.initializationStatus().await())

            // Given：初始化路由仍在服务器回环地址监听
            val initializeRouter = Router.router(vertx)
            PlatformAuthRoutes(service, secureCookies = false).register(initializeRouter)
            val initializeServer = vertx.createHttpServer()
                .requestHandler(initializeRouter).listen(0, "127.0.0.1").await()
            val initializeHost = "127.0.0.1:${initializeServer.actualPort()}"
            try {
                // When：两个真实 HTTP 初始化请求并发提交相同的首个平台管理员
                val concurrentRequests = listOf(
                    CompletableFuture.supplyAsync {
                        rawHttpRequest(initializeServer.actualPort(), "POST",
                            "/api/v1/platform/auth/initialize", initializeHost)
                    },
                    CompletableFuture.supplyAsync {
                        rawHttpRequest(initializeServer.actualPort(), "POST",
                            "/api/v1/platform/auth/initialize", initializeHost)
                    }
                )
                val responses = concurrentRequests.map { it.get(30, TimeUnit.SECONDS) }

                // Then：真实 API 只创建一个管理员；另一请求得到冲突响应
                assertEquals(1, responses.count { it.startsWith("HTTP/1.1 201") })
                assertEquals(1, responses.count { it.startsWith("HTTP/1.1 409") })
                assertTrue(responses.any { it.lowercase().contains("set-cookie: ztx_platform_session=") })
            } finally {
                initializeServer.close().await()
            }

            // Then：通过 HTTP 初始化成功后，数据库中只有一个管理员
            assertTrue(service.initializationStatus().await())
            val initialized = service.login("platform.admin", "ZtxAdmin123", null,
                "127.0.0.1", "bdd-test").await()
            val admin = initialized.admin

            // When：模拟 Server 重启后由新认证服务实例读取同一控制面数据库
            val restartedService = PlatformAuthService(database, "test-mfa-encryption-key-32-bytes-minimum")

            // Then：管理员记录仍然存在，首次初始化不会因应用重启重新开放
            assertTrue(restartedService.initializationStatus().await())

            // Given：平台管理员已执行租户生命周期操作，事件记录在独立租户审计表
            val auditedTenantId = java.util.UUID.randomUUID()
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    """INSERT INTO platform.tenant(id, code, name, status, database_name)
                       VALUES (?, 'audit-demo', '审计演示企业', 'ACTIVE', 'ztx_t_audit_demo')"""
                ).use { statement ->
                    statement.setObject(1, auditedTenantId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO audit.tenant_event(id, tenant_id, actor_admin_id, action)
                       VALUES (?, ?, ?, 'TENANT_ACTIVE')"""
                ).use { statement ->
                    statement.setObject(1, java.util.UUID.randomUUID())
                    statement.setObject(2, auditedTenantId)
                    statement.setObject(3, admin.id)
                    statement.executeUpdate()
                }
            }

            // Then：平台统一审计查询同时返回安全事件和租户生命周期事件
            val auditEvents = service.recentAuditEvents().await()
            val tenantAudit = (0 until auditEvents.size()).map { auditEvents.getJsonObject(it) }
                .firstOrNull { it.getString("eventType") == "TENANT_ACTIVE" }
            assertNotNull(tenantAudit)
            assertEquals("audit-demo", tenantAudit!!.getString("tenantCode"))
            assertEquals("审计演示企业", tenantAudit.getString("tenantName"))

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

            // Given：同一管理员另有一个仍然有效的平台会话
            val activeSession = service.login(
                "platform.admin", "ZtxAdmin123", currentTotp(enrollment.secret), "127.0.0.1", "bdd-test"
            ).await()

            // When：从非交互式进程调用服务器重置命令（没有可用终端）
            val commandConfig = AppConfig(
                role = RuntimeRole.SERVER,
                httpHost = "127.0.0.1",
                httpPort = 8080,
                migrateOnStart = false,
                mfaEncryptionKey = "test-mfa-encryption-key-32-bytes-minimum",
                secureCookies = false,
                database = config
            )
            val nonInteractiveExitCode = PlatformAdminResetCommand.execute(commandConfig, null)

            // Then：命令拒绝非交互式输入并记录失败审计，不触碰现有会话
            assertEquals(2, nonInteractiveExitCode)

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

            // Given：部署人员通过服务器交互式命令执行恢复，先提交一次确认不一致的失败尝试
            val mismatch = service.recordPasswordResetCommandFailure(
                "ztx-deployer", "PASSWORD_CONFIRMATION_MISMATCH"
            ).await()
            assertFalse(mismatch.success)

            // When：部署人员提交不符合策略的新密码
            val rejectedPassword = service.resetPasswordFromServerCommand("weak", "ztx-deployer").await()

            // Then：密码保持不变，会话不被撤销，失败操作留下审计
            assertFalse(rejectedPassword.success)
            assertNotNull(service.authenticate(activeSession.sessionToken).await())

            // When：部署人员提交符合策略的新密码
            val reset = service.resetPasswordFromServerCommand("ZtxRecovery456", "ztx-deployer").await()

            // Then：只重置现有管理员密码，撤销全部会话，并保留已启用的 MFA
            assertTrue(reset.success)
            assertNull(service.authenticate(activeSession.sessionToken).await())
            assertEquals(true, service.mfaEnabled().await())
            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM iam.platform_admin_session WHERE revoked_at IS NULL AND expires_at > clock_timestamp()"
                    ).use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
            val recoveredLogin = service.login(
                "platform.admin", "ZtxRecovery456", currentTotp(enrollment.secret), "127.0.0.1", "bdd-test"
            ).await()
            assertEquals(admin.id, recoveredLogin.admin.id)

            DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
                connection.prepareStatement(
                    """SELECT outcome, details->>'operator' AS operator, details->>'targetUsername' AS target_username
                       FROM audit.platform_security_event
                       WHERE event_type = 'ADMIN_PASSWORD_RESET'
                       ORDER BY occurred_at"""
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        val resetAudits = mutableListOf<Triple<String, String?, String?>>()
                        while (rows.next()) {
                            resetAudits += Triple(rows.getString("outcome"), rows.getString("operator"), rows.getString("target_username"))
                        }
                        assertEquals(5, resetAudits.size)
                        assertEquals(4, resetAudits.count { it.first == "FAILURE" })
                        assertEquals(1, resetAudits.count { it.first == "SUCCESS" })
                        assertTrue(resetAudits.all { it.second == "ztx-deployer" })
                        assertTrue(resetAudits.all { it.third == "platform.admin" })
                    }
                }
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

    private fun rawHttpRequest(
        port: Int,
        method: String,
        path: String,
        host: String,
        extraHeader: String? = null
    ): String {
        val body = if (method == "POST") """{"username":"platform.admin","password":"ZtxAdmin123"}""" else ""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            output.write("$method $path HTTP/1.1\r\n")
            output.write("Host: $host\r\n")
            output.write("Connection: close\r\n")
            if (method == "POST") {
                output.write("Content-Type: application/json\r\n")
                output.write("Content-Length: ${bodyBytes.size}\r\n")
            }
            if (extraHeader != null) output.write("$extraHeader\r\n")
            output.write("\r\n")
            output.flush()
            if (bodyBytes.isNotEmpty()) {
                socket.getOutputStream().write(bodyBytes)
                socket.getOutputStream().flush()
            }
            socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
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
