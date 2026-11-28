package com.ztx.modules.document

import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.UUID

class DocumentVersionConflictException : RuntimeException("单据已被其他用户修改，请刷新后重新编辑")

/** Document 通用 CRUD 与生命周期；权限、持久化和审计在同一租户数据库事务中执行。 */
class DocumentRuntimeRoutes(behaviors: DocumentBehaviorRegistry = DocumentBehaviorRegistry.load()) : DocumentRuntimeSupport(behaviors) {

    private val dbProvider = DbProvider(this)

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
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "create")
            val values = DocumentValueCodec.decode(definition, body(context))
            enforceCompanyScope(definition, values, principal, requireValue = true)
            val doctype = context.pathParam("doctype")
            val behavior = behaviors.resolve(definition, doctype)
            principal.database.withTransaction { connection ->
                val hookContext = DocumentBehaviorContext(doctype, definition, principal, connection)
                val beforeCreate = behavior?.beforeCreate(hookContext, values) ?: Future.succeededFuture()
                beforeCreate.compose {
                    insert(connection, definition, values, principal).compose { row ->
                        val id = UUID.fromString(row.getString("id"))
                        val afterContext = hookContext.copy(documentId = id, after = row)
                        (behavior?.afterCreate(afterContext, row) ?: Future.succeededFuture())
                            .compose {
                                audit(connection, principal, doctype, id, "CREATE", null, row).map { row }
                            }
                    }
                }
            }.onSuccess { send(context, redact(definition, principal, it), 201) }
                .onFailure { fail(context, status(it), it.message ?: "创建单据失败") }
        }.onFailure { fail(context, status(it), it.message ?: "创建单据失败") }
    }

    private fun read(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "read")
            val id = UUID.fromString(context.pathParam("id"))
            principal.database.withTransaction { connection ->
                selectOne(connection, definition, id, principal, lock = false, includeDeferred = false)
            }.onSuccess { send(context, redact(definition, principal, it)) }
                .onFailure { fail(context, status(it), it.message ?: "读取单据失败") }
        }.onFailure { fail(context, status(it), it.message ?: "读取单据失败") }
    }

    /** 按需读取被标记为延迟加载的大字段/附件，不把它们放进普通 Document 响应。 */
    private fun getField(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "read")
            val fieldName = context.pathParam("field")
            val field = (definition.getJsonArray("fields") ?: JsonArray())
                .filterIsInstance<JsonObject>().firstOrNull { it.getString("name") == fieldName }
                ?: return@compose Future.failedFuture<JsonObject>("DocType 未定义字段：$fieldName")
            if (!field.getBoolean("loadOnDemand", false)) {
                return@compose Future.failedFuture<JsonObject>("字段 $fieldName 不是延迟加载字段")
            }
            val roles = field.getJsonArray("readRoles")
            if (!canReadField(roles, principal.role)) {
                return@compose Future.failedFuture<JsonObject>("无权读取字段：$fieldName")
            }
            val id = UUID.fromString(context.pathParam("id"))
            principal.database.withTransaction { connection ->
                val parameters = mutableListOf<Any?>(id)
                val where = scopeWhere(definition, principal, parameters)
                val sql = "SELECT \"$fieldName\" FROM " + tableSql(definition) +
                    " WHERE \"id\"=$1" + where
                connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
                    val row = rows.firstOrNull() ?: throw IllegalArgumentException("单据不存在或无权访问")
                    JsonObject().put("field", fieldName).put("value", row.getValue(fieldName))
                }
            }
        }.onSuccess { send(context, it) }
            .onFailure { fail(context, status(it), it.message ?: "读取字段失败") }
    }

    private fun update(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "update")
            val id = UUID.fromString(context.pathParam("id"))
            val request = body(context)
            val expectedVersion = request.getInteger("version")
                ?: return@compose Future.failedFuture<JsonObject>("保存单据必须提供版本号")
            request.remove("version")
            val values = DocumentValueCodec.decode(definition, request, applyDefaults = false)
            enforceCompanyScope(definition, values, principal, requireValue = false)
            val doctype = context.pathParam("doctype")
            val behavior = behaviors.resolve(definition, doctype)
            principal.database.withTransaction { connection ->
                selectOne(connection, definition, id, principal, lock = true).compose { before ->
                    if (before.getInteger("version", 1) != expectedVersion) {
                        return@compose Future.failedFuture<JsonObject>(DocumentVersionConflictException())
                    }
                    if (before.getInteger("docstatus", 0) != DocumentStatus.DRAFT.code) {
                        return@compose Future.failedFuture<JsonObject>("只有草稿单据可以修改")
                    }
                    val hookContext = DocumentBehaviorContext(doctype, definition, principal, connection, id, before)
                    (behavior?.beforeUpdate(hookContext, before, values) ?: Future.succeededFuture())
                        .compose {
                            updateRow(connection, definition, id, values, principal, expectedVersion).compose { after ->
                                val afterContext = hookContext.copy(after = after)
                                (behavior?.afterUpdate(afterContext, before, after) ?: Future.succeededFuture())
                                    .compose {
                                        audit(connection, principal, doctype, id, "UPDATE", before, after).map { after }
                                    }
                            }
                        }
                }
            }.onSuccess { send(context, redact(definition, principal, it)) }
                .onFailure { fail(context, status(it), it.message ?: "更新单据失败") }
        }.onFailure { fail(context, status(it), it.message ?: "更新单据失败") }
    }

    private fun action(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            val action = context.pathParam("action")
            authorize(definition, principal, action)
            val doctype = context.pathParam("doctype")
            val behavior = behaviors.resolve(definition, doctype)
            val id = UUID.fromString(context.pathParam("id"))
            val expectedVersion = body(context).getInteger("version")
                ?: return@compose Future.failedFuture<JsonObject>("执行单据动作必须提供版本号")
            principal.database.withTransaction { connection ->
                selectOne(connection, definition, id, principal, lock = true).compose { before ->
                    if (before.getInteger("version", 1) != expectedVersion) {
                        return@compose Future.failedFuture<JsonObject>(DocumentVersionConflictException())
                    }
                    val hookContext = DocumentBehaviorContext(doctype, definition, principal, connection, id, before, action = action)
                    (behavior?.beforeAction(hookContext, before, action) ?: Future.succeededFuture())
                        .compose {
                            val next = DocumentLifecycle.transition(before.getInteger("docstatus", 0), action)
                            val sql = "UPDATE " + tableSql(definition) +
                                " SET \"docstatus\"=$1,\"version\"=\"version\"+1,\"updated_at\"=clock_timestamp()" +
                                " WHERE \"id\"=$2 AND \"docstatus\"=$3 AND \"version\"=$4 RETURNING *"
                            connection.preparedQuery(sql).execute(Tuple.of(next, id, before.getInteger("docstatus", 0), expectedVersion))
                                .compose { rows ->
                                    val after = rows.firstOrNull()?.toJson()
                                        ?: return@compose Future.failedFuture<JsonObject>("单据状态已变化，请刷新后重试")
                                    val afterContext = hookContext.copy(after = after)
                                    (behavior?.afterAction(afterContext, before, after, action) ?: Future.succeededFuture())
                                        .compose {
                                            audit(connection, principal, doctype, id, action.uppercase(), before, after).map { after }
                                        }
                                }
                        }
                }
            }.onSuccess { send(context, redact(definition, principal, it)) }
                .onFailure { fail(context, status(it), it.message ?: "单据动作执行失败") }
        }.onFailure { fail(context, status(it), it.message ?: "读取元数据失败") }
    }

    private fun runtimeDefinition(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).onSuccess { definition ->
            try {
                authorize(definition, principal, "read")
                send(context, JsonObject().put("doctype", context.pathParam("doctype")).put("definition", definition))
            } catch (error: Throwable) {
                fail(context, status(error), error.message ?: "无权读取 DocType")
            }
        }.onFailure { fail(context, status(it), it.message ?: "读取运行时元数据失败") }
    }

    private fun loadDefinition(principal: TenantPrincipal, doctype: String): Future<JsonObject> =
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT definition_json FROM meta.document_definition
                   WHERE scope='TENANT' AND doctype=$1 AND status='VALIDATED'
                   ORDER BY version DESC LIMIT 1"""
            ).execute(Tuple.of(doctype)).compose { rows ->
                val definition = rows.firstOrNull()?.getJsonObject("definition_json")
                    ?: return@compose Future.failedFuture<JsonObject>("DocType 不存在或尚未通过校验")
                validateScopeFields(connection, definition).map { definition }
            }
        }

    private fun withPrincipal(context: RoutingContext, action: (TenantPrincipal) -> Unit) {
        val principal = context.get<TenantPrincipal>(TENANT_CONTEXT_KEY)
        if (principal == null) fail(context, 401, "租户登录已失效") else action(principal)
    }

    private fun body(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

    private fun send(context: RoutingContext, value: JsonObject, status: Int = 200) {
        context.response().setStatusCode(status).putHeader("cache-control", "no-store")
            .putHeader("content-type", "application/json; charset=utf-8").end(value.encode())
    }

    /** 字段白名单按当前用户角色匹配；TENANT_ADMIN 是特权用户角色，不是授予所有人的通配符。 */
    fun canReadField(roles: JsonArray?, role: String): Boolean =
        roles == null || roles.isEmpty || role == "TENANT_ADMIN" || roles.any { it == role }

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
