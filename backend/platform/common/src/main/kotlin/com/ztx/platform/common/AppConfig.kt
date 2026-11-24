package com.ztx.platform.common

data class AppConfig(
    val role: RuntimeRole,
    val httpHost: String,
    val httpPort: Int,
    val migrateOnStart: Boolean,
    val database: DatabaseConfig
) {
    companion object {
        fun load(
            args: Array<String> = emptyArray(),
            env: Map<String, String> = System.getenv(),
            systemProperties: Map<String, String> = System.getProperties().stringPropertyNames()
                .associateWith { System.getProperty(it) },
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader
        ): AppConfig = fromConfig(ConfigLoader.load(args, env, systemProperties, classLoader))

        fun fromConfig(config: Map<String, Any?>): AppConfig {
            val app = config.section("app")
            val server = config.section("server")
            val database = config.section("database")
            val port = server.int("port", 8080)
            require(port in 1..65535) { "server.port 必须在 1 到 65535 之间" }
            val migrate = database.boolean("migrate-on-start", false)
            return AppConfig(
                role = RuntimeRole.fromEnvironment(app.string("role", "server")),
                httpHost = server.string("host", "0.0.0.0"),
                httpPort = port,
                migrateOnStart = migrate,
                database = DatabaseConfig.fromConfig(database)
            )
        }

        private fun Map<String, Any?>.section(name: String): Map<String, Any?> {
            val value = this[name] ?: return emptyMap()
            require(value is Map<*, *>) { "$name 配置必须是对象" }
            return value.entries.associate { (key, item) ->
                require(key is String) { "$name 配置包含非法键" }
                key to item
            }
        }

        private fun Map<String, Any?>.string(name: String, default: String): String {
            val value = this[name] ?: return default
            require(value is String) { "$name 配置必须是字符串" }
            return value
        }

        private fun Map<String, Any?>.int(name: String, default: Int): Int {
            val value = this[name] ?: return default
            return value.toString().toIntOrNull()
                ?: throw IllegalArgumentException("$name 配置必须是整数")
        }

        private fun Map<String, Any?>.boolean(name: String, default: Boolean): Boolean {
            val value = this[name] ?: return default
            return when (value) {
                is Boolean -> value
                is String -> value.toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("$name 配置必须是 true 或 false")
                else -> throw IllegalArgumentException("$name 配置必须是 true 或 false")
            }
        }
    }
}
