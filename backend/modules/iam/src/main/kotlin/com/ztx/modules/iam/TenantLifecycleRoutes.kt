package com.ztx.modules.iam

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import java.util.UUID

class TenantLifecycleRoutes(
    private val authService: PlatformAuthService,
    private val tenantService: TenantLifecycleService
) {
    fun register(router: Router) {
        router.route("/api/v1/platform/tenants/*").handler(BodyHandler.create().setBodyLimit(16 * 1024))
        router.route("/api/v1/tenant/*").handler(BodyHandler.create().setBodyLimit(16 * 1024))
        router.post("/api/v1/tenant/setup").handler { context ->
            val body = context.body().asJsonObject() ?: JsonObject()
            tenantService.initializeTenantAdmin(
                body.getString("tenantCode", ""), body.getString("token", ""),
                body.getString("username", ""), body.getString("password", "")
            ).onSuccess {
                context.response().setStatusCode(201).end()
            }.onFailure { fail(context, 400) }
        }
        router.get("/api/v1/platform/tenants").handler { context ->
            withAdmin(context) {
                tenantService.list().onSuccess { tenants ->
                    val items = JsonArray()
                    tenants.forEach { items.add(toJson(it)) }
                    context.response().putHeader("content-type", "application/json")
                        .end(JsonObject().put("items", items).encode())
                }.onFailure { fail(context, 503) }
            }
        }
        router.post("/api/v1/platform/tenants").handler { context ->
            withAdmin(context) {
                val body = context.body().asJsonObject() ?: JsonObject()
                tenantService.create(body.getString("code", ""), body.getString("name", ""), it)
                    .onSuccess { result ->
                        context.response().setStatusCode(201).putHeader("content-type", "application/json")
                            .end(JsonObject().put("tenant", toJson(result.tenant))
                                .put("setupToken", result.setupToken).encode())
                    }.onFailure { fail(context, 400) }
            }
        }
        router.post("/api/v1/platform/tenants/:id/enable").handler { context ->
            withTenantAdmin(context) { admin, id ->
                tenantService.enable(id, admin).onSuccess {
                    context.response().putHeader("content-type", "application/json").end(toJson(it).encode())
                }.onFailure { fail(context, 409) }
            }
        }
        router.post("/api/v1/platform/tenants/:id/disable").handler { context ->
            withTenantAdmin(context) { admin, id ->
                tenantService.disable(id, admin).onSuccess {
                    context.response().putHeader("content-type", "application/json").end(toJson(it).encode())
                }.onFailure { fail(context, 409) }
            }
        }
        router.post("/api/v1/platform/tenants/:id/retry").handler { context ->
            withTenantAdmin(context) { admin, id ->
                tenantService.retry(id, admin).onSuccess { result ->
                    context.response().putHeader("content-type", "application/json")
                        .end(JsonObject().put("tenant", toJson(result.tenant))
                            .put("setupToken", result.setupToken).encode())
                }.onFailure { fail(context, 409) }
            }
        }
    }

    private fun withAdmin(context: RoutingContext, action: (PlatformAdmin) -> Unit) {
        val token = context.request().getHeader("cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("ztx_platform_session=") }?.substringAfter('=')
        if (token.isNullOrBlank()) {
            fail(context, 401)
            return
        }
        authService.authenticate(token).onSuccess { admin ->
            if (admin == null) fail(context, 401) else action(admin)
        }.onFailure { fail(context, 503) }
    }

    private fun withTenantAdmin(context: RoutingContext, action: (PlatformAdmin, UUID) -> Unit) =
        withAdmin(context) { admin ->
            val id = runCatching { UUID.fromString(context.pathParam("id")) }.getOrNull()
            if (id == null) fail(context, 400) else action(admin, id)
        }

    private fun toJson(tenant: TenantRecord) = JsonObject()
        .put("id", tenant.id.toString()).put("code", tenant.code).put("name", tenant.name)
        .put("status", tenant.status).put("databaseName", tenant.databaseName)
        .put("lastError", tenant.lastError)

    private fun fail(context: RoutingContext, status: Int) {
        if (context.response().ended()) return
        val message = when (status) {
            401 -> "未登录或会话已失效"
            400 -> "租户请求参数不正确"
            409 -> "租户状态不允许此操作"
            else -> "租户服务暂不可用"
        }
        context.response().setStatusCode(status).putHeader("content-type", "application/json")
            .end(JsonObject().put("error", JsonObject()
                .put("code", "TENANT_REQUEST_FAILED").put("message", message)).encode())
    }
}
