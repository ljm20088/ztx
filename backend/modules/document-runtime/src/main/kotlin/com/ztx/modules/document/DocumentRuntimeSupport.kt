package com.ztx.modules.document

import com.ztx.modules.iam.TenantPrincipal
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.UUID

/**
 * Document 数据访问运行时能力。
 *
 * 只包含元数据、权限、数据范围、字段映射、SQL 持久化和审计能力；
 * 不依赖 HTTP Router 或 RoutingContext。Db 与 HTTP Routes 都可以调用它。
 */
open class DocumentRuntimeSupport(internal val behaviors: DocumentBehaviorRegistry = DocumentBehaviorRegistry.load()) {
    private val identifier = Regex("[a-z][a-z0-9_]{0,62}")
    private val protectedSchemas = setOf("public", "platform", "iam", "meta", "audit", "pg_catalog", "information_schema")

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
    } else if (principal.role != "TENANT_ADMIN" && roles.none { it == principal.role }) {
        throw IllegalArgumentException("无权执行单据动作：$action")
    }
}

internal fun redact(definition: JsonObject, principal: TenantPrincipal, row: JsonObject): JsonObject {
    val fields = definition.getJsonArray("fields") ?: JsonArray()
    val allowed = fields.filterIsInstance<JsonObject>().filter { field ->
        val roles = field.getJsonArray("readRoles")
        canReadField(roles, principal.role)
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
}
