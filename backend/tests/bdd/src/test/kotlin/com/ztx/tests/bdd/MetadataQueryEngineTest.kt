package com.ztx.tests.bdd

import com.ztx.modules.metadata.MetadataQueryEngine
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class MetadataQueryEngineTest {
    private val companyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val definition = JsonObject()
        .put("doctype", "PurchaseOrder")
        .put("table", JsonObject().put("schema", "pur").put("name", "purchase_order"))
        .put("fields", JsonArray()
            .add(JsonObject().put("name", "id").put("type", "uuid"))
            .add(JsonObject().put("name", "supplier_name").put("type", "string"))
            .add(JsonObject().put("name", "company_id").put("type", "uuid")))
        .put("list", JsonObject()
            .put("columns", JsonArray().add("id").add("supplier_name"))
            .put("filters", JsonArray().add("supplier_name"))
            .put("sort", JsonArray().add("supplier_name"))
            .put("search", JsonArray().add("supplier_name")))

    /** Given 筛选值包含 SQL 片段，When 编译列表查询，Then 值只能进入绑定参数。 */
    @Test
    fun filterValuesAreAlwaysBound() {
        val compiled = MetadataQueryEngine.compile(
            definition,
            JsonObject().put("filter", JsonObject().put("field", "supplier_name")
                .put("operator", "contains").put("value", "' OR 1=1 --")),
            "TENANT_ADMIN",
            setOf(companyId),
            emptySet(),
            emptySet()
        )
        assertFalse(compiled.selectSql.contains("OR 1=1"))
        assertEquals("' OR 1=1 --", compiled.countParameters.first())
        assertEquals(true, compiled.countSql.contains("ANY($2::uuid[])"))
    }

    /** Given 客户端提交未授权字段，When 编译查询，Then 服务端拒绝执行。 */
    @Test
    fun unknownFieldsCannotBeFiltered() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MetadataQueryEngine.compile(
                definition,
                JsonObject().put("filter", JsonObject().put("field", "password_hash")
                    .put("operator", "eq").put("value", "secret")),
                "TENANT_ADMIN",
                setOf(companyId),
                emptySet(),
                emptySet()
            )
        }
        assertEquals(true, error.message!!.contains("字段不可筛选"))
    }

    /** Given 客户端提交任意排序标识符，When 编译查询，Then 服务端拒绝该排序。 */
    @Test
    fun arbitrarySortIdentifiersAreRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MetadataQueryEngine.compile(
                definition,
                JsonObject().put("sort", JsonArray().add(JsonObject()
                    .put("field", "supplier_name; DROP TABLE purchase_order").put("direction", "asc"))),
                "TENANT_ADMIN",
                setOf(companyId),
                emptySet(),
                emptySet()
            )
        }
        assertEquals(true, error.message!!.contains("不允许按字段排序"))
    }

    /** Given 用户没有企业数据范围，When 编译查询，Then 查询条件强制为空集。 */
    @Test
    fun emptyCompanyScopeReturnsNoRows() {
        val compiled = MetadataQueryEngine.compile(definition, JsonObject(), "TENANT_USER", emptySet(), emptySet(), emptySet())
        assertEquals(true, compiled.countSql.contains("FALSE"))
    }

    /** Given 筛选 AST 包含超过总量上限的节点，When 编译查询，Then 在执行 SQL 前拒绝过量表达式。 */
    @Test
    fun rejectsFilterAstWithTooManyNodes() {
        val groups = JsonArray()
        repeat(5) {
            val conditions = JsonArray()
            repeat(50) {
                conditions.add(JsonObject().put("field", "supplier_name").put("operator", "eq").put("value", "A"))
            }
            groups.add(JsonObject().put("op", "and").put("conditions", conditions))
        }
        val oversized = JsonObject().put("op", "and").put("conditions", groups)
        val error = assertThrows(IllegalArgumentException::class.java) {
            MetadataQueryEngine.compile(
                definition,
                JsonObject().put("filter", oversized),
                "TENANT_ADMIN",
                setOf(companyId),
                emptySet(),
                emptySet()
            )
        }
        assertEquals(true, error.message!!.contains("节点总数"))
    }
}
