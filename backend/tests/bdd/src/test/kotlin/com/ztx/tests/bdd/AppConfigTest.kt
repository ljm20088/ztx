package com.ztx.tests.bdd

import com.ztx.platform.common.AppConfig
import com.ztx.platform.common.RuntimeRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppConfigTest {
    @Test
    fun environmentLoadsSafeServerDefaults() {
        // Given：开发环境提供数据库凭据但未覆盖其他配置
        val env = mapOf("ZTX_DB_PASSWORD" to "sensitive-test-value")
        // When：加载应用配置
        val config = AppConfig.fromEnvironment(env)
        // Then：默认角色为 Server，配置文本不会泄露密码
        assertEquals(RuntimeRole.SERVER, config.role)
        assertEquals(8080, config.httpPort)
        assertFalse(config.database.toString().contains("sensitive-test-value"))
    }

    @Test
    fun workerRoleCanBeSelected() {
        // Given：运行环境声明 worker 角色并提供数据库密码
        val env = mapOf("ZTX_APP_ROLE" to "worker", "ZTX_DB_PASSWORD" to "test-secret")
        // When：解析运行角色
        val config = AppConfig.fromEnvironment(env)
        // Then：应用选择 Worker 运行角色
        assertEquals(RuntimeRole.WORKER, config.role)
    }

    @Test
    fun missingDatabasePasswordIsRejected() {
        // Given：运行环境没有提供数据库凭据
        // When / Then：配置加载失败，不使用内置数据库密码
        assertThrows(IllegalArgumentException::class.java) { AppConfig.fromEnvironment(emptyMap()) }
    }

    @Test
    fun databaseMigrationIsDisabledByDefault() {
        // Given：运行环境未明确授权启动时自动迁移
        val env = mapOf("ZTX_DB_PASSWORD" to "test-secret")
        // When：加载配置
        val config = AppConfig.fromEnvironment(env)
        // Then：默认不在应用启动时修改数据库结构
        assertFalse(config.migrateOnStart)
    }
}
