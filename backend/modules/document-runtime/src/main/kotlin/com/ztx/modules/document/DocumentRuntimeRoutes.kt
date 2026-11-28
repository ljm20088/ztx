package com.ztx.modules.document

import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import java.util.UUID

/** Document 通用 CRUD 与生命周期；权限、持久化和审计在同一租户数据库事务中执行。 */
class DocumentRuntimeRoutes(behaviors: DocumentBehaviorRegistry = DocumentBehaviorRegistry.load()) {

    private val runtime = DocumentRuntimeSupport(behaviors)
    private val dbProvider = DbProvider(runtime)

    fun register(router: Router) {
        router.route("/api/v1/tenant/documents/*").handler(BodyHandler.create().setBodyLimit(1024 * 1024))
        router.get("/api/v1/tenant/runtime/doctypes/:doctype").handler(::runtimeDefinition)
        router.post("/api/v1/tenant/documents/:doctype").handler(::create)
        router.get("/api/v1/tenant/documents/:doctype/:id").handler(::read)
        router.get("/api/v1/tenant/documents/:doctype/:id/fields/:field").handler(::getField)
        router.put("/api/v1/tenant/documents/:doctype/:id").handler(::update)
        router.post("/api/v1/tenant/documents/:doctype/:id/actions/:action").handler(::action)
    }

    private fun create(context: RoutingContext) = withPrincipal(context) { principal ->
        val doctype = context.pathParam("doctype")
        dbProvider.transaction(principal) { db ->
            db.new(doctype).compose { document ->
                val values = DocumentValueCodec.decode(document.metadata(), body(context))
                values.fieldNames().forEach { field -> document[field] = values.getValue(field) }
                db.insert(document)
            }
        }.onSuccess { send(context, documentJson(it), 201) }
            .onFailure { fail(context, status(it), it.message ?: "创建单据失败") }
    }

    private fun read(context: RoutingContext) = withPrincipal(context) { principal ->
        val doctype = context.pathParam("doctype")
        val id = UUID.fromString(context.pathParam("id"))
        dbProvider.transaction(principal) { db -> db.get(doctype, id) }
            .onSuccess { send(context, documentJson(it)) }
            .onFailure { fail(context, status(it), it.message ?: "读取单据失败") }
    }

    /** 按需读取被标记为延迟加载的大字段/附件，不把它们放进普通 Document 响应。 */
    private fun getField(context: RoutingContext) = withPrincipal(context) { principal ->
        val doctype = context.pathParam("doctype")
        val id = UUID.fromString(context.pathParam("id"))
        val field = context.pathParam("field")
        dbProvider.transaction(principal) { db ->
            db.getValue(doctype, id, field).map { JsonObject().put("field", field).put("value", it) }
        }.onSuccess { send(context, it) }
            .onFailure { fail(context, status(it), it.message ?: "读取字段失败") }
    }

    private fun update(context: RoutingContext) = withPrincipal(context) { principal ->
        val doctype = context.pathParam("doctype")
        val id = UUID.fromString(context.pathParam("id"))
        val request = body(context)
        val expectedVersion = request.getInteger("version")
            ?: return@withPrincipal fail(context, 400, "保存单据必须提供版本号")
        request.remove("version")
        dbProvider.transaction(principal) { db ->
            db.get(doctype, id).compose { document ->
                if (document.version != expectedVersion) {
                    return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                }
                val values = DocumentValueCodec.decode(document.metadata(), request, applyDefaults = false)
                values.fieldNames().forEach { field -> document[field] = values.getValue(field) }
                db.update(document)
            }
        }.onSuccess { send(context, documentJson(it)) }
            .onFailure { fail(context, status(it), it.message ?: "更新单据失败") }
    }

    private fun action(context: RoutingContext) = withPrincipal(context) { principal ->
        val doctype = context.pathParam("doctype")
        val id = UUID.fromString(context.pathParam("id"))
        val action = context.pathParam("action")
        val expectedVersion = body(context).getInteger("version")
            ?: return@withPrincipal fail(context, 400, "执行单据动作必须提供版本号")
        dbProvider.transaction(principal) { db ->
            db.get(doctype, id).compose { document ->
                if (document.version != expectedVersion) {
                    return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                }
                db.action(document, action)
            }
        }.onSuccess { send(context, documentJson(it)) }
            .onFailure { fail(context, status(it), it.message ?: "单据动作执行失败") }
    }

    private fun runtimeDefinition(context: RoutingContext) = withPrincipal(context) { principal ->
        val doctype = context.pathParam("doctype")
        dbProvider.transaction(principal) { db -> db.definition(doctype) }
            .onSuccess { send(context, JsonObject().put("doctype", doctype).put("definition", it)) }
            .onFailure { fail(context, status(it), it.message ?: "读取运行时元数据失败") }
    }

    private fun documentJson(document: DynamicDocument): JsonObject =
        document.toJson()
            .put("id", document.id?.toString())
            .put("version", document.version)
            .put("docstatus", document.docstatus)

    private fun withPrincipal(context: RoutingContext, action: (TenantPrincipal) -> Unit) {
        val principal = context.get<TenantPrincipal>(TENANT_CONTEXT_KEY)
        if (principal == null) fail(context, 401, "租户登录已失效") else action(principal)
    }

    private fun body(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

    private fun send(context: RoutingContext, value: JsonObject, status: Int = 200) {
        context.response().setStatusCode(status).putHeader("cache-control", "no-store")
            .putHeader("content-type", "application/json; charset=utf-8").end(value.encode())
    }

    private fun status(error: Throwable): Int = when {
        error.message?.contains("无权") == true -> 403
        error is DocumentVersionConflictException -> 409
        error is IllegalArgumentException -> 400
        else -> 500
    }

    private fun fail(context: RoutingContext, status: Int, message: String) {
        if (context.response().ended()) return
        context.response().setStatusCode(status).putHeader("cache-control", "no-store")
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(JsonObject().put("error", JsonObject().put("code", "DOCUMENT_REJECTED").put("message", message)).encode())
    }
}
