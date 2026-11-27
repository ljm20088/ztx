package com.ztx.modules.metadata

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

data class MetadataDiagnostic(
    val code: String,
    val path: String,
    val message: String
) {
    fun toJson() = JsonObject().put("code", code).put("path", path).put("message", message)
}

/** 只接受声明式 JSON，不解释 HTML、脚本、SQL 或表达式。 */
object MetadataDefinitionValidator {
    private val doctypePattern = Regex("[A-Za-z][A-Za-z0-9_]{0,119}")
    private val identifierPattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val rolePattern = Regex("(?i)[a-z][a-z0-9_]{0,62}")
    private val supportedTypes = setOf("string", "text", "uuid", "date", "datetime", "time", "decimal", "integer", "boolean", "reference", "select", "money", "json", "table")
    private val dangerousContent = Regex("(?i)<\\s*/?\\s*(script|iframe|object|embed)\\b|javascript\\s*:|on(error|load|click)\\s*=")

    fun validate(definition: JsonObject, knownDefinitions: Map<String, JsonObject> = emptyMap()): List<MetadataDiagnostic> {
        val errors = mutableListOf<MetadataDiagnostic>()
        if (dangerousContent.containsMatchIn(definition.encode())) {
            errors += MetadataDiagnostic("UNSAFE_CONTENT", "$", "元数据不能包含 HTML、脚本或事件处理内容")
        }

        val doctype = definition.getString("doctype", "").trim()
        if (!doctypePattern.matches(doctype)) errors += MetadataDiagnostic("INVALID_DOCTYPE", "doctype", "DocType 标识必须以字母开头，只能包含字母、数字和下划线")
        if (definition.getString("scope", "TENANT") != "TENANT") {
            errors += MetadataDiagnostic("SCOPE_NOT_ALLOWED", "scope", "租户管理接口只能创建和修改租户级元数据")
        }

        val table = definition.getJsonObject("table")
        if (table == null) {
            errors += MetadataDiagnostic("TABLE_REQUIRED", "table", "必须定义物理表映射")
        } else {
            val schema = table.getString("schema", "")
            val name = table.getString("name", "")
            if (!identifierPattern.matches(schema) || schema in setOf("pg_catalog", "information_schema", "platform", "iam", "meta", "audit")) {
                errors += MetadataDiagnostic("INVALID_SCHEMA", "table.schema", "Schema 标识不合法或不允许使用")
            }
            if (!identifierPattern.matches(name)) errors += MetadataDiagnostic("INVALID_TABLE", "table.name", "表名必须使用小写蛇形标识")
        }

        val fields = definition.getJsonArray("fields") ?: JsonArray()
        if (fields.isEmpty) errors += MetadataDiagnostic("FIELDS_REQUIRED", "fields", "至少定义一个字段")
        if (fields.size() > 200) errors += MetadataDiagnostic("TOO_MANY_FIELDS", "fields", "单个 DocType 最多定义 200 个字段")

        val fieldNames = linkedSetOf<String>()
        val references = linkedSetOf<String>()
        fields.forEachIndexed { index, raw ->
            val path = "fields[$index]"
            val field = raw as? JsonObject
            if (field == null) {
                errors += MetadataDiagnostic("INVALID_FIELD", path, "字段定义必须是对象")
                return@forEachIndexed
            }
            val name = field.getString("name", "").trim()
            if (!identifierPattern.matches(name)) errors += MetadataDiagnostic("INVALID_FIELD_NAME", "$path.name", "字段标识必须使用小写蛇形命名")
            else if (!fieldNames.add(name)) errors += MetadataDiagnostic("DUPLICATE_FIELD", "$path.name", "字段标识重复：$name")

            val type = field.getString("type", "").lowercase()
            if (type !in supportedTypes) errors += MetadataDiagnostic("UNSUPPORTED_FIELD_TYPE", "$path.type", "不支持的字段类型：$type")
            if (type == "reference") {
                val target = field.getString("target", "").trim()
                if (!doctypePattern.matches(target)) errors += MetadataDiagnostic("REFERENCE_TARGET_REQUIRED", "$path.target", "引用字段必须指定合法的目标 DocType")
                else references += target
            }
            if (type == "select") {
                val options = field.getJsonArray("options") ?: JsonArray()
                if (options.isEmpty || options.any { it !is String || it.isBlank() }) {
                    errors += MetadataDiagnostic("SELECT_OPTIONS_REQUIRED", "$path.options", "下拉字段必须提供非空选项")
                }
            }
            if (type == "table") {
                val children = field.getJsonArray("childFields") ?: JsonArray()
                if (children.isEmpty || children.size() > 100) {
                    errors += MetadataDiagnostic("CHILD_FIELDS_REQUIRED", "$path.childFields", "明细字段必须定义 1 到 100 个子字段")
                }
                val childNames = mutableSetOf<String>()
                children.forEachIndexed { childIndex, rawChild ->
                    val child = rawChild as? JsonObject
                    val childName = child?.getString("name", "")?.trim().orEmpty()
                    if (child == null || !identifierPattern.matches(childName) || !childNames.add(childName)) {
                        errors += MetadataDiagnostic("INVALID_CHILD_FIELD", "$path.childFields[$childIndex]", "明细子字段标识不合法或重复")
                    } else if (child.getString("type", "") !in supportedTypes - "table") {
                        errors += MetadataDiagnostic("UNSUPPORTED_CHILD_FIELD_TYPE", "$path.childFields[$childIndex].type", "明细子字段类型不支持或不能嵌套明细表")
                    }
                }
            }
            if (field.containsKey("defaultValue") && !validDefault(type, field.getValue("defaultValue"))) {
                errors += MetadataDiagnostic("INVALID_DEFAULT_VALUE", "$path.defaultValue", "默认值与字段类型不匹配")
            }
            if (field.getBoolean("required", false) && field.getBoolean("readOnly", false) && !field.containsKey("defaultValue")) {
                errors += MetadataDiagnostic("READ_ONLY_REQUIRED_WITHOUT_DEFAULT", path, "只读必填字段必须提供默认值")
            }
            listOf("readRoles", "writeRoles").forEach { roleKey ->
                val roles = field.getJsonArray(roleKey)
                if (roles != null && roles.any { it !is String || !rolePattern.matches(it) }) {
                    errors += MetadataDiagnostic("INVALID_FIELD_ROLE", "$path.$roleKey", "字段角色标识格式不正确")
                }
            }
        }

        validateFieldList(definition.getJsonObject("form")?.getJsonArray("fields"), "form.fields", fieldNames, errors)
        definition.getJsonObject("list")?.let { list ->
            validateFieldList(list.getJsonArray("columns"), "list.columns", fieldNames, errors)
            validateFieldList(list.getJsonArray("filters"), "list.filters", fieldNames, errors)
            validateFieldList(list.getJsonArray("sort"), "list.sort", fieldNames, errors)
            validateFieldList(list.getJsonArray("search"), "list.search", fieldNames, errors)
        }

        definition.getJsonObject("extension")?.let { extension ->
            val mode = extension.getString("mode", "")
            if (mode !in setOf("METADATA_WITH_KOTLIN_FILE", "KOTLIN_IMPLEMENTATION")) {
                errors += MetadataDiagnostic("INVALID_EXTENSION_MODE", "extension.mode", "Document 扩展模式不受支持")
            }
            val implementation = extension.getString("implementation", "")
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(implementation)) {
                errors += MetadataDiagnostic("INVALID_EXTENSION_IMPLEMENTATION", "extension.implementation", "Kotlin 实现必须使用完整类名")
            }
        }

        definition.getJsonObject("permissions")?.let { permissions ->
            permissions.fieldNames().forEach { action ->
                if (action !in setOf("read", "create", "update", "delete", "submit", "cancel", "void")) {
                    errors += MetadataDiagnostic("INVALID_PERMISSION_ACTION", "permissions.$action", "不支持的权限动作")
                }
                val roles = permissions.getJsonArray(action)
                if (roles != null && roles.any { it !is String || !rolePattern.matches(it) }) {
                    errors += MetadataDiagnostic("INVALID_PERMISSION_ROLE", "permissions.$action", "角色标识格式不正确")
                }
            }
        }

        if (doctypePattern.matches(doctype)) {
            val graph = knownDefinitions.mapValues { (_, value) -> referenceTargets(value) }.toMutableMap()
            graph[doctype] = references
            findCycle(graph, doctype)?.let { cycle ->
                errors += MetadataDiagnostic("REFERENCE_CYCLE", "fields", "引用关系形成循环：${cycle.joinToString(" -> ")}")
            }
            references.filter { it != doctype && it !in knownDefinitions }.forEach { target ->
                errors += MetadataDiagnostic("REFERENCE_NOT_FOUND", "fields", "引用目标不存在：$target")
            }
        }
        return errors.distinctBy { "${it.code}:${it.path}:${it.message}" }
    }

    private fun validateFieldList(values: JsonArray?, path: String, fieldNames: Set<String>, errors: MutableList<MetadataDiagnostic>) {
        values?.forEachIndexed { index, raw ->
            val name = when (raw) {
                is String -> raw
                is JsonObject -> raw.getString("field", "")
                else -> ""
            }
            if (name !in fieldNames) errors += MetadataDiagnostic("UNKNOWN_LAYOUT_FIELD", "$path[$index]", "布局或列表引用了未定义字段：$name")
        }
    }

    private fun referenceTargets(definition: JsonObject): Set<String> =
        (definition.getJsonArray("fields") ?: JsonArray()).mapNotNull { raw ->
            (raw as? JsonObject)?.takeIf { it.getString("type") == "reference" }?.getString("target")
        }.toSet()

    private fun findCycle(graph: Map<String, Set<String>>, start: String): List<String>? {
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(node: String): List<String>? {
            if (node in visiting) return visiting.dropWhile { it != node } + node
            if (!visited.add(node)) return null
            visiting += node
            for (next in graph[node].orEmpty().filter { it in graph }) visit(next)?.let { return it }
            visiting -= node
            return null
        }
        return visit(start)
    }

    private fun validDefault(type: String, value: Any?): Boolean = when (type) {
        "string", "text", "uuid", "date", "datetime", "time", "reference", "select" -> value is String
        "decimal", "money" -> value is Number || value is String
        "integer" -> value is Int || value is Long
        "boolean" -> value is Boolean
        "json" -> value is JsonObject || value is JsonArray
        else -> false
    }
}
