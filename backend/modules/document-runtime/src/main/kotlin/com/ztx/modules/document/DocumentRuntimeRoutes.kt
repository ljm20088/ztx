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

/** Document 通用 CRUD 与生命周期；权限、持久化和审计在同一租户数据库事务中执行。 */
class DocumentRuntimeRoutes {
    private val identifier = Regex("[a-z][a-z0-9_]{0,62}")

    fun register(router: Router) {
        router.route("/api/v1/tenant/documents/*").handler(BodyHandler.create().setBodyLimit(1024 * 1024))
        router.get("/api/v1/tenant/runtime/doctypes/:doctype").handler(::runtimeDefinition)
        router.post("/api/v1/tenant/documents/:doctype").handler(::create)
        router.get("/api/v1/tenant/documents/:doctype/:id").handler(::read)
        router.put("/api/v1/tenant/documents/:doctype/:id").handler(::update)
        router.post("/api/v1/tenant/documents/:doctype/:id/actions/:action").handler(::action)
    }

    private fun create(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "create")
            val values = DocumentValueCodec.decode(definition, body(context))
            enforceCompanyScope(definition, values, principal, requireValue = true)
            principal.database.withTransaction { connection ->
                insert(connection, definition, values, principal).compose { row ->
                    audit(connection, principal, context.pathParam("doctype"), row.getUUID("id"),
                        "CREATE", null, row).map { row }
                }
            }.onSuccess { send(context, redact(definition, principal, it), 201) }
                .onFailure { fail(context, status(it), it.message ?: "创建单据失败") }
        }.onFailure { fail(context, status(it), it.message ?: "创建单据失败") }
    }

    private fun read(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "read")
            val id = UUID.fromString(context.pathParam("id"))
            principal.database.withTransaction { connection -> selectOne(connection, definition, id, principal, false) }
                .onSuccess { send(context, redact(definition, principal, it)) }
                .onFailure { fail(context, status(it), it.message ?: "读取单据失败") }
        }.onFailure { fail(context, status(it), it.message ?: "读取单据失败") }
    }

    private fun update(context: RoutingContext) = withPrincipal(context) { principal ->
        loadDefinition(principal, context.pathParam("doctype")).compose { definition ->
            authorize(definition, principal, "update")
            val id = UUID.fromString(context.pathParam("id"))
            val values = DocumentValueCodec.decode(definition, body(context))
            enforceCompanyScope(definition, values, principal, requireValue = false)
            principal.database.withTransaction { connection ->
                selectOne(connection, definition, id, principal, true).compose { before ->
                    if (before.getInteger("docstatus", 0) != DocumentStatus.DRAFT.code) {
                        return@compose Future.failedFuture<JsonObject>("只有草稿单据可以修改")
                    }
                    updateRow(connection, definition, id, values, principal).compose { after ->
                        audit(connection, principal, context.pathParam("doctype"), id, "UPDATE", before, after)
                            .map { after }
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
            val id = UUID.fromString(context.pathParam("id"))
            principal.database.withTransaction { connection ->
                selectOne(connection, definition, id, principal, true).compose { before ->
                    val next = DocumentLifecycle.transition(before.getInteger("docstatus", 0), action)
                    connection.preparedQuery(
                        """UPDATE ${tableSql(definition)} SET "docstatus"=$1,"updated_at"=clock_timestamp()
                           WHERE "id"=$2 AND "docstatus"=$3 RETURNING *"""
                    ).execute(Tuple.of(next, id, before.getInteger("docstatus", 0))).compose { rows ->
                        val after = rows.firstOrNull()?.toJson()
                            ?: return@compose Future.failedFuture<JsonObject>("单据状态已变化，请刷新后重试")
                        audit(connection, principal, context.pathParam("doctype"), id, action.uppercase(), before, after)
                            .map { after }
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
            ).execute(Tuple.of(doctype)).map { rows ->
                rows.firstOrNull()?.getJsonObject("definition_json")
                    ?: throw IllegalArgumentException("DocType 不存在或尚未通过校验")
            }
        }

    private fun insert(connection: SqlConnection, definition: JsonObject, values: JsonObject, principal: TenantPrincipal): Future<JsonObject> {
        val id = UUID.randomUUID()
        val entries = writableEntries(definition, values, principal)
        val columns = mutableListOf("\"id\"", "\"docstatus\"")
        val parameters = mutableListOf<Any?>(id, DocumentStatus.DRAFT.code)
        val placeholders = mutableListOf("$1", "$2")
        entries.forEach { (name, value, json) ->
            columns += "\"$name\""
            parameters += if (json) value.encode() else value
            placeholders += if (json) "${'$'}${parameters.size}::jsonb" else "${'$'}${parameters.size}"
        }
        val sql = "INSERT INTO ${tableSql(definition)} (${columns.joinToString(",")}) VALUES (${placeholders.joinToString(",")}) RETURNING *"
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalStateException("单据写入失败")
        }
    }

    private fun updateRow(
        connection: SqlConnection,
        definition: JsonObject,
        id: UUID,
        values: JsonObject,
        principal: TenantPrincipal
    ): Future<JsonObject> {
        val entries = writableEntries(definition, values, principal)
        if (entries.isEmpty()) return Future.failedFuture("没有可更新字段")
        val parameters = mutableListOf<Any?>()
        val assignments = entries.map { (name, value, json) ->
            parameters += if (json) value.encode() else value
            "\"$name\"=${'$'}${parameters.size}${if (json) "::jsonb" else ""}"
        }.toMutableList()
        assignments += "\"updated_at\"=clock_timestamp()"
        parameters += id
        val idIndex = parameters.size
        val where = scopeWhere(definition, principal, parameters)
        val sql = "UPDATE ${tableSql(definition)} SET ${assignments.joinToString(",")} WHERE \"id\"=${'$'}$idIndex AND \"docstatus\"=0$where RETURNING *"
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalArgumentException("单据不存在、无权访问或已不处于草稿状态")
        }
    }

    private fun selectOne(
        connection: SqlConnection,
        definition: JsonObject,
        id: UUID,
        principal: TenantPrincipal,
        lock: Boolean
    ): Future<JsonObject> {
        val parameters = mutableListOf<Any?>(id)
        val where = scopeWhere(definition, principal, parameters)
        val sql = "SELECT * FROM ${tableSql(definition)} WHERE \"id\"=$1$where" + if (lock) " FOR UPDATE" else ""
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalArgumentException("单据不存在或无权访问")
        }
    }

    private fun scopeWhere(definition: JsonObject, principal: TenantPrincipal, parameters: MutableList<Any?>): String {
        val names = (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>()
            .mapNotNull { it.getString("name") }.toSet()
        val conditions = mutableListOf<String>()
        fun addScope(field: String, ids: Set<UUID>) {
            if (field !in names) return
            if (ids.isEmpty()) conditions += "FALSE" else {
                parameters.add(ids.toTypedArray())
                conditions += "\"$field\" = ANY(${'$'}${parameters.size}::uuid[])"
            }
        }
        addScope("company_id", principal.companyIds)
        addScope("book_id", principal.bookIds)
        addScope("organization_unit_id", principal.organizationUnitIds)
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " AND ") ?: ""
    }

    private fun enforceCompanyScope(
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

    private fun writableEntries(definition: JsonObject, values: JsonObject, principal: TenantPrincipal): List<Triple<String, Any, Boolean>> {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        return fields.filterIsInstance<JsonObject>().mapNotNull { field ->
            val name = field.getString("name") ?: return@mapNotNull null
            if (name in setOf("id", "docstatus", "created_at", "updated_at") || field.getBoolean("readOnly", false)) return@mapNotNull null
            val writeRoles = field.getJsonArray("writeRoles")
            if (writeRoles != null && writeRoles.isNotEmpty && writeRoles.none { it == principal.role || it == "TENANT_ADMIN" }) {
                return@mapNotNull null
            }
            val value = values.getValue(name) ?: return@mapNotNull null
            val type = field.getString("type")
            Triple(name, value, type == "json" || type == "table")
        }
    }

    private fun tableSql(definition: JsonObject): String {
        val table = definition.getJsonObject("table") ?: throw IllegalArgumentException("缺少表映射")
        val schema = table.getString("schema", "")
        val name = table.getString("name", "")
        if (!identifier.matches(schema) || !identifier.matches(name) ||
            schema in setOf("platform", "iam", "meta", "audit", "pg_catalog", "information_schema")) {
            throw IllegalArgumentException("物理表映射不允许使用")
        }
        return "\"$schema\".\"$name\""
    }

    private fun authorize(definition: JsonObject, principal: TenantPrincipal, action: String) {
        val roles = definition.getJsonObject("permissions")?.getJsonArray(action)
        if (roles == null || roles.isEmpty) {
            if (principal.role != "TENANT_ADMIN") throw IllegalArgumentException("无权执行单据动作：$action")
        } else if (roles.none { it == principal.role || it == "TENANT_ADMIN" }) {
            throw IllegalArgumentException("无权执行单据动作：$action")
        }
    }

    private fun redact(definition: JsonObject, principal: TenantPrincipal, row: JsonObject): JsonObject {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        val allowed = fields.filterIsInstance<JsonObject>().filter { field ->
            val roles = field.getJsonArray("readRoles")
            roles == null || roles.isEmpty || roles.any { it == principal.role || it == "TENANT_ADMIN" }
        }.mapNotNull { it.getString("name") }.toSet() + setOf("id", "docstatus", "created_at", "updated_at")
        return JsonObject().also { output ->
            row.fieldNames().filter { it in allowed }.forEach { output.put(it, row.getValue(it)) }
        }
    }

    private fun audit(
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
}$idIndex AND \"docstatus\"=0$where RETURNING *"
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalArgumentException("单据不存在、无权访问或已不处于草稿状态")
        }
    }

    private fun selectOne(
        connection: SqlConnection,
        definition: JsonObject,
        id: UUID,
        principal: TenantPrincipal,
        lock: Boolean
    ): Future<JsonObject> {
        val parameters = mutableListOf<Any?>(id)
        val where = scopeWhere(definition, principal, parameters)
        val sql = "SELECT * FROM ${tableSql(definition)} WHERE \"id\"=$1$where" + if (lock) " FOR UPDATE" else ""
        return connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
            rows.firstOrNull()?.toJson() ?: throw IllegalArgumentException("单据不存在或无权访问")
        }
    }

    private fun scopeWhere(definition: JsonObject, principal: TenantPrincipal, parameters: MutableList<Any?>): String {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        val hasCompany = fields.filterIsInstance<JsonObject>().any { it.getString("name") == "company_id" }
        if (!hasCompany) return ""
        if (principal.companyIds.isEmpty()) return " AND FALSE"
        parameters.add(principal.companyIds.toTypedArray())
        return " AND \"company_id\" = ANY(${'$'}${parameters.size}::uuid[])"
    }

    private fun enforceCompanyScope(
        definition: JsonObject,
        values: JsonObject,
        principal: TenantPrincipal,
        requireValue: Boolean
    ) {
        val hasCompany = (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>()
            .any { it.getString("name") == "company_id" }
        if (!hasCompany) return
        val company = values.getValue("company_id")?.let { UUID.fromString(it.toString()) }
        if (company == null && requireValue) throw IllegalArgumentException("必须指定所属企业")
        if (company != null && company !in principal.companyIds) throw IllegalArgumentException("无权访问指定企业的数据")
    }

    private fun writableEntries(definition: JsonObject, values: JsonObject): List<Triple<String, Any, Boolean>> {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        return fields.filterIsInstance<JsonObject>().mapNotNull { field ->
            val name = field.getString("name") ?: return@mapNotNull null
            if (name in setOf("id", "docstatus", "created_at", "updated_at") || field.getBoolean("readOnly", false)) return@mapNotNull null
            val value = values.getValue(name) ?: return@mapNotNull null
            val type = field.getString("type")
            Triple(name, value, type == "json" || type == "table")
        }
    }

    private fun tableSql(definition: JsonObject): String {
        val table = definition.getJsonObject("table") ?: throw IllegalArgumentException("缺少表映射")
        val schema = table.getString("schema", "")
        val name = table.getString("name", "")
        if (!identifier.matches(schema) || !identifier.matches(name) ||
            schema in setOf("platform", "iam", "meta", "audit", "pg_catalog", "information_schema")) {
            throw IllegalArgumentException("物理表映射不允许使用")
        }
        return "\"$schema\".\"$name\""
    }

    private fun authorize(definition: JsonObject, principal: TenantPrincipal, action: String) {
        val roles = definition.getJsonObject("permissions")?.getJsonArray(action)
        if (roles == null || roles.isEmpty) {
            if (principal.role != "TENANT_ADMIN") throw IllegalArgumentException("无权执行单据动作：$action")
        } else if (roles.none { it == principal.role || it == "TENANT_ADMIN" }) {
            throw IllegalArgumentException("无权执行单据动作：$action")
        }
    }

    private fun redact(definition: JsonObject, principal: TenantPrincipal, row: JsonObject): JsonObject {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        val allowed = fields.filterIsInstance<JsonObject>().filter { field ->
            val roles = field.getJsonArray("readRoles")
            roles == null || roles.isEmpty || roles.any { it == principal.role || it == "TENANT_ADMIN" }
        }.mapNotNull { it.getString("name") }.toSet() + setOf("id", "docstatus", "created_at", "updated_at")
        return JsonObject().also { output ->
            row.fieldNames().filter { it in allowed }.forEach { output.put(it, row.getValue(it)) }
        }
    }

    private fun audit(
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
