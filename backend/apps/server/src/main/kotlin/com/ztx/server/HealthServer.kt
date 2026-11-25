package com.ztx.server

import com.ztx.adapters.postgres.DatabaseHealth
import com.ztx.modules.iam.PlatformAuthRoutes
import com.ztx.modules.iam.TenantLifecycleRoutes
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.StaticHandler

class HealthServer(
    private val vertx: Vertx,
    private val database: DatabaseHealth,
    private val host: String,
    private val port: Int,
    private val platformAuthRoutes: PlatformAuthRoutes? = null,
    private val tenantLifecycleRoutes: TenantLifecycleRoutes? = null
) {
    fun start(): Future<HttpServer> {
        val router = Router.router(vertx)
        platformAuthRoutes?.register(router)
        tenantLifecycleRoutes?.register(router)

        router.route().failureHandler { context ->
            val status = context.statusCode().takeIf { it in 400..599 } ?: 500
            val code = if (status >= 500) "INTERNAL_ERROR" else "REQUEST_FAILED"
            val message = if (status >= 500) "服务暂时不可用" else "请求无法处理"
            context.response().setStatusCode(status)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(JsonObject().put("error", JsonObject().put("code", code).put("message", message)).encode())
        }
        router.get("/health/live").handler { context ->
            context.response().putHeader("content-type", "application/json; charset=utf-8")
                .end(JsonObject().put("status", "UP").put("service", "ztx-server").encode())
        }
        router.get("/health/ready").handler { context ->
            database.ping().onSuccess {
                context.response().setStatusCode(200).putHeader("content-type", "application/json; charset=utf-8")
                    .end(JsonObject().put("status", "READY")
                        .put("components", JsonObject().put("database", "UP")).encode())
            }.onFailure {
                context.response().setStatusCode(503).putHeader("content-type", "application/json; charset=utf-8")
                    .end(JsonObject().put("status", "NOT_READY")
                        .put("components", JsonObject().put("database", "DOWN")).encode())
            }
        }
        router.route("/api/*").handler { context ->
            context.response().setStatusCode(404)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(JsonObject().put("error", JsonObject().put("code", "NOT_FOUND")
                    .put("message", "接口不存在")).encode())
        }
        router.route("/:section").handler { context ->
            if (context.normalizedPath().substringAfterLast('/').contains('.')) context.next()
            else context.reroute("/")
        }
        router.route("/:section/:feature").handler { context ->
            if (context.normalizedPath().substringAfterLast('/').contains('.')) context.next()
            else context.reroute("/")
        }
        router.route().handler(StaticHandler.create("webroot").setIndexPage("index.html"))
        return vertx.createHttpServer().requestHandler(router).listen(port, host)
    }
}
