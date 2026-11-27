package com.ztx.modules.document

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.primaryConstructor

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

    /**
     * 将已按 DocType 元数据校验、规范化的 JSON 转为业务 Kotlin 类型。
     * 支持 data class、嵌套 data class、List/Set 以及 UUID、日期时间和 BigDecimal 等精确类型。
     */
    fun <T : Any> decodeAs(definition: JsonObject, input: JsonObject, target: KClass<T>): T {
        val normalized = decode(definition, input)
        @Suppress("UNCHECKED_CAST")
        return convert(normalized, target.createType()) as T
    }

    private fun convert(value: Any?, target: KType): Any? {
        if (value == null) {
            if (target.isMarkedNullable) return null
            throw IllegalArgumentException("必填 Kotlin 属性缺少值：$target")
        }
        val classifier = target.classifier as? KClass<*>
            ?: throw IllegalArgumentException("不支持的 Kotlin 类型：$target")
        return when (classifier) {
            String::class -> value as? String ?: value.toString()
            UUID::class -> if (value is UUID) value else UUID.fromString(value.toString())
            LocalDate::class -> if (value is LocalDate) value else LocalDate.parse(value.toString())
            LocalDateTime::class -> if (value is LocalDateTime) value else LocalDateTime.parse(value.toString())
            LocalTime::class -> if (value is LocalTime) value else LocalTime.parse(value.toString())
            BigDecimal::class -> if (value is BigDecimal) value else BigDecimal(value.toString())
            Int::class -> (value as? Number)?.toInt() ?: value.toString().toInt()
            Long::class -> (value as? Number)?.toLong() ?: value.toString().toLong()
            Short::class -> (value as? Number)?.toShort() ?: value.toString().toShort()
            Double::class -> (value as? Number)?.toDouble() ?: value.toString().toDouble()
            Float::class -> (value as? Number)?.toFloat() ?: value.toString().toFloat()
            Boolean::class -> value as? Boolean
                ?: throw IllegalArgumentException("布尔属性必须是 true 或 false")
            JsonObject::class -> value as? JsonObject
                ?: throw IllegalArgumentException("属性必须是 JSON 对象")
            JsonArray::class -> value as? JsonArray
                ?: throw IllegalArgumentException("属性必须是 JSON 数组")
            List::class, Collection::class, MutableList::class, Set::class, MutableSet::class -> {
                val items = when (value) {
                    is JsonArray -> value.toList()
                    is Collection<*> -> value.toList()
                    else -> throw IllegalArgumentException("属性必须是集合")
                }
                val elementType = target.arguments.firstOrNull()?.type
                    ?: throw IllegalArgumentException("集合属性缺少元素类型：$target")
                val converted = items.map { convert(it, elementType) }
                if (classifier == Set::class || classifier == MutableSet::class) converted.toSet() else converted
            }
            else -> {
                val source = when (value) {
                    is JsonObject -> value.map
                    is Map<*, *> -> value
                    else -> throw IllegalArgumentException("属性 $target 必须是对象")
                }
                val constructor = classifier.primaryConstructor
                    ?: throw IllegalArgumentException("Kotlin 类型必须声明主构造函数：$target")
                val arguments = mutableMapOf<kotlin.reflect.KParameter, Any?>()
                constructor.parameters.forEach { parameter ->
                    val name = parameter.name ?: throw IllegalArgumentException("Kotlin 构造参数必须命名：$target")
                    val sourceName = source.keys.firstOrNull { it == name || snakeToCamel(it.toString()) == name }
                    if (sourceName != null) {
                        arguments[parameter] = convert(source[sourceName], parameter.type)
                    } else if (parameter.isOptional) {
                        // 保留 Kotlin 默认参数。
                    } else if (parameter.type.isMarkedNullable) {
                        arguments[parameter] = null
                    } else {
                        throw IllegalArgumentException("缺少 Kotlin 属性：$name")
                    }
                }
                constructor.callBy(arguments)
            }
        }
    }

    private fun snakeToCamel(value: String): String =
        value.replace(Regex("_([a-z])")) { match -> match.groupValues[1].uppercase() }

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
                    normalized.put(childName, converted)
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
