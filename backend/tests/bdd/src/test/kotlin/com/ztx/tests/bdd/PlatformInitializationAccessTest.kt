package com.ztx.tests.bdd

import com.ztx.modules.iam.allowsLoopbackInitialization
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformInitializationAccessTest {
    @Test
    fun initializationRequiresAnActualLoopbackConnectionAndLoopbackHost() {
        // Given：请求确实来自 IPv4/IPv6 回环地址，Host 也指向回环地址
        assertTrue(allowsLoopbackInitialization("127.0.0.1", "localhost:8080", false))
        assertTrue(allowsLoopbackInitialization("::1", "[::1]:18080", false))

        // When：请求来自非回环地址，或试图通过代理头伪造来源
        // Then：即使 Host 看起来是本机地址，也必须拒绝初始化
        assertFalse(allowsLoopbackInitialization("192.0.2.10", "127.0.0.1:8080", false))
        assertFalse(allowsLoopbackInitialization("127.0.0.1", "localhost:8080", true))

        // When：Host 使用相似域名、伪造后缀或非法端口
        // Then：不能将其误判为本机访问
        assertFalse(allowsLoopbackInitialization("127.0.0.1", "localhost.example.com", false))
        assertFalse(allowsLoopbackInitialization("127.0.0.1", "127.0.0.1.attacker.test", false))
        assertFalse(allowsLoopbackInitialization("127.0.0.1", "[::1]attacker.test", false))
        assertFalse(allowsLoopbackInitialization("127.0.0.1", "localhost:invalid", false))
    }
}
