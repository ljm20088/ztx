package com.ztx.tests.bdd

import com.ztx.adapters.postgres.DatabaseHealth
import com.ztx.server.HealthServer
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

class HealthEndpointTest {
    @Test
    fun livenessRemainsUpWhenDatabaseIsUnavailable() {
        // Given：数据库健康检查失败，但 HTTP 进程仍然运行
        val vertx = Vertx.vertx()
        val unavailableDatabase = object : DatabaseHealth {
            override fun ping(): Future<Void> = Future.failedFuture("database unavailable")
        }
        var server: io.vertx.core.http.HttpServer? = null
        val previousVersion = System.getProperty("ztx.version")
        System.setProperty("ztx.version", "0.1.0-test")

        try {
            // When：启动健康检查端点并分别请求 liveness 和 readiness
            server = HealthServer(vertx, unavailableDatabase, "127.0.0.1", 0)
                .start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            val client = HttpClient.newHttpClient()
            val live = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort()}/health/live")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            val ready = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort()}/health/ready")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )

            // Then：liveness 不受数据库故障影响，readiness 明确返回 503
            assertEquals(200, live.statusCode())
            assertTrue(live.body().contains("\"status\":\"UP\""))
            assertTrue(live.body().contains("\"version\":\"0.1.0-test\""))
            assertEquals(503, ready.statusCode())
            assertTrue(ready.body().contains("\"database\":\"DOWN\""))
        } finally {
            server?.close()?.toCompletionStage()?.toCompletableFuture()?.get(10, TimeUnit.SECONDS)
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            if (previousVersion == null) System.clearProperty("ztx.version")
            else System.setProperty("ztx.version", previousVersion)
        }
    }
}
