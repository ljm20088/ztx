package com.ztx.platform.common

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * ZTX 配置加载入口：默认 YAML -> Profile YAML -> 外部 YAML -> 显式环境变量覆盖。
 * 环境变量只在此处映射，业务代码只消费已校验的配置对象。
 */
object ConfigLoader {
    fun load(
        args: Array<String> = emptyArray(),
        env: Map<String, String> = System.getenv(),
        systemProperties: Map<String, String> = System.getProperties().stringPropertyNames()
            .associateWith { System.getProperty(it) },
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader
    ): Map<String, Any?> {
        val options = parseArguments(args)
        val profile = options["ztx.profile"] ?: systemProperties["ztx.profile"] ?: env["ZTX_PROFILE"]
        val externalConfig = options["ztx.config"] ?: systemProperties["ztx.config"] ?: env["ZTX_CONFIG_FILE"]

        val merged = linkedMapOf<String, Any?>()
        mergeInto(merged, readResource(classLoader, "application.yaml", required = true))
        if (!profile.isNullOrBlank()) {
            require(PROFILE_PATTERN.matches(profile)) { "ZTX Profile 名称格式不合法" }
            mergeInto(merged, readResource(classLoader, "application-$profile.yaml", required = true))
        }
        if (!externalConfig.isNullOrBlank()) {
            val path = Path.of(externalConfig).toAbsolutePath().normalize()
            require(Files.isRegularFile(path) && Files.isReadable(path)) { "外部配置文件不存在或不可读取" }
            Files.newInputStream(path).use { mergeInto(merged, readYaml(it, path.toString())) }
        }
        mergeInto(merged, environmentOverrides(env))
        return merged
    }

    private fun parseArguments(args: Array<String>): Map<String, String> =
        args.mapNotNull { arg ->
            if (!arg.startsWith("--ztx.")) return@mapNotNull null
            val separator = arg.indexOf('=')
            require(separator > 0) { "ZTX 启动参数必须使用 --ztx.name=value 格式" }
            arg.substring(2, separator) to arg.substring(separator + 1)
        }.toMap()

    private fun readResource(classLoader: ClassLoader, name: String, required: Boolean): Map<String, Any?> {
        val stream = classLoader.getResourceAsStream(name)
        if (stream == null) {
            require(!required) { "缺少默认配置文件 $name" }
            return emptyMap()
        }
        return stream.use { readYaml(it, name) }
    }

    private fun readYaml(input: InputStream, source: String): Map<String, Any?> {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 20
            codePointLimit = 2 * 1024 * 1024
        }
        val parsed = try {
            Yaml(SafeConstructor(options)).load<Any?>(input)
        } catch (error: Exception) {
            throw IllegalArgumentException("配置文件 $source 格式错误", error)
        } ?: emptyMap<String, Any?>()
        require(parsed is Map<*, *>) { "配置文件 $source 的根节点必须是对象" }
        return parsed.entries.associate { (key, value) ->
            require(key is String) { "配置文件 $source 存在非字符串配置键" }
            key to value
        }
    }

    private fun environmentOverrides(env: Map<String, String>): Map<String, Any?> {
        val database = linkedMapOf<String, Any?>()
        fun put(target: String, source: String) {
            env[source]?.let { database[target] = it }
        }
        put("host", "ZTX_DB_HOST")
        put("port", "ZTX_DB_PORT")
        put("name", "ZTX_DB_NAME")
        put("user", "ZTX_DB_USER")
        put("password", "ZTX_DB_PASSWORD")
        if ("password" !in database) put("password", "POSTGRES_PASSWORD")
        put("pool-size", "ZTX_DB_POOL_SIZE")
        put("migrations", "ZTX_DB_MIGRATIONS")
        env["ZTX_DB_MIGRATE_ON_START"]?.let { database["migrate-on-start"] = it }

        val root = linkedMapOf<String, Any?>()
        if (database.isNotEmpty()) root["database"] = database
        val server = linkedMapOf<String, Any?>()
        env["ZTX_HTTP_HOST"]?.let { server["host"] = it }
        env["ZTX_HTTP_PORT"]?.let { server["port"] = it }
        if (server.isNotEmpty()) root["server"] = server
        val app = linkedMapOf<String, Any?>()
        env["ZTX_APP_ROLE"]?.let { app["role"] = it }
        if (app.isNotEmpty()) root["app"] = app
        env["ZTX_LOG_LEVEL"]?.let { root["logging"] = mapOf("level" to it) }
        return root
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeInto(target: MutableMap<String, Any?>, source: Map<String, Any?>) {
        source.forEach { (key, value) ->
            val current = target[key]
            if (current is MutableMap<*, *> && value is Map<*, *>) {
                mergeInto(current as MutableMap<String, Any?>, value as Map<String, Any?>)
            } else if (value is Map<*, *>) {
                val nested = linkedMapOf<String, Any?>()
                mergeInto(nested, value as Map<String, Any?>)
                target[key] = nested
            } else {
                target[key] = value
            }
        }
    }

    private val PROFILE_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
}
