package com.ztx.platform.common

/**
 * 轻量模块装配表：启动时校验模块标识、依赖存在性和循环依赖。
 * 不引入 DI 容器；对象由应用入口显式创建和组合。
 */
class ModuleRegistry {
    private val modules = linkedMapOf<String, PlatformModule>()

    fun register(module: PlatformModule): ModuleRegistry {
        val id = module.descriptor.id
        require(modules.putIfAbsent(id, module) == null) { "重复注册平台模块：$id" }
        return this
    }

    fun validateAndOrder(): List<ModuleDescriptor> {
        modules.values.forEach { module ->
            val missing = module.descriptor.dependencies - modules.keys
            require(missing.isEmpty()) {
                "模块 ${module.descriptor.id} 缺少依赖：${missing.sorted().joinToString()}"
            }
        }

        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val ordered = mutableListOf<ModuleDescriptor>()

        fun visit(id: String) {
            require(id !in visiting) { "检测到模块循环依赖：${(visiting + id).joinToString(" -> ")}" }
            if (id in visited) return
            visiting += id
            modules.getValue(id).descriptor.dependencies.sorted().forEach(::visit)
            visiting -= id
            visited += id
            ordered += modules.getValue(id).descriptor
        }

        modules.keys.forEach(::visit)
        return ordered
    }
}
