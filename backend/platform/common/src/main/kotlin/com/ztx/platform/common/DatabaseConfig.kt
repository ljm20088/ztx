package com.ztx.platform.common

class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val poolSize: Int,
    val migrationLocations: String
) {
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$database"

    override fun toString(): String =
        "DatabaseConfig(host=$host, port=$port, database=$database, user=$user, password=[REDACTED], poolSize=$poolSize, migrationLocations=$migrationLocations)"

    companion object {
        internal fun fromConfig(config: Map<String, Any?>): DatabaseConfig {
            fun string(name: String, default: String? = null): String {
                val value = config[name] ?: default
                require(value is String && value.isNotBlank()) { "database.$name 必须配置" }
                return value
            }
            fun int(name: String, default: Int): Int {
                val value = config[name] ?: default
                val parsed = value.toString().toIntOrNull()
                    ?: throw IllegalArgumentException("database.$name 必须是整数")
                return parsed
            }

            val port = int("port", 5432)
            val poolSize = int("pool-size", 10)
            require(port in 1..65535) { "database.port 必须在 1 到 65535 之间" }
            require(poolSize in 1..100) { "database.pool-size 必须在 1 到 100 之间" }
            return DatabaseConfig(
                host = string("host", "localhost"),
                port = port,
                database = string("name", "ztx"),
                user = string("user", "ztx"),
                password = string("password"),
                poolSize = poolSize,
                migrationLocations = string("migrations", "filesystem:database/migrations")
            )
        }
    }
}
