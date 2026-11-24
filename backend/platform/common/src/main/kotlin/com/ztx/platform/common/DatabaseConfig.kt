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
        fun fromEnvironment(env: Map<String, String>): DatabaseConfig {
            val password = env["ZTX_DB_PASSWORD"]
                ?: env["POSTGRES_PASSWORD"]
                ?: throw IllegalArgumentException("必须通过 ZTX_DB_PASSWORD 或 POSTGRES_PASSWORD 提供数据库密码")
            val port = env["ZTX_DB_PORT"]?.toIntOrNull() ?: 5432
            val poolSize = env["ZTX_DB_POOL_SIZE"]?.toIntOrNull() ?: 10
            require(port in 1..65535) { "ZTX_DB_PORT 必须在 1 到 65535 之间" }
            require(poolSize in 1..100) { "ZTX_DB_POOL_SIZE 必须在 1 到 100 之间" }
            return DatabaseConfig(
                host = env["ZTX_DB_HOST"] ?: "localhost",
                port = port,
                database = env["ZTX_DB_NAME"] ?: "ztx",
                user = env["ZTX_DB_USER"] ?: "ztx",
                password = password,
                poolSize = poolSize,
                migrationLocations = env["ZTX_DB_MIGRATIONS"] ?: "filesystem:database/migrations"
            )
        }
    }
}
