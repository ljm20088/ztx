package com.ztx.modules.iam

import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext

const val TENANT_CONTEXT_KEY = "ztx.tenant.context"

class TenantAuthRoutes(
    private val auth: TenantAuthService,
    private val lifecycle: TenantLifecycleService,
    private val secureCookies: Boolean
) {
    fun register(router: Router) {
        router.post("/api/v1/tenant/auth/setup").handler(::setup)
        router.post("/api/v1/tenant/auth/login").handler(::login)
        router.post("/api/v1/tenant/auth/logout").handler(::logout)
        router.get("/api/v1/tenant/auth/me").handler { context ->
            withTenant(context) { principal ->
                context.response().putHeader("cache-control", "no-store")
                    .putHeader("content-type", "application/json")
                    .end(toJson(principal).encode())
            }
        }
        router.post("/api/v1/tenant/auth/password").handler { context ->
            withTenant(context) { principal ->
                val body = body(context)
                val token = session(context)!!.second
                auth.changePassword(principal, token, body.getString("oldPassword", ""),
                    body.getString("newPassword", ""), ip(context))
                    .onSuccess { context.response().setStatusCode(204).end() }
                    .onFailure { fail(context, 400, "当前密码不正确或新密码不符合安全要求") }
            }
        }
    }

    /** 必须在业务 API 路由注册之前安装，避免新路由遗漏租户认证。 */
    fun registerGuard(router: Router) {
        router.route("/api/v1/*").handler { context ->
            val path = context.normalizedPath()
            if (path.startsWith("/api/v1/platform/") ||
                path == "/api/v1/tenant/auth/login" ||
                path == "/api/v1/tenant/auth/setup" ||
                path == "/api/v1/tenant/auth/logout" ||
                path == "/api/v1/tenant/setup") {
                context.next()
            } else {
                val cookie = session(context)
                if (cookie == null) {
                    fail(context, 401, "租户登录已失效")
                } else {
                    auth.authenticate(cookie.first, cookie.second).onSuccess { principal ->
                        if (principal == null) fail(context, 401, "租户登录已失效")
                        else {
                            context.put(TENANT_CONTEXT_KEY, principal)
                            context.next()
                        }
                    }.onFailure { fail(context, 401, "租户登录已失效") }
                }
            }
        }
    }

    private fun setup(context: RoutingContext) {
        val body = body(context)
        val code = body.getString("tenantCode", "")
        val username = body.getString("username", "")
        val password = body.getString("password", "")
        lifecycle.initializeTenantAdmin(code, body.getString("token", ""), username, password)
            .compose { auth.login(code, username, password, ip(context), context.request().getHeader("user-agent")) }
            .onSuccess { result ->
                context.response().setStatusCode(201).putHeader("set-cookie", cookie(code, result.sessionToken))
                    .putHeader("cache-control", "no-store").putHeader("content-type", "application/json")
                    .end(toJson(result.principal).encode())
            }.onFailure { fail(context, 400, "租户初始化凭据无效、已过期或账号信息不符合要求") }
    }

    private fun login(context: RoutingContext) {
        val body = body(context)
        val code = body.getString("tenantCode", "")
        auth.login(code, body.getString("username", ""), body.getString("password", ""),
            ip(context), context.request().getHeader("user-agent"))
            .onSuccess { result ->
                context.response().putHeader("set-cookie", cookie(code, result.sessionToken))
                    .putHeader("cache-control", "no-store").putHeader("content-type", "application/json")
                    .end(toJson(result.principal).encode())
            }.onFailure { fail(context, 401, "租户编码、账号或密码不正确") }
    }

    private fun logout(context: RoutingContext) {
        val current = session(context)
        val revoke = if (current == null) io.vertx.core.Future.succeededFuture<Void>()
            else auth.logout(current.first, current.second)
        revoke.onComplete {
            context.response().putHeader("set-cookie", clearCookie()).setStatusCode(204).end()
        }
    }

    private fun withTenant(context: RoutingContext, action: (TenantPrincipal) -> Unit) {
        val current = session(context)
        if (current == null) {
            fail(context, 401, "租户登录已失效")
            return
        }
        auth.authenticate(current.first, current.second).onSuccess { principal ->
            if (principal == null) fail(context, 401, "租户登录已失效")
            else {
                context.put(TENANT_CONTEXT_KEY, principal)
                action(principal)
            }
        }.onFailure { fail(context, 401, "租户登录已失效") }
    }

    /** Cookie 中的租户编码仅用于定位候选数据库；必须在该库内验证随机会话令牌。 */
    private fun session(context: RoutingContext): Pair<String, String>? {
        val value = context.request().getHeader("cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("ztx_tenant_session=") }?.substringAfter('=')
            ?: return null
        val code = value.substringBefore('.', "")
        val token = value.substringAfter('.', "")
        if (!code.matches(Regex("[a-z][a-z0-9_-]{1,39}")) ||
            !token.matches(Regex("[A-Za-z0-9_-]{40,64}"))) return null
        return code to token
    }

    private fun cookie(code: String, token: String): String =
        "ztx_tenant_session=$code.$token; Path=/api/v1/tenant; Max-Age=28800; HttpOnly; SameSite=Strict" +
            if (secureCookies) "; Secure" else ""

    private fun clearCookie(): String =
        "ztx_tenant_session=; Path=/api/v1/tenant; Max-Age=0; HttpOnly; SameSite=Strict" +
            if (secureCookies) "; Secure" else ""

    private fun body(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()
    private fun ip(context: RoutingContext): String? =
        context.request().remoteAddress()?.host()?.takeIf { it.matches(Regex("[0-9a-fA-F:.]+")) }

    private fun toJson(principal: TenantPrincipal) = JsonObject()
        .put("id", principal.userId.toString()).put("username", principal.username)
        .put("tenantId", principal.tenantId.toString()).put("tenantCode", principal.tenantCode)
        .put("role", principal.role)

    private fun fail(context: RoutingContext, status: Int, message: String) {
        if (context.response().ended()) return
        context.response().setStatusCode(status).putHeader("cache-control", "no-store")
            .putHeader("content-type", "application/json")
            .end(JsonObject().put("error", JsonObject()
                .put("code", if (status == 401) "TENANT_AUTH_REQUIRED" else "TENANT_AUTH_REJECTED")
                .put("message", message)).encode())
    }
}
