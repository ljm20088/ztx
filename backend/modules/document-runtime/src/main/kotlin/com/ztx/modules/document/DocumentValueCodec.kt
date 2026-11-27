package com.ztx.modules.document

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** 将 JSON 输入按 DocType 字段定义转换为稳定的 Kotlin 值；不执行元数据中的表达式或脚本。 */
object DocumentValueCodec {
    fun decode(definition: JsonObject, input: JsonObject): JsonObject {
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        val result = JsonObject()
        fields.filterIsInstance<JsonObject>().forEach { field ->
            val name = field.getString("name") ?: return@forEach
            val type = field.getString("type", "")
            val supplied = input.getValue(name)
            if (supplied == null && field.getBoolean("required", false) && !field.containsKey("defaultValue")) {
                throw IllegalArgumentException("字段 $name 为必填项")
            }
            val value = if (supplied == null) field.getValue("defaultValue") else supplied
            if (value != null) result.put(name, normalize(name, type, value, field))
        }
        val known = fields.filterIsInstance<JsonObject>().mapNotNull { it.getString("name") }.toSet()
        val unknown = input.fieldNames() - known
        if (unknown.isNotEmpty()) throw IllegalArgumentException("包含未定义字段：${unknown.sorted().joinToString()}")
        return result
    }

    private fun normalize(name: String, type: String, value: Any, field: JsonObject): Any = try {
        when (type) {
            "string", "text", "select" -> value as? String ?: invalid(name, "必须是文本")
            "uuid", "reference" -> UUID.fromString(value.toString())
            "date" -> LocalDate.parse(value.toString())
            "datetime" -> LocalDateTime.parse(value.toString())
            "time" -> LocalTime.parse(value.toString())
            "decimal", "money" -> BigDecimal(value.toString())
            "integer" -> when (value) {
                is Number -> value.toLong()
                else -> value.toString().toLong()
            }
            "boolean" -> value as? Boolean ?: invalid(name, "必须是布尔值")
            "json" -> when (value) {
                is JsonObject, is JsonArray -> value
                else -> invalid(name, "必须是 JSON 对象或数组")
            }
            "table" -> normalizeChildren(name, value, field.getJsonArray("childFields") ?: JsonArray())
            else -> invalid(name, "不支持的字段类型：$type")
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("字段 $name 格式不正确", error)
    }

    private fun normalizeChildren(name: String, value: Any, childFields: JsonArray): JsonArray {
        val rows = value as? JsonArray ?: invalid(name, "明细必须是数组")
        if (childFields.isEmpty) invalid(name, "明细字段定义不能为空")
        return JsonArray(rows.mapIndexed { index, raw ->
            val row = raw as? JsonObject ?: invalid(name, "第 ${index + 1} 行必须是对象")
            val normalized = JsonObject()
            childFields.filterIsInstance<JsonObject>().forEach { child ->
                val childName = child.getString("name") ?: return@forEach
                val childValue = row.getValue(childName)
                if (childValue == null && child.getBoolean("required", false) && !child.containsKey("defaultValue")) {
                    invalid("$name[${index + 1}].$childName", "为必填项")
                }
                val actual = childValue ?: child.getValue("defaultValue")
                if (actual != null) {
                    val converted = normalize("$name[${index + 1}].$childName", child.getString("type", ""), actual, child)
                    normalized.put(childName, if (converted is UUID || converted is LocalDate || converted is LocalDateTime || converted is LocalTime) converted.toString() else converted)
                }
            }
            val known = childFields.filterIsInstance<JsonObject>().mapNotNull { it.getString("name") }.toSet()
            val unknown = row.fieldNames() - known
            if (unknown.isNotEmpty()) invalid(name, "第 ${index + 1} 行包含未定义字段：${unknown.sorted().joinToString()}")
            normalized
        })
    }

    private fun invalid(name: String, reason: String): Nothing =
        throw IllegalArgumentException("字段 $name $reason")
}

enum class DocumentStatus(val code: Int) { DRAFT(0), SUBMITTED(1), CANCELLED(2), VOID(3) }

/** 常规单据生命周期的纯状态机；数据库更新与审计由同一事务包裹。 */
object DocumentLifecycle {
    fun transition(current: Int, action: String): Int {
        val status = DocumentStatus.entries.firstOrNull { it.code == current }
            ?: throw IllegalArgumentException("单据状态不受支持：$current")
        return when (action) {
            "submit" -> {
                if (status != DocumentStatus.DRAFT) invalid("只有草稿单据可以提交")
                DocumentStatus.SUBMITTED.code
            }
            "cancel" -> {
                if (status != DocumentStatus.SUBMITTED) invalid("只有已提交单据可以撤销")
                DocumentStatus.CANCELLED.code
            }
            "void" -> {
                if (status != DocumentStatus.DRAFT) invalid("只有草稿单据可以作废")
                DocumentStatus.VOID.code
            }
            else -> invalid("不支持的单据动作：$action")
        }
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
