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
class DocumentRuntimeRoutes(
    internal val behaviors: DocumentBehaviorRegistry = DocumentBehaviorRegistry.load()
) {
    private val identifier = Regex("[a-z][a-z0-9_]{0,62}")
    private val protectedSchemas = setOf("public", "platform", "iam", "meta", "audit", "pg_catalog", "information_schema")

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
            if (roles != null && !roles.isEmpty && roles.none { it == principal.role || it == "TENANT_ADMIN" }) {
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

    internal fun loadDefinition(connection: SqlConnection, doctype: String): Future<JsonObject> =
        connection.preparedQuery(
            """SELECT definition_json FROM meta.document_definition
               WHERE scope='TENANT' AND doctype=$1 AND status='VALIDATED'
               ORDER BY version DESC LIMIT 1"""
        ).execute(Tuple.of(doctype)).compose { rows ->
            val definition = rows.firstOrNull()?.getJsonObject("definition_json")
                ?: return@compose Future.failedFuture<JsonObject>("DocType 不存在或尚未通过校验")
            validateScopeFields(connection, definition).map { definition }
        }

    internal fun validateScopeFields(connection: SqlConnection, definition: JsonObject): Future<Void> {
        val table = definition.getJsonObject("table") ?: return Future.failedFuture("缺少物理表映射")
        tableSql(definition)
        return connection.preparedQuery(
            """SELECT column_name FROM information_schema.columns
               WHERE table_schema=$1 AND table_name=$2
                 AND column_name = ANY($3::text[])"""
        ).execute(Tuple.of(table.getString("schema"), table.getString("name"),
            arrayOf("company_id", "book_id", "organization_unit_id", "version"))).map { columns ->
            val physical = columns.map { it.getString("column_name") }.toSet()
            if ("version" !in physical) throw IllegalArgumentException("物理表必须包含系统版本列 version")
            val declared = (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>()
                .mapNotNull { it.getString("name") }.toSet()
            val missing = (physical - setOf("version")) - declared
            if (missing.isNotEmpty()) throw IllegalArgumentException(
                "DocType 必须声明物理表的数据范围字段：" + missing.sorted().joinToString()
            )
        }.mapEmpty()
    }

    internal fun insert(
        connection: SqlConnection,
        definition: JsonObject,
        values: JsonObject,
        principal: TenantPrincipal
    ): Future<JsonObject> {
        val id = UUID.randomUUID()
        val entries = writableEntries(definition, values, principal)
        val columns = mutableListOf("\"id\"", "\"docstatus\"", "\"version\"")
        val parameters = mutableListOf<Any?>(id, DocumentStatus.DRAFT.code, 1)
        val placeholders = mutableListOf("$1", "$2", "$3")
        entries.forEach { (name, value, json) ->
            columns += "\"$name\""
            parameters += if (json && value != null) encodeJson(value) else value
            val marker = "$" + parameters.size
            placeholders += if (json) marker + "::jsonb" else marker
        }
        val sql = "INSERT INTO " + tableSql(definition) + " (" + columns.joinToString(",") +
            ") VALUES (" + placeholders.joinToString(",") + ") RETURNING *"
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalStateException("单据写入失败")
        }
    }

    internal fun updateRow(
        connection: SqlConnection,
        definition: JsonObject,
        id: UUID,
        values: JsonObject,
        principal: TenantPrincipal,
        expectedVersion: Int
    ): Future<JsonObject> {
        val entries = writableEntries(definition, values, principal, includeNulls = true)
        if (entries.isEmpty()) return Future.failedFuture("没有可更新字段")
        val parameters = mutableListOf<Any?>()
        val assignments = entries.map { (name, value, json) ->
            parameters += if (json && value != null) encodeJson(value) else value
            val marker = "$" + parameters.size
            "\"$name\"=" + marker + if (json) "::jsonb" else ""
        }.toMutableList()
        assignments += "\"version\"=\"version\"+1"
        assignments += "\"updated_at\"=clock_timestamp()"
        parameters += id
        val idIndex = parameters.size
        parameters += expectedVersion
        val versionIndex = parameters.size
        val where = scopeWhere(definition, principal, parameters)
        val sql = "UPDATE " + tableSql(definition) + " SET " + assignments.joinToString(",") +
            " WHERE \"id\"=$" + idIndex + " AND \"version\"=$" + versionIndex + " AND \"docstatus\"=0" + where + " RETURNING *"
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw DocumentVersionConflictException()
        }
    }

    internal fun documentProjection(definition: JsonObject, includeDeferred: Boolean): String {
        val system = listOf("id", "docstatus", "version", "created_at", "updated_at")
        val fields = (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>()
            .filter { includeDeferred || !it.getBoolean("loadOnDemand", false) }
            .mapNotNull { it.getString("name") }
        return (system + fields).distinct().joinToString(", ") { "\"$it\"" }
    }

    internal fun selectOne(
        connection: SqlConnection,
        definition: JsonObject,
        id: UUID,
        principal: TenantPrincipal,
        lock: Boolean,
        includeDeferred: Boolean = true
    ): Future<JsonObject> {
        val parameters = mutableListOf<Any?>(id)
        val where = scopeWhere(definition, principal, parameters)
        val sql = "SELECT " + documentProjection(definition, includeDeferred) +
            " FROM " + tableSql(definition) + " WHERE \"id\"=$1" + where +
            if (lock) " FOR UPDATE" else ""
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalArgumentException("单据不存在或无权访问")
        }
    }

    internal fun scopeWhere(
        definition: JsonObject,
        principal: TenantPrincipal,
        parameters: MutableList<Any?>
    ): String {
        val names = (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>()
            .mapNotNull { it.getString("name") }.toSet()
        val conditions = mutableListOf<String>()
        fun addScope(field: String, ids: Set<UUID>) {
            if (field !in names) return
            if (ids.isEmpty()) conditions += "FALSE" else {
                parameters.add(ids.toTypedArray())
                conditions += "\"$field\" = ANY($" + parameters.size + "::uuid[])"
            }
        }
        addScope("company_id", principal.companyIds)
        addScope("book_id", principal.bookIds)
        addScope("organization_unit_id", principal.organizationUnitIds)
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " AND ") ?: ""
    }

    internal fun enforceCompanyScope(
        definition: JsonObject,
        values: JsonObject,
        principal: TenantPrincipal,
        requireValue: Boolean
    ) {
        val names = (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>()
            .mapNotNull { it.getString("name") }.toSet()
        if ("company_id" in names) {
            val company = values.getValue("company_id")?.let { UUID.fromString(it.toString()) }
            if (company == null && requireValue) throw IllegalArgumentException("必须指定所属企业")
            if (company != null && company !in principal.companyIds) throw IllegalArgumentException("无权访问指定企业的数据")
        }
        val book = values.getValue("book_id")?.let { UUID.fromString(it.toString()) }
        if (book != null && book !in principal.bookIds) throw IllegalArgumentException("无权访问指定账套的数据")
        val organization = values.getValue("organization_unit_id")?.let { UUID.fromString(it.toString()) }
        if (organization != null && organization !in principal.organizationUnitIds) throw IllegalArgumentException("无权访问指定组织的数据")
    }

    internal fun writableEntries(
        definition: JsonObject,
        values: JsonObject,
        principal: TenantPrincipal,
        includeNulls: Boolean = false
    ): List<Triple<String, Any?, Boolean>> {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        return fields.filterIsInstance<JsonObject>().mapNotNull { field ->
            val name = field.getString("name") ?: return@mapNotNull null
            if (name in setOf("id", "docstatus", "created_at", "updated_at") || field.getBoolean("readOnly", false)) {
                return@mapNotNull null
            }
            if (!values.containsKey(name)) return@mapNotNull null
            val value = values.getValue(name)
            if (value == null && !includeNulls) return@mapNotNull null
            val writeRoles = field.getJsonArray("writeRoles")
            if (writeRoles != null && !writeRoles.isEmpty && writeRoles.none {
                it == principal.role || it == "TENANT_ADMIN"
            }) throw IllegalArgumentException("无权修改字段：$name")
            val type = field.getString("type")
            Triple(name, value, type == "json" || type == "table")
        }
    }

    internal fun encodeJson(value: Any): String = when (value) {
        is JsonObject -> value.encode()
        is JsonArray -> value.encode()
        else -> throw IllegalArgumentException("JSON/明细字段必须是对象或数组")
    }

    internal fun tableSql(definition: JsonObject): String {
        val table = definition.getJsonObject("table") ?: throw IllegalArgumentException("缺少表映射")
        val schema = table.getString("schema", "")
        val name = table.getString("name", "")
        if (!identifier.matches(schema) || !identifier.matches(name) || schema in protectedSchemas) {
            throw IllegalArgumentException("物理表映射不允许使用")
        }
        return "\"$schema\".\"$name\""
    }

    internal fun authorize(definition: JsonObject, principal: TenantPrincipal, action: String) {
        val roles = definition.getJsonObject("permissions")?.getJsonArray(action)
        if (roles == null || roles.isEmpty) {
            if (principal.role != "TENANT_ADMIN") throw IllegalArgumentException("无权执行单据动作：$action")
        } else if (roles.none { it == principal.role || it == "TENANT_ADMIN" }) {
            throw IllegalArgumentException("无权执行单据动作：$action")
        }
    }

    internal fun redact(definition: JsonObject, principal: TenantPrincipal, row: JsonObject): JsonObject {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        val allowed = fields.filterIsInstance<JsonObject>().filter { field ->
            val roles = field.getJsonArray("readRoles")
            roles == null || roles.isEmpty || roles.any { it == principal.role || it == "TENANT_ADMIN" }
        }.mapNotNull { it.getString("name") }.toSet() + setOf("id", "docstatus", "version", "created_at", "updated_at")
        return JsonObject().also { output ->
            row.fieldNames().filter { it in allowed }.forEach { output.put(it, row.getValue(it)) }
        }
    }

    internal fun audit(
        connection: SqlConnection,
        principal: TenantPrincipal,
        doctype: String,
        id: UUID,
        action: String,
        before: JsonObject?,
        after: JsonObject?
    ): Future<Void> {
        val adminId = if (principal.subjectType == "ADMIN") principal.userId else null
        val userId = if (principal.subjectType == "USER") principal.userId else null
        return connection.preparedQuery(
            """INSERT INTO audit.document_event
               (id,actor_admin_id,actor_user_id,doctype,document_id,action,before_data,after_data)
               VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb,$8::jsonb)"""
        ).execute(Tuple.of(UUID.randomUUID(), adminId, userId, doctype, id, action,
            before?.encode(), after?.encode())).mapEmpty()
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
