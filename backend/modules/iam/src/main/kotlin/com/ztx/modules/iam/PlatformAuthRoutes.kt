package com.ztx.modules.iam

import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import java.net.InetAddress

class PlatformAuthRoutes(
    private val service: PlatformAuthService,
    private val secureCookies: Boolean
) {
    fun register(router: Router) {
        router.route("/api/v1/platform/auth/*").handler(BodyHandler.create().setBodyLimit(16 * 1024))
        router.get("/api/v1/platform/auth/status").handler(::status)
        router.post("/api/v1/platform/auth/initialize").handler(::initialize)
        router.post("/api/v1/platform/auth/login").handler(::login)
        router.post("/api/v1/platform/auth/logout").handler(::logout)
        router.get("/api/v1/platform/auth/me").handler(::me)
        router.post("/api/v1/platform/auth/password").handler(::changePassword)
        router.post("/api/v1/platform/auth/mfa/enroll").handler(::enrollMfa)
        router.post("/api/v1/platform/auth/mfa/enable").handler(::enableMfa)
    }

    private fun status(context: RoutingContext) {
        service.initializationStatus().onSuccess { initialized ->
            context.response().putHeader("cache-control", "no-store")
                .putHeader("content-type", "application/json")
                .end(JsonObject().put("initialized", initialized)
                    .put("canInitialize", !initialized && isLoopbackInitializationRequest(context)).encode())
        }.onFailure { fail(context, it, 503) }
    }

    private fun initialize(context: RoutingContext) {
        if (!isLoopbackInitializationRequest(context)) {
            context.response().setStatusCode(403).putHeader("cache-control", "no-store").end()
            return
        }
        val body = AuthSupport.body(context)
        service.initialize(body.getString("username", ""), body.getString("password", ""),
            AuthSupport.ip(context), context.request().getHeader("user-agent"))
            .onSuccess { result ->
                context.response().setStatusCode(201).putHeader("set-cookie", AuthSupport.cookie("ztx_platform_session", result.sessionToken, "/api/v1/platform", secureCookies))
                    .putHeader("content-type", "application/json")
                    .end(JsonObject().put("id", result.admin.id.toString())
                        .put("username", result.admin.username).put("mfaEnabled", result.admin.mfaEnabled).encode())
            }.onFailure { fail(context, it, 409) }
    }

    /**
     * 不信任 X-Forwarded-* 等代理头。SSH 本地端口转发和服务器本机访问的 Host 必须是 loopback。
     * 反向代理应显式拒绝转发初始化请求，不能把公网流量伪装成 loopback。
     */
    private fun isLoopbackInitializationRequest(context: RoutingContext): Boolean {
        val remoteHost = context.request().remoteAddress()?.host()
        val hostHeader = context.request().getHeader("host")?.lowercase().orEmpty()
        val localHost = hostHeader == "::1" || hostHeader.startsWith("[::1]") ||
            hostHeader.substringBefore(':') in setOf("localhost", "127.0.0.1")
        val forwarded = context.request().getHeader("forwarded") != null ||
            context.request().getHeader("x-forwarded-for") != null ||
            context.request().getHeader("x-real-ip") != null
        val isLoopback = remoteHost != null && runCatching {
            InetAddress.getByName(remoteHost).isLoopbackAddress
        }.getOrDefault(false)
        return isLoopback && localHost && !forwarded
    }

    private fun login(context: RoutingContext) {
        val body = AuthSupport.body(context)
        service.login(body.getString("username", ""), body.getString("password", ""),
            body.getString("totp"), AuthSupport.ip(context), context.request().getHeader("user-agent"))
            .onSuccess { result ->
                context.response().putHeader("set-cookie", AuthSupport.cookie("ztx_platform_session", result.sessionToken, "/api/v1/platform", secureCookies))
                    .putHeader("content-type", "application/json")
                    .end(JsonObject().put("id", result.admin.id.toString())
                        .put("username", result.admin.username).put("mfaEnabled", result.admin.mfaEnabled).encode())
            }.onFailure { fail(context, it, 401) }
    }

    private fun logout(context: RoutingContext) {
        val token = sessionToken(context)
        if (token == null) {
            context.response().putHeader("set-cookie", AuthSupport.clearCookie("ztx_platform_session", "/api/v1/platform", secureCookies)).setStatusCode(204).end()
            return
        }
        service.logout(token, AuthSupport.ip(context)).onSuccess {
            context.response().putHeader("set-cookie", AuthSupport.clearCookie("ztx_platform_session", "/api/v1/platform", secureCookies)).setStatusCode(204).end()
        }.onFailure { fail(context, it, 500) }
    }

    private fun me(context: RoutingContext) = withAdmin(context) { admin ->
        context.response().putHeader("content-type", "application/json")
            .end(JsonObject().put("id", admin.id.toString()).put("username", admin.username)
                .put("mfaEnabled", admin.mfaEnabled).encode())
    }

    private fun changePassword(context: RoutingContext) = withAdmin(context) { admin ->
        val body = AuthSupport.body(context)
        service.changePassword(admin, body.getString("oldPassword", ""), body.getString("newPassword", ""),
            sessionToken(context)!!, AuthSupport.ip(context))
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context, it, 400) }
    }

    private fun enrollMfa(context: RoutingContext) = withAdmin(context) { admin ->
        service.enrollMfa(admin).onSuccess { enrollment ->
            context.response().putHeader("content-type", "application/json")
                .end(JsonObject().put("secret", enrollment.secret).put("otpauthUri", enrollment.otpauthUri).encode())
        }.onFailure { fail(context, it, 503) }
    }

    private fun enableMfa(context: RoutingContext) = withAdmin(context) { admin ->
        service.enableMfa(admin, AuthSupport.body(context).getString("code", ""), AuthSupport.ip(context))
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context, it, 400) }
    }

    private fun withAdmin(context: RoutingContext, action: (PlatformAdmin) -> Unit) {
        val token = sessionToken(context)
        if (token == null) {
            fail(context, IllegalStateException("未登录"), 401)
            return
        }
        service.authenticate(token).onSuccess { admin ->
            if (admin == null) fail(context, IllegalStateException("会话已失效"), 401) else action(admin)
        }.onFailure { fail(context, it, 500) }
    }

    private fun sessionToken(context: RoutingContext): String? =
        context.request().getHeader("cookie")?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("ztx_platform_session=") }
            ?.substringAfter('=')?.takeIf { it.isNotBlank() }

    private fun fail(context: RoutingContext, error: Throwable, status: Int) {
        if (context.response().ended()) return
        val message = if (status >= 500) "认证服务暂不可用" else (error.message ?: "认证请求被拒绝")
        context.response().setStatusCode(status).putHeader("content-type", "application/json")
            .end(JsonObject().put("error", JsonObject()
                .put("code", if (status >= 500) "PLATFORM_AUTH_UNAVAILABLE" else "PLATFORM_AUTH_REJECTED")
                .put("message", message)).encode())
    }
}
