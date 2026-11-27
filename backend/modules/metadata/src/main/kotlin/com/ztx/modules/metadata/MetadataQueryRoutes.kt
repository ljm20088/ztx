package com.ztx.modules.metadata

import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Tuple

/** 元数据列表查询入口；认证主体和租户数据库只取自全局认证上下文。 */
class MetadataQueryRoutes {
    fun register(router: Router) {
        router.route("/api/v1/tenant/query/*").handler(BodyHandler.create().setBodyLimit(128 * 1024))
        router.post("/api/v1/tenant/query/:doctype").handler(::query)
    }

    private fun query(context: RoutingContext) {
        val principal = context.get<TenantPrincipal>(TENANT_CONTEXT_KEY)
        if (principal == null) {
            fail(context, 401, "租户登录已失效")
            return
        }
        val request = context.body().asJsonObject() ?: JsonObject()
        val doctype = context.pathParam("doctype")
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT definition_json FROM meta.document_definition
                   WHERE scope='TENANT' AND doctype=$1 AND status='VALIDATED'
                   ORDER BY version DESC LIMIT 1"""
            ).execute(Tuple.of(doctype)).compose { definitions ->
                val row = definitions.firstOrNull() ?: return@compose Future.failedFuture<JsonObject>("DocType 不存在或尚未通过校验")
                val definition = row.getJsonObject("definition_json")
                val permissions = definition.getJsonObject("permissions")?.getJsonArray("read")
                if (permissions != null && permissions.isNotEmpty && permissions.none {
                    it == principal.role || it == "TENANT_ADMIN"
                }) return@compose Future.failedFuture<JsonObject>("无权读取此 DocType")
                val compiled = MetadataQueryEngine.compile(definition, request, principal.role, principal.companyIds)
                connection.preparedQuery(compiled.countSql).execute(Tuple.from(compiled.countParameters))
                    .compose { countRows ->
                        val total = countRows.first().getLong("total")
                        connection.preparedQuery(compiled.selectSql).execute(Tuple.from(compiled.selectParameters))
                            .map { rows ->
                                JsonObject().put("items", JsonArray(rows.map { it.toJson() }))
                                    .put("total", total)
                                    .put("page", request.getInteger("page", 1).coerceIn(1, 100000))
                                    .put("pageSize", request.getInteger("pageSize", 20).coerceIn(1, 200))
                            }
                    }
            }
        }.onSuccess { result ->
            context.response().putHeader("cache-control", "no-store")
                .putHeader("content-type", "application/json; charset=utf-8").end(result.encode())
        }.onFailure { error ->
            val message = error.message ?: "列表查询失败"
            val status = if (message.startsWith("无权")) 403 else if (error is IllegalArgumentException) 400 else 404
            fail(context, status, message)
        }
    }

    private fun fail(context: RoutingContext, status: Int, message: String) {
        if (context.response().ended()) return
        context.response().setStatusCode(status).putHeader("cache-control", "no-store")
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(JsonObject().put("error", JsonObject().put("code", "QUERY_REJECTED").put("message", message)).encode())
    }
}
