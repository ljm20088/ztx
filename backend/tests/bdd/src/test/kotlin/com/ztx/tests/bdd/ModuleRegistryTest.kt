package com.ztx.tests.bdd

import com.ztx.adapters.postgres.PostgresModule
import com.ztx.modules.document.DocumentRuntimeModule
import com.ztx.modules.iam.IamModule
import com.ztx.modules.metadata.MetadataModule
import com.ztx.platform.common.ModuleDescriptor
import com.ztx.platform.common.ModuleRegistry
import com.ztx.platform.common.PlatformModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModuleRegistryTest {
    @Test
    fun moduleDependenciesAreOrdered() {
        // Given：平台模块声明依赖关系，注册顺序不代表启动顺序
        val registry = ModuleRegistry()
            .register(DocumentRuntimeModule())
            .register(MetadataModule())
            .register(IamModule())
            .register(PostgresModule())
        // When：校验模块依赖并生成装配顺序
        val orderedIds = registry.validateAndOrder().map { it.id }
        // Then：依赖模块先于依赖它的模块
        assertEquals(listOf("postgres", "iam", "metadata", "document-runtime"), orderedIds)
    }

    @Test
    fun missingDependencyBlocksStartup() {
        // Given：模块声明了一个尚未注册的依赖
        val registry = ModuleRegistry().register(object : PlatformModule {
            override val descriptor = ModuleDescriptor("metadata", setOf("iam"))
        })
        // When / Then：装配校验失败并指出缺失依赖
        assertThrows(IllegalArgumentException::class.java) { registry.validateAndOrder() }
    }

    @Test
    fun cyclicDependencyBlocksStartup() {
        // Given：两个模块互相依赖
        val registry = ModuleRegistry()
            .register(object : PlatformModule {
                override val descriptor = ModuleDescriptor("a", setOf("b"))
            })
            .register(object : PlatformModule {
                override val descriptor = ModuleDescriptor("b", setOf("a"))
            })
        // When / Then：拓扑排序拒绝循环依赖
        assertThrows(IllegalArgumentException::class.java) { registry.validateAndOrder() }
    }
}
