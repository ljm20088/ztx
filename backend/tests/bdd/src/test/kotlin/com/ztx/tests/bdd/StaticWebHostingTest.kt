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

class StaticWebHostingTest {
    @Test
    fun servesFrontendAssetsAndFallsBackToVueHistoryRoutes() {
        // Given：Server classpath 中包含前端 webroot 测试资源
        val vertx = Vertx.vertx()
        val database = object : DatabaseHealth {
            override fun ping(): Future<Void> = Future.succeededFuture()
        }
        var server: io.vertx.core.http.HttpServer? = null

        try {
            server = HealthServer(vertx, database, "127.0.0.1", 0)
                .start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            val client = HttpClient.newHttpClient()
            fun get(path: String) = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.actualPort()}$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )

            // When：请求首页、Vue Router 深层页面、静态资源和不存在的 API
            val home = get("/")
            val loginPage = get("/platform/login")
            val asset = get("/assets/app.js")
            val missingApi = get("/api/v1/not-found")

            // Then：页面路由回退到 index，静态资源原样返回，未知 API 保持 JSON 404
            assertEquals(200, home.statusCode())
            assertTrue(home.body().contains("ZTX_STATIC_HOST_TEST"))
            assertEquals(200, loginPage.statusCode())
            assertTrue(loginPage.body().contains("ZTX_STATIC_HOST_TEST"))
            assertEquals(200, asset.statusCode())
            assertTrue(asset.body().contains("window.ztxStaticHostTest"))
            assertEquals(404, missingApi.statusCode())
            assertTrue(missingApi.body().contains("NOT_FOUND"))
        } finally {
            server?.close()?.toCompletionStage()?.toCompletableFuture()?.get(10, TimeUnit.SECONDS)
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }
}
