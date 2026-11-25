package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.PlatformAdmin
import com.ztx.modules.iam.TenantLifecycleService
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Vertx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class TenantLifecycleIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    @Test
    // 场景：平台管理员开通两个租户，系统为其创建隔离的业务数据库并支持生命周期管理
    fun shouldProvisionIsolatedDatabasesAndManageTenantLifecycle() {
        // Given：控制面数据库已完成迁移，平台管理员已登录
        val projectDir = Path.of(System.getProperty("ztx.projectDir"))
        val config = DatabaseConfig(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            poolSize = 6,
            migrationLocations = "filesystem:${projectDir.resolve("database/migrations")}"
        )
        FlywayMigrator(config).migrate()
        val vertx = Vertx.vertx()
        val controlDatabase = PgDatabase(vertx, config)
        val service = TenantLifecycleService(
            vertx, controlDatabase, config
        )
        val actor = PlatformAdmin(UUID.randomUUID(), "platform.admin", false)

        try {
            // When：平台管理员连续开通两个不同租户
            val tenantA = service.create("tenant_a", "租户 A", actor).await()
            val tenantB = service.create("tenant_b", "租户 B", actor).await()

            // Then：两个租户都已开通，数据库路由不同，且首次管理员设置凭据只在开通响应中返回
            assertEquals("ACTIVE", tenantA.tenant.status)
            assertEquals("ACTIVE", tenantB.tenant.status)
            assertNotEquals(tenantA.tenant.databaseName, tenantB.tenant.databaseName)
            assertNotNull(tenantA.setupToken)
            assertNotNull(tenantB.setupToken)
            assertEquals(tenantA.tenant.databaseName, service.activeDatabaseName("tenant_a").await())
            assertEquals(tenantB.tenant.databaseName, service.activeDatabaseName("tenant_b").await())

            // Then：租户数据库分别保存各自的租户标识和管理员初始化凭据
            listOf(tenantA, tenantB).forEach { result ->
                DriverManager.getConnection(
                    "jdbc:postgresql://${config.host}:${config.port}/${result.tenant.databaseName}",
                    config.user, config.password
                ).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT code FROM platform.tenant_info").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(result.tenant.code, rows.getString("code"))
                        }
                        statement.executeQuery("SELECT count(*) FROM iam.tenant_admin_setup_token").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1, rows.getInt(1))
                        }
                    }
                }
            }

            // When：重复提交相同租户编码
            val duplicate = service.create("tenant_a", "租户 A", actor).await()

            // Then：返回既有租户，不会创建重复租户或重新覆盖数据库
            assertEquals(tenantA.tenant.id, duplicate.tenant.id)
            assertEquals(tenantA.tenant.databaseName, duplicate.tenant.databaseName)
            assertEquals(2, service.list().await().size)

            // When：平台管理员停用租户 A
            service.disable(tenantA.tenant.id, actor).await()

            // Then：停用租户不能取得业务数据库路由
            assertThrows(Exception::class.java) {
                service.activeDatabaseName("tenant_a").await()
            }

            // When：平台管理员重新启用租户 A
            val enabled = service.enable(tenantA.tenant.id, actor).await()

            // Then：租户恢复可用，历史数据库保持不变
            assertEquals("ACTIVE", enabled.status)
            assertEquals(tenantA.tenant.databaseName, service.activeDatabaseName("tenant_a").await())
        } finally {
            controlDatabase.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }

    private fun <T> io.vertx.core.Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS)
}
