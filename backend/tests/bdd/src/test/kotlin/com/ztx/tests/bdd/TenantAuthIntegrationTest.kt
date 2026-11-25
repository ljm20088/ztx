package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.PlatformAdmin
import com.ztx.modules.iam.TenantAuthRoutes
import com.ztx.modules.iam.TenantAuthService
import com.ztx.modules.iam.TenantDatabaseRegistry
import com.ztx.modules.iam.TenantLifecycleService
import com.ztx.modules.iam.TenantPrincipal
import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class TenantAuthIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    // 场景：两个租户存在同名管理员时，登录会话和服务端数据路由仍严格隔离
    @Test
    fun shouldIsolateSameNamedAccountsAndIgnoreClientTenantHints() {
        // Given：控制面和两个租户数据库均已迁移，两个租户使用相同管理员账号
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host, port = postgres.firstMappedPort, database = postgres.databaseName,
            user = postgres.username, password = postgres.password, poolSize = 8,
            migrationLocations = "filesystem:${projectDir.resolve("database/migrations")}"
        )
        FlywayMigrator(config).migrate()
        val vertx = Vertx.vertx()
        val control = PgDatabase(vertx, config)
        val lifecycle = TenantLifecycleService(
            vertx, control, config,
            tenantMigrationLocations = "filesystem:${projectDir.resolve("database/tenant-migrations")}"
        )
        val adminId = UUID.randomUUID()
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.prepareStatement(
                "INSERT INTO iam.platform_admin(id, username, password_hash) VALUES (?, ?, ?)"
            ).use { statement ->
                statement.setObject(1, adminId)
                statement.setString(2, "platform.test")
                statement.setString(3, "test-only")
                statement.executeUpdate()
            }
        }
        val actor = PlatformAdmin(adminId, "platform.test", false)
        val provisionA = lifecycle.create("tenant_a", "租户 A", actor).await()
        val provisionB = lifecycle.create("tenant_b", "租户 B", actor).await()
        val tenantA = provisionA.tenant
        val tenantB = provisionB.tenant
        lifecycle.initializeTenantAdmin("tenant_a", requireNotNull(provisionA.setupToken), "shared.admin", "TenantPass123").await()
        lifecycle.initializeTenantAdmin("tenant_b", requireNotNull(provisionB.setupToken), "shared.admin", "TenantPass123").await()
        val registry = TenantDatabaseRegistry(vertx, lifecycle, config)
        val auth = TenantAuthService(registry)

        try {
            // When：两个租户的同名管理员分别登录
            val loginA = auth.login("tenant_a", "shared.admin", "TenantPass123", null, "bdd").await()
            val loginB = auth.login("tenant_b", "shared.admin", "TenantPass123", null, "bdd").await()

            // Then：会话令牌、用户身份和数据库路由均独立，A 的令牌不能在 B 使用
            assertNotEquals(loginA.sessionToken, loginB.sessionToken)
            assertEquals(tenantA.id, loginA.principal.tenantId)
            assertEquals(tenantB.id, loginB.principal.tenantId)
            assertNotEquals(loginA.principal.databaseName, loginB.principal.databaseName)
            assertNotNull(auth.authenticate("tenant_a", loginA.sessionToken).await())
            assertNull(auth.authenticate("tenant_b", loginA.sessionToken).await())
            assertEquals(tenantA.databaseName, registry.resolve(tenantA.id).await().tenant.databaseName)

            // When：租户 B 的另一个会话到达数据库过期时间
            val expiringLogin = auth.login("tenant_b", "shared.admin", "TenantPass123", null, "bdd").await()
            val expiredHash = MessageDigest.getInstance("SHA-256").digest(expiringLogin.sessionToken.toByteArray())
                .joinToString("") { "%02x".format(it) }
            DriverManager.getConnection(
                "jdbc:postgresql://${config.host}:${config.port}/${tenantB.databaseName}", config.user, config.password
            ).use { connection ->
                connection.prepareStatement(
                    "UPDATE iam.tenant_admin_session SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE token_hash = ?"
                ).use { statement ->
                    statement.setString(1, expiredHash)
                    statement.executeUpdate()
                }
            }

            // Then：数据库过期的会话立即失效
            assertNull(auth.authenticate("tenant_b", expiringLogin.sessionToken).await())

            // When：租户 B 管理员使用当前密码修改凭据
            auth.changePassword(loginB.principal, loginB.sessionToken,
                "TenantPass123", "TenantPass456", null).await()

            // Then：当前会话保留，其他会话撤销，新密码可登录
            assertNotNull(auth.authenticate("tenant_b", loginB.sessionToken).await())
            assertNull(auth.authenticate("tenant_b", expiringLogin.sessionToken).await())
            val changedPasswordLogin = auth.login("tenant_b", "shared.admin", "TenantPass456", null, "bdd").await()
            assertNotNull(auth.authenticate("tenant_b", changedPasswordLogin.sessionToken).await())

            // When：客户端携带租户 B 的伪造请求头和查询参数，却使用租户 A 的有效会话
            val router = Router.router(vertx)
            TenantAuthRoutes(auth, lifecycle, false).registerGuard(router)
            router.get("/api/v1/test/tenant-context").handler { context ->
                val principal = context.get<TenantPrincipal>(TENANT_CONTEXT_KEY)
                context.response().putHeader("content-type", "application/json")
                    .end(io.vertx.core.json.JsonObject().put("tenantCode", principal.tenantCode)
                        .put("databaseName", principal.databaseName).encode())
            }
            val server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").await()
            val request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:${server.actualPort()}/api/v1/test/tenant-context?tenantCode=tenant_b&database=postgres")
            ).header("Cookie", "ztx_tenant_session=tenant_a.${loginA.sessionToken}")
                .header("X-Tenant-Code", "tenant_b")
                .header("X-Database-Name", "postgres").GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            // Then：服务端仍从 A 的有效会话和控制面路由建立上下文，客户端提示不改变数据源
            assertEquals(200, response.statusCode())
            val contextJson = io.vertx.core.json.JsonObject(response.body())
            assertEquals("tenant_a", contextJson.getString("tenantCode"))
            assertEquals(tenantA.databaseName, contextJson.getString("databaseName"))

            // When：使用 A 的会话退出登录
            auth.logout("tenant_a", loginA.sessionToken).await()

            // Then：已撤销会话不能再次认证，B 的会话不受影响
            assertNull(auth.authenticate("tenant_a", loginA.sessionToken).await())
            assertNotNull(auth.authenticate("tenant_b", loginB.sessionToken).await())
            server.close().await()
        } finally {
            registry.close()
            control.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }

    private fun <T> io.vertx.core.Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS)
}
