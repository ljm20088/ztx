package com.ztx.modules.document

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * 元数据驱动的内存 Document。
 *
 * 字段通过下标访问；赋值只修改内存并记录 dirty fields，不会自动写入数据库。
 * 持久化必须由作用域 db.update(document) 显式完成。
 */
class DynamicDocument private constructor(
    val doctype: String,
    val id: UUID?,
    val version: Int,
    private val definition: JsonObject,
    initialValues: JsonObject
) {
    private val fields = (definition.getJsonArray("fields") ?: JsonArray())
        .filterIsInstance<JsonObject>()
        .mapNotNull { field -> field.getString("name")?.let { it to field } }
        .toMap()
    private val values = initialValues.copy()
    private val changed = linkedSetOf<String>()

    operator fun get(field: String): Any? {
        val metadata = requireField(field)
        if (metadata.getBoolean("loadOnDemand", false) && !values.containsKey(field)) {
            throw IllegalStateException("字段 $field 尚未加载，请显式调用 db.getValue() 或字段加载接口")
        }
        return copyValue(values.getValue(field))
    }

    operator fun set(field: String, value: Any?) {
        val metadata = requireField(field)
        require(!metadata.getBoolean("readOnly", false)) { "字段 $field 为只读字段" }
        if (value == null) {
            require(!metadata.getBoolean("required", false)) { "字段 $field 为必填项" }
        }
        val normalized = value?.let { DocumentValueCodec.normalizeField(metadata, it) }
        if (values.getValue(field) != normalized) {
            values.put(field, copyValue(normalized))
            changed += field
        }
    }

    fun getString(field: String): String? = typed(field) {
        it as? String ?: throw IllegalArgumentException("值不是文本")
    }
    fun getDecimal(field: String): BigDecimal? = typed(field) {
        when (it) {
            is BigDecimal -> it
            is Number -> BigDecimal(it.toString())
            else -> BigDecimal(it.toString())
        }
    }
    fun getInt(field: String): Int? = typed(field) {
        when (it) {
            is Number -> it.toInt()
            else -> it.toString().toInt()
        }
    }
    fun getLong(field: String): Long? = typed(field) {
        when (it) {
            is Number -> it.toLong()
            else -> it.toString().toLong()
        }
    }
    fun getBoolean(field: String): Boolean? = typed(field) {
        it as? Boolean ?: throw IllegalArgumentException("值不是布尔类型")
    }
    fun getDate(field: String): LocalDate? = typed(field) {
        if (it is LocalDate) it else LocalDate.parse(it.toString())
    }
    fun getDateTime(field: String): LocalDateTime? = typed(field) {
        if (it is LocalDateTime) it else LocalDateTime.parse(it.toString())
    }
    fun getTime(field: String): LocalTime? = typed(field) {
        if (it is LocalTime) it else LocalTime.parse(it.toString())
    }

    internal fun metadata(): JsonObject = definition.copy()

    fun dirtyFields(): Set<String> = changed.toSet()
    fun changes(): JsonObject = JsonObject().also { result ->
        changed.forEach { field -> result.put(field, copyValue(values.getValue(field))) }
    }
    fun toJson(): JsonObject = values.copy()

    private inline fun <T> typed(field: String, convert: (Any) -> T): T? {
        val value = get(field) ?: return null
        return try {
            convert(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("字段 $field 无法转换为请求的类型", error)
        }
    }

    private fun requireField(field: String): JsonObject =
        fields[field] ?: throw IllegalArgumentException("DocType $doctype 未定义字段：$field")

    private fun copyValue(value: Any?): Any? = when (value) {
        is JsonObject -> value.copy()
        is JsonArray -> JsonArray(value.encode())
        else -> value
    }

    companion object {
        /** 创建尚未持久化的新 Document，version=0 表示尚无数据库版本。 */
        fun new(doctype: String, definition: JsonObject): DynamicDocument {
            val defaults = JsonObject()
            (definition.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>().forEach { field ->
                val name = field.getString("name") ?: return@forEach
                val defaultValue = field.getValue("defaultValue") ?: return@forEach
                defaults.put(name, DocumentValueCodec.normalizeField(field, defaultValue))
            }
            return DynamicDocument(doctype, null, 0, definition.copy(), defaults)
        }

        /** 从数据库完整行构造已加载 Document；系统列不作为普通元数据字段。 */
        fun loaded(doctype: String, definition: JsonObject, row: JsonObject): DynamicDocument {
            val id = row.getString("id")?.let(UUID::fromString)
                ?: throw IllegalArgumentException("Document 缺少系统主键 id")
            val version = row.getInteger("version")
                ?: throw IllegalArgumentException("Document 缺少系统版本 version")
            val ordinary = row.copy().also {
                it.remove("id")
                it.remove("version")
                it.remove("docstatus")
                it.remove("created_at")
                it.remove("updated_at")
            }
            return DynamicDocument(doctype, id, version, definition.copy(), ordinary)
        }
    }
}
