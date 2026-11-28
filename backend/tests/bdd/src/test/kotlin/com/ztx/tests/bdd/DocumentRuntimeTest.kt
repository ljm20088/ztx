package com.ztx.tests.bdd

import com.ztx.modules.document.Db
import com.ztx.modules.document.DbProvider
import com.ztx.modules.document.DocumentRuntimeRoutes
import com.ztx.modules.document.DocumentRuntimeSupport
import com.ztx.modules.document.DocumentLifecycle
import com.ztx.modules.document.DocumentValueCodec
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class DocumentRuntimeTest {
    /** Given 统一数据访问门面与 HTTP 路由，When 检查类型依赖，Then Db 不得反向依赖 Routes。 */
    @Test
    fun dbIsIndependentFromHttpRoutes() {
        assertTrue(Db::class.java.declaredFields.any { it.type == DocumentRuntimeSupport::class.java })
        assertTrue(Db::class.java.declaredFields.none {
            DocumentRuntimeRoutes::class.java.isAssignableFrom(it.type)
        })
        assertTrue(DocumentRuntimeRoutes::class.java.declaredFields.any { it.type == DbProvider::class.java })
        assertTrue(DocumentRuntimeRoutes::class.java.declaredFields.any { it.type == DocumentRuntimeSupport::class.java })
    }

    /** Given 字段只允许 TENANT_ADMIN，When 普通角色请求，Then 不得把管理员角色当成通配符。 */
    @Test
    fun fieldReadRolesAreAnAllowlistWithExplicitAdminBypass() {
        val runtime = DocumentRuntimeSupport()
        val adminOnly = JsonArray().add("TENANT_ADMIN")
        assertTrue(!runtime.canReadField(adminOnly, "PURCHASING"))
        assertTrue(runtime.canReadField(adminOnly, "TENANT_ADMIN"))
        assertTrue(runtime.canReadField(JsonArray().add("PURCHASING"), "PURCHASING"))
        assertTrue(runtime.canReadField(JsonArray(), "PURCHASING"))
    }

    /** Given 元数据定义金额、日期和嵌套明细，When 接收 JSON，Then 转换为精确 Kotlin 类型。 */
    @Test
    fun jsonValuesAreConvertedByMetadataTypes() {
        val definition = JsonObject().put("fields", JsonArray()
            .add(JsonObject().put("name", "supplier_id").put("type", "uuid"))
            .add(JsonObject().put("name", "order_date").put("type", "date"))
            .add(JsonObject().put("name", "start_time").put("type", "time"))
            .add(JsonObject().put("name", "total").put("type", "money"))
            .add(JsonObject().put("name", "lines").put("type", "table").put("childFields", JsonArray()
                .add(JsonObject().put("name", "quantity").put("type", "decimal").put("required", true)))))
        val input = JsonObject().put("supplier_id", "00000000-0000-0000-0000-000000000001")
            .put("order_date", "2026-11-27").put("start_time", "08:30:15").put("total", "123456789.123456")
            .put("lines", JsonArray().add(JsonObject().put("quantity", "2.500")))
        val result = DocumentValueCodec.decode(definition, input)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), result.getValue("supplier_id"))
        assertEquals(LocalDate.parse("2026-11-27"), result.getValue("order_date"))
        assertEquals(LocalTime.parse("08:30:15"), result.getValue("start_time"))
        assertEquals(BigDecimal("123456789.123456"), result.getValue("total"))
        assertEquals(BigDecimal("2.500"), result.getJsonArray("lines").getJsonObject(0).getValue("quantity"))
    }

    /** Given JSON 缺少必填字段，When 转换 Document，Then 返回稳定字段错误。 */
    @Test
    fun missingRequiredFieldIsRejected() {
        val definition = JsonObject().put("fields", JsonArray()
            .add(JsonObject().put("name", "code").put("type", "string").put("required", true)))
        val error = assertThrows(IllegalArgumentException::class.java) {
            DocumentValueCodec.decode(definition, JsonObject())
        }
        assertEquals("字段 code 为必填项", error.message)
    }

    /** Given 可选字段显式传入 null，When 解码更新请求，Then 保留 null 以支持清空字段。 */
    @Test
    fun preservesExplicitNullForOptionalFields() {
        val definition = JsonObject().put("fields", JsonArray()
            .add(JsonObject().put("name", "remark").put("type", "string")))
        val decoded = DocumentValueCodec.decode(definition, JsonObject().put("remark", null), applyDefaults = false)
        assertTrue(decoded.containsKey("remark"))
        assertEquals(null, decoded.getValue("remark"))
    }

    /** Given 单据处于草稿，When 执行提交动作，Then 状态变为已提交。 */
    @Test
    fun draftCanBeSubmitted() {
        assertEquals(1, DocumentLifecycle.transition(0, "submit"))
    }

    /** Given 单据已经提交，When 再次提交，Then 拒绝非法状态迁移。 */
    @Test
    fun submittedDocumentCannotBeSubmittedAgain() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentLifecycle.transition(1, "submit")
        }
    }
}


class DocumentBehaviorRegistryTest {
    private class PurchaseOrderBehavior : com.ztx.modules.document.DocumentBehavior {
        override val doctype = "PurchaseOrder"
        override val mode = com.ztx.modules.document.DocumentExtensionMode.METADATA_WITH_KOTLIN_FILE
    }

    /** Given DocType 声明已注册的单文件 Kotlin 扩展，When 解析运行时实现，Then 返回对应行为对象。 */
    @Test
    fun resolvesRegisteredSingleFileBehavior() {
        val behavior = PurchaseOrderBehavior()
        val registry = com.ztx.modules.document.DocumentBehaviorRegistry(listOf(behavior))
        val definition = JsonObject().put("extension", JsonObject()
            .put("mode", "METADATA_WITH_KOTLIN_FILE")
            .put("implementation", PurchaseOrderBehavior::class.qualifiedName))
        assertEquals(behavior, registry.resolve(definition, "PurchaseOrder"))
    }

    /** Given DocType 声明的扩展模式与已注册实现不同，When 解析扩展，Then 拒绝执行不匹配实现。 */
    @Test
    fun rejectsExtensionModeMismatch() {
        val registry = com.ztx.modules.document.DocumentBehaviorRegistry(listOf(PurchaseOrderBehavior()))
        val definition = JsonObject().put("extension", JsonObject()
            .put("mode", "KOTLIN_IMPLEMENTATION")
            .put("implementation", PurchaseOrderBehavior::class.qualifiedName))
        assertThrows(IllegalArgumentException::class.java) {
            registry.resolve(definition, "PurchaseOrder")
        }
    }

    /** Given 两个 Kotlin 实现声明同一 DocType，When 建立扩展注册表，Then 拒绝歧义注册。 */
    @Test
    fun rejectsDuplicateDoctypeImplementations() {
        assertThrows(IllegalArgumentException::class.java) {
            com.ztx.modules.document.DocumentBehaviorRegistry(
                listOf(PurchaseOrderBehavior(), PurchaseOrderBehavior())
            )
        }
    }
}


class KotlinImplementationRegistryTest {
    private class SpecializedPurchaseOrder : com.ztx.modules.document.DocumentBehavior {
        override val doctype = "PurchaseOrder"
        override val mode = com.ztx.modules.document.DocumentExtensionMode.KOTLIN_IMPLEMENTATION
    }

    /** Given DocType 采用 Kotlin 专用实现模式，When 解析已注册类，Then 允许使用 PascalCase DocType 标识。 */
    @Test
    fun resolvesDedicatedKotlinImplementation() {
        val behavior = SpecializedPurchaseOrder()
        val registry = com.ztx.modules.document.DocumentBehaviorRegistry(listOf(behavior))
        val definition = JsonObject().put("extension", JsonObject()
            .put("mode", "KOTLIN_IMPLEMENTATION")
            .put("implementation", SpecializedPurchaseOrder::class.qualifiedName))
        assertEquals(behavior, registry.resolve(definition, "PurchaseOrder"))
    }
}

data class TypedPurchaseOrder(
    val supplierId: UUID,
    val orderDate: LocalDate,
    val total: BigDecimal,
    val lines: List<TypedPurchaseOrderLine>
)

data class TypedPurchaseOrderLine(
    val itemCode: String,
    val quantity: BigDecimal,
    val deliveryDate: LocalDate?
)

class DocumentTypedCodecTest {
    /** Given 元数据定义订单及嵌套明细，When 转换为 Kotlin data class，Then 顶层和明细均为强类型对象。 */
    @Test
    fun convertsNestedJsonToTypedKotlinObjects() {
        val definition = JsonObject().put("fields", JsonArray()
            .add(JsonObject().put("name", "supplier_id").put("type", "uuid").put("required", true))
            .add(JsonObject().put("name", "order_date").put("type", "date").put("required", true))
            .add(JsonObject().put("name", "total").put("type", "money").put("required", true))
            .add(JsonObject().put("name", "lines").put("type", "table").put("required", true)
                .put("childFields", JsonArray()
                    .add(JsonObject().put("name", "item_code").put("type", "string").put("required", true))
                    .add(JsonObject().put("name", "quantity").put("type", "decimal").put("required", true))
                    .add(JsonObject().put("name", "delivery_date").put("type", "date")))))
        val supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val input = JsonObject().put("supplier_id", supplierId.toString())
            .put("order_date", "2026-11-27").put("total", "123456789.123456")
            .put("lines", JsonArray().add(JsonObject()
                .put("item_code", "MAT-001").put("quantity", "2.500").put("delivery_date", "2026-12-01")))

        val result = DocumentValueCodec.decodeAs(definition, input, TypedPurchaseOrder::class)

        assertEquals(supplierId, result.supplierId)
        assertEquals(LocalDate.parse("2026-11-27"), result.orderDate)
        assertEquals(BigDecimal("123456789.123456"), result.total)
        assertEquals(1, result.lines.size)
        assertEquals("MAT-001", result.lines.single().itemCode)
        assertEquals(BigDecimal("2.500"), result.lines.single().quantity)
        assertEquals(LocalDate.parse("2026-12-01"), result.lines.single().deliveryDate)
    }
}


class ServiceLoadedDocumentBehavior : com.ztx.modules.document.DocumentBehavior {
    override val doctype = "ServiceLoadedProbe"
    override val mode = com.ztx.modules.document.DocumentExtensionMode.KOTLIN_IMPLEMENTATION
}

class DocumentBehaviorServiceLoaderTest {
    /** Given Kotlin 扩展通过 ServiceLoader 服务描述文件注册，When 启动注册表，Then 可按 DocType 和实现类解析扩展。 */
    @Test
    fun loadsRegisteredProviderFromServiceDescriptor() {
        val registry = com.ztx.modules.document.DocumentBehaviorRegistry.load()
        val definition = JsonObject().put("extension", JsonObject()
            .put("mode", "KOTLIN_IMPLEMENTATION")
            .put("implementation", ServiceLoadedDocumentBehavior::class.qualifiedName))
        assertEquals("ServiceLoadedProbe", registry.resolve(definition, "ServiceLoadedProbe")?.doctype)
    }
}


class DynamicDocumentTest {
    /** Given 元数据定义动态字段，When 通过下标读写，Then 类型转换正确且仅记录内存变更。 */
    @Test
    fun supportsBracketFieldAccessAndTracksChangesWithoutPersistence() {
        val definition = JsonObject().put("fields", JsonArray()
            .add(JsonObject().put("name", "code").put("type", "string").put("required", true))
            .add(JsonObject().put("name", "total").put("type", "money"))
            .add(JsonObject().put("name", "order_date").put("type", "date"))
            .add(JsonObject().put("name", "locked").put("type", "string").put("readOnly", true))
            .add(JsonObject().put("name", "status").put("type", "string").put("defaultValue", "DRAFT")))
        val document = com.ztx.modules.document.DynamicDocument.new("PurchaseOrder", definition)

        document["code"] = "PO-001"
        document["total"] = "123.450"
        document["order_date"] = "2026-11-28"

        assertEquals("PO-001", document.getString("code"))
        assertEquals(BigDecimal("123.450"), document.getDecimal("total"))
        assertEquals(LocalDate.parse("2026-11-28"), document.getDate("order_date"))
        assertEquals(0, document.version)
        assertEquals(null, document.id)
        assertEquals("DRAFT", document.getString("status"))
        assertEquals(setOf("code", "total", "order_date"), document.dirtyFields())
        assertEquals("PO-001", document.changes().getString("code"))
        assertThrows(IllegalArgumentException::class.java) { document["unknown"] = "x" }
        assertThrows(IllegalArgumentException::class.java) { document["locked"] = "x" }
    }

    /** Given 已加载 Document，When 修改嵌套 JSON 的读取副本，Then 不会绕过下标赋值污染原对象。 */
    @Test
    fun returnsDefensiveCopiesForNestedValues() {
        val definition = JsonObject().put("fields", JsonArray()
            .add(JsonObject().put("name", "details").put("type", "json")))
        val original = JsonObject().put("id", UUID.randomUUID().toString()).put("version", 3)
            .put("details", JsonObject().put("note", "original"))
        val document = com.ztx.modules.document.DynamicDocument.loaded("Probe", definition, original)

        val read = document["details"] as JsonObject
        read.put("note", "mutated")

        assertEquals("original", (document["details"] as JsonObject).getString("note"))
        assertEquals(3, document.version)
        assertEquals(emptySet<String>(), document.dirtyFields())
    }
}
