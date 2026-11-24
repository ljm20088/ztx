package com.ztx.platform.common

data class ModuleDescriptor(
    val id: String,
    val dependencies: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "模块标识不能为空" }
        require(dependencies.none(String::isBlank)) { "模块依赖标识不能为空" }
        require(id !in dependencies) { "模块不能依赖自身：$id" }
    }
}

interface PlatformModule {
    val descriptor: ModuleDescriptor
}
