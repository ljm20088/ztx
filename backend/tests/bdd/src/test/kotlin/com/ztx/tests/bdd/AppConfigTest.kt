package com.ztx.tests.bdd

import com.ztx.platform.common.AppConfig
import com.ztx.platform.common.ConfigLoader
import com.ztx.platform.common.RuntimeRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AppConfigTest {
    @Test
    fun defaultYamlLoadsWhenNoProfileIsSelected() {
        // Given：未指定 Profile，运行环境提供数据库密码
        val config = AppConfig.load(env = mapOf("ZTX_DB_PASSWORD" to "test-secret"))
        // When：读取默认配置
        // Then：只采用 application.yml 中的默认端口和角色
        assertEquals(RuntimeRole.SERVER, config.role)
        assertEquals(8080, config.httpPort)
        assertEquals("localhost", config.database.host)
    }

    @Test
    fun profileOverridesDeclaredValuesAndInheritsDefaults() {
        // Given：激活 dev Profile，且只覆盖默认配置中的部分值
        val merged = ConfigLoader.load(
            args = arrayOf("--ztx.profile=dev"),
            env = mapOf("ZTX_DB_PASSWORD" to "test-secret")
        )
        // When：将合并后的配置转换为应用配置
        val config = AppConfig.fromConfig(merged)
        // Then：Profile 的值覆盖默认值，未声明的值继续继承
        assertEquals("localhost", config.database.host)
        assertEquals(8080, config.httpPort)
        assertEquals(10, config.database.poolSize)
    }

    @Test
    fun explicitProfileArgumentSelectsTestConfiguration() {
        // Given：启动参数显式指定 test Profile
        val config = AppConfig.load(
            args = arrayOf("--ztx.profile=test"),
            env = mapOf("ZTX_DB_PASSWORD" to "test-secret")
        )
        // When：读取应用配置
        // Then：采用 test Profile 的端口覆盖值
        assertEquals(18080, config.httpPort)
    }

    @Test
    fun environmentVariablesOverrideYamlValues() {
        // Given：YAML 定义默认端口，环境变量显式覆盖端口和迁移开关
        val config = AppConfig.load(
            env = mapOf(
                "ZTX_HTTP_PORT" to "9090",
                "ZTX_DB_MIGRATE_ON_START" to "true",
                "ZTX_DB_PASSWORD" to "test-secret"
            )
        )
        // When：加载配置
        // Then：环境变量作为可选覆盖生效
        assertEquals(9090, config.httpPort)
        assertTrue(config.migrateOnStart)
    }

    @Test
    fun externalYamlOverridesProfileAndKeepsUnspecifiedDefaults(@TempDir directory: Path) {
        // Given：外部配置只声明数据库名称
        val external = directory.resolve("external.yml")
        Files.writeString(external, "database:\n  name: customer_ztx\n")
        // When：加载默认配置、dev Profile 和外部配置
        val config = AppConfig.load(
            args = arrayOf("--ztx.profile=dev", "--ztx.config=${external}"),
            env = mapOf("ZTX_DB_PASSWORD" to "test-secret")
        )
        // Then：外部值生效，其他默认值和 Profile 值继续保留
        assertEquals("customer_ztx", config.database.database)
        assertEquals("localhost", config.database.host)
        assertEquals(8080, config.httpPort)
    }

    @Test
    fun missingDatabasePasswordIsRejectedWithoutSecretInError() {
        // Given：YAML 和环境变量均未提供数据库密码
        // When：加载应用配置
        val error = assertThrows(IllegalArgumentException::class.java) {
            AppConfig.load(env = emptyMap(), systemProperties = emptyMap())
        }
        // Then：启动前校验失败，错误信息不包含敏感值
        assertTrue(error.message!!.contains("database.password"))
        assertFalse(error.message!!.contains("secret"))
    }

    @Test
    fun databaseMigrationIsDisabledByDefault() {
        // Given：默认 YAML 未授权启动时自动迁移
        val config = AppConfig.load(env = mapOf("ZTX_DB_PASSWORD" to "test-secret"))
        // When：加载配置
        // Then：默认不在应用启动时修改数据库结构
        assertFalse(config.migrateOnStart)
    }

    @Test
    fun invalidPortIsRejected() {
        // Given：环境变量提供非法端口
        // When / Then：配置校验失败
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.load(env = mapOf("ZTX_HTTP_PORT" to "not-a-port", "ZTX_DB_PASSWORD" to "test-secret"))
        }
    }
}
