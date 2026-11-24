package com.ztx.platform.common

data class AppConfig(
    val role: RuntimeRole,
    val httpHost: String,
    val httpPort: Int,
    val migrateOnStart: Boolean,
    val database: DatabaseConfig
) {
    companion object {
        fun fromEnvironment(env: Map<String, String>): AppConfig {
            val port = env["ZTX_HTTP_PORT"]?.toIntOrNull() ?: 8080
            require(port in 1..65535) { "ZTX_HTTP_PORT 必须在 1 到 65535 之间" }
            val migrate = env["ZTX_DB_MIGRATE_ON_START"]?.toBooleanStrictOrNull() ?: false
            return AppConfig(
                role = RuntimeRole.fromEnvironment(env["ZTX_APP_ROLE"]),
                httpHost = env["ZTX_HTTP_HOST"] ?: "0.0.0.0",
                httpPort = port,
                migrateOnStart = migrate,
                database = DatabaseConfig.fromEnvironment(env)
            )
        }
    }
}
