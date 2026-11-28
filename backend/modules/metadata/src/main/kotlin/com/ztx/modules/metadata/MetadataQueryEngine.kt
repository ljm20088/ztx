package com.ztx.modules.metadata

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.UUID

data class CompiledMetadataQuery(
    val selectSql: String,
    val countSql: String,
    val selectParameters: List<Any?>,
    val countParameters: List<Any?>
)

/**
 * 受控查询编译器：标识符只来自已校验元数据，所有用户输入只进入绑定参数。
 * 不接受 SQL 字符串、表达式、表名或客户端自定义列清单。
 */
object MetadataQueryEngine {
    private data class FilterBudget(var nodes: Int = 0)
    private val identifier = Regex("[a-z][a-z0-9_]{0,62}")
    private val protectedSchemas = setOf("public", "platform", "iam", "meta", "audit", "pg_catalog", "information_schema")
    private val operators = setOf("eq", "ne", "gt", "gte", "lt", "lte", "contains", "startsWith", "in", "isNull")

    fun compile(
        definition: JsonObject,
        request: JsonObject,
        role: String,
        companyIds: Set<UUID>,
        bookIds: Set<UUID>,
        organizationUnitIds: Set<UUID>
    ): CompiledMetadataQuery {
        val table = definition.getJsonObject("table") ?: invalid("缺少物理表映射")
        val schema = table.getString("schema", "")
        val tableName = table.getString("name", "")
        requireIdentifier(schema, "Schema")
        requireIdentifier(tableName, "表名")
        if (schema in protectedSchemas) invalid("不允许查询受保护 Schema")

        val fields = definition.getJsonArray("fields") ?: JsonArray()
        val fieldNames = fields.mapNotNull { (it as? JsonObject)?.getString("name") }.toSet()
        if (fieldNames.isEmpty()) invalid("DocType 未定义字段")
        fieldNames.forEach { requireIdentifier(it, "字段") }

        val list = definition.getJsonObject("list") ?: JsonObject()
        val allowedColumns = (list.getJsonArray("columns") ?: JsonArray(fields.mapNotNull {
            (it as? JsonObject)?.getString("name")
        })).mapNotNull { value ->
            when (value) {
                is String -> value
                is JsonObject -> value.getString("field")
                else -> null
            }
        }.filter { it in fieldNames }.distinct()
        if (allowedColumns.isEmpty()) invalid("列表没有可读取的列")

        val readable = fields.filterIsInstance<JsonObject>().filter { field ->
            val roles = field.getJsonArray("readRoles")
            // TENANT_ADMIN 是当前主体的特权角色，不是可写入字段白名单的通配符。
            roles == null || roles.isEmpty || role == "TENANT_ADMIN" || roles.any { it == role }
        }.mapNotNull { it.getString("name") }.toSet()
        val selected = allowedColumns.filter { it in readable }
        // 系统主键始终作为隐藏投影返回，供列表行打开对应 Document；不要求元数据把 id 声明为普通字段。
        val projection = (listOf("id") + selected).distinct()
        if (selected.isEmpty()) invalid("当前用户没有可读取的列表字段")

        fun configuredFields(key: String): Set<String> =
            (list.getJsonArray(key) ?: JsonArray()).mapNotNull { value ->
                when (value) {
                    is String -> value
                    is JsonObject -> value.getString("field")
                    else -> null
                }
            }.toSet().intersect(readable)

        val filterable = configuredFields("filters")
        val sortable = configuredFields("sort")
        val searchable = configuredFields("search")

        val where = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        request.getJsonObject("filter")?.let { where += compileNode(it, filterable, values, 0, FilterBudget()) }

        // 企业、账套和组织范围来自认证主体，绝不读取客户端传入的范围参数。
        addScope("company_id", fieldNames, companyIds, where, values)
        addScope("book_id", fieldNames, bookIds, where, values)
        addScope("organization_unit_id", fieldNames, organizationUnitIds, where, values)

        request.getString("search")?.trim()?.takeIf { it.isNotEmpty() }?.let { search ->
            if (searchable.isNotEmpty()) {
                values += search
                val parameter = "$" + values.size
                where += searchable.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "position(lower($parameter) in lower(CAST(\"$it\" AS text))) > 0"
                }
            }
        }
        val whereSql = where.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " WHERE ") ?: ""
        val from = " FROM \"$schema\".\"$tableName\""
        val countSql = "SELECT count(*) AS total$from$whereSql"

        val requestedSort = request.getJsonArray("sort") ?: JsonArray()
        if (requestedSort.size() > 5) invalid("最多允许 5 个排序字段")
        if (requestedSort.any { it !is JsonObject }) invalid("排序条件必须是对象")
        val orderBy = requestedSort.map { raw ->
            val item = raw as JsonObject
            val field = item.getString("field") ?: invalid("排序字段不能为空")
            if (field !in sortable) invalid("不允许按字段排序：$field")
            val direction = item.getString("direction", "asc").lowercase()
            if (direction !in setOf("asc", "desc")) invalid("排序方向不合法")
            "\"$field\" " + direction.uppercase()
        }
        val orderSql = if (orderBy.isEmpty()) " ORDER BY \"id\" ASC"
            else " ORDER BY " + orderBy.joinToString(", ") + ", \"id\" ASC"
        val page = request.getInteger("page", 1).coerceIn(1, 100000)
        val pageSize = request.getInteger("pageSize", 20).coerceIn(1, 200)
        val selectValues = values.toMutableList()
        selectValues += pageSize
        selectValues += (page - 1) * pageSize
        val selectSql = "SELECT " + projection.joinToString(", ") { "\"$it\"" } +
            from + whereSql + orderSql + " LIMIT $" + (selectValues.size - 1) + " OFFSET $" + selectValues.size
        return CompiledMetadataQuery(selectSql, countSql, selectValues, values.toList())
    }

    private fun addScope(
        field: String,
        fieldNames: Set<String>,
        allowedIds: Set<UUID>,
        where: MutableList<String>,
        values: MutableList<Any?>
    ) {
        if (field !in fieldNames) return
        if (allowedIds.isEmpty()) {
            where += "FALSE"
        } else {
            values.add(allowedIds.toTypedArray())
            where += "\"$field\" = ANY($" + values.size + "::uuid[])"
        }
    }

    private fun compileNode(node: JsonObject, allowed: Set<String>, values: MutableList<Any?>, depth: Int, budget: FilterBudget): String {
        if (depth > 8) invalid("筛选条件嵌套层级不能超过 8 层")
        budget.nodes++
        if (budget.nodes > 200) invalid("筛选 AST 节点总数不能超过 200 个")
        val logical = node.getString("op", "").lowercase()
        if (logical == "and" || logical == "or") {
            val children = node.getJsonArray("conditions") ?: invalid("逻辑筛选缺少 conditions")
            if (children.isEmpty || children.size() > 50) invalid("逻辑筛选条件数量不合法")
            val parts = children.map { raw ->
                val child = raw as? JsonObject ?: invalid("筛选条件必须是对象")
                compileNode(child, allowed, values, depth + 1, budget)
            }
            return parts.joinToString(if (logical == "and") " AND " else " OR ", prefix = "(", postfix = ")")
        }
        val field = node.getString("field", "")
        requireIdentifier(field, "筛选字段")
        if (field !in allowed) invalid("字段不可筛选：$field")
        val operator = node.getString("operator", "")
        if (operator !in operators) invalid("不支持的筛选操作符：$operator")
        if (operator == "isNull") {
            val isNull = node.getBoolean("value", true)
            return "\"$field\" IS " + (if (isNull) "" else "NOT ") + "NULL"
        }
        val value = node.getValue("value")
        if (value == null) invalid("筛选值不能为空；空值请使用 isNull")
        if (operator == "in") {
            val items = value as? JsonArray ?: invalid("in 操作符的值必须是数组")
            if (items.isEmpty || items.size() > 100) invalid("in 操作符数组长度不合法")
            val placeholders = items.map { item ->
                values += item
                "$" + values.size
            }
            return "\"$field\" IN (" + placeholders.joinToString(", ") + ")"
        }
        if (operator == "contains" || operator == "startsWith") {
            values += value.toString()
            val parameter = "$" + values.size
            return if (operator == "contains")
                "position(lower($parameter) in lower(CAST(\"$field\" AS text))) > 0"
            else
                "left(lower(\"$field\"), char_length($parameter)) = lower($parameter)"
        }
        val sqlOperator = when (operator) {
            "eq" -> "="
            "ne" -> "<>"
            "gt" -> ">"
            "gte" -> ">="
            "lt" -> "<"
            "lte" -> "<="
            else -> invalid("不支持的筛选操作符")
        }
        values += value
        return "\"$field\" $sqlOperator $" + values.size
    }

    private fun requireIdentifier(value: String, label: String) {
        if (!identifier.matches(value)) invalid("$label 标识不合法")
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
