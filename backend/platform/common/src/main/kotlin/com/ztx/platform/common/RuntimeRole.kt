package com.ztx.platform.common

enum class RuntimeRole {
    SERVER,
    WORKER;

    companion object {
        fun fromEnvironment(value: String?): RuntimeRole =
            when (value?.trim()?.lowercase()) {
                null, "", "server" -> SERVER
                "worker" -> WORKER
                else -> throw IllegalArgumentException("ZTX_APP_ROLE 仅支持 server 或 worker")
            }
    }
}
