package com.ztx.tests.bdd

import com.ztx.modules.metadata.MetadataDefinitionValidator
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataDefinitionValidatorTest {
    private fun validDefinition() = JsonObject()
        .put("doctype", "SampleItem")
        .put("scope", "TENANT")
        .put("table", JsonObject().put("schema", "org").put("name", "sample_item"))
        .put("fields", JsonArray()
            .add(JsonObject().put("name", "code").put("type", "string").put("required", true).put("unique", true))
            .add(JsonObject().put("name", "name").put("type", "string")))
        .put("form", JsonObject().put("fields", JsonArray().add("code").add("name")))
        .put("list", JsonObject().put("columns", JsonArray().add("code").add("name"))
            .put("filters", JsonArray().add("code")).put("sort", JsonArray().add("name")))
        .put("permissions", JsonObject().put("read", JsonArray().add("reader")).put("update", JsonArray().add("manager")))

    // Given 定义包含合法字段、表映射和页面配置，When 执行元数据校验，Then 不产生诊断错误。
    @Test
    fun acceptsValidTenantDefinition() {
        assertTrue(MetadataDefinitionValidator.validate(validDefinition()).isEmpty())
    }

    // Given 定义包含重复字段和不存在的列表列，When 执行校验，Then 返回可定位的稳定错误。
    @Test
    fun rejectsDuplicateFieldsAndUnknownLayoutReferences() {
        val definition = validDefinition()
        definition.getJsonArray("fields").add(JsonObject().put("name", "code").put("type", "string"))
        definition.getJsonObject("list").put("columns", JsonArray().add("missing_field"))
        val codes = MetadataDefinitionValidator.validate(definition).map { it.code }
        assertTrue("DUPLICATE_FIELD" in codes)
        assertTrue("UNKNOWN_LAYOUT_FIELD" in codes)
    }

    // Given 租户管理员试图声明平台级范围，When 执行校验，Then 拒绝跨越元数据作用域边界。
    @Test
    fun rejectsPlatformScopeInTenantDefinitions() {
        val definition = validDefinition().put("scope", "PLATFORM")
        assertTrue(MetadataDefinitionValidator.validate(definition).any { it.code == "SCOPE_NOT_ALLOWED" })
    }

    // Given 字段定义包含脚本注入内容，When 执行校验，Then 拒绝不受控内容。
    @Test
    fun rejectsScriptContent() {
        val definition = validDefinition().put("label", "<script>alert(1)</script>")
        assertTrue(MetadataDefinitionValidator.validate(definition).any { it.code == "UNSAFE_CONTENT" })
    }

    // Given 两个 DocType 相互引用，When 校验其中一个定义，Then 识别循环引用。
    @Test
    fun rejectsCircularReferences() {
        val definition = validDefinition()
        definition.getJsonArray("fields").add(JsonObject()
            .put("name", "parent_id").put("type", "reference").put("target", "OtherItem"))
        val other = JsonObject().put("fields", JsonArray().add(JsonObject()
            .put("name", "sample_id").put("type", "reference").put("target", "SampleItem")))
        val diagnostics = MetadataDefinitionValidator.validate(definition, mapOf("OtherItem" to other))
        assertTrue(diagnostics.any { it.code == "REFERENCE_CYCLE" })
    }

    // Given 引用字段指向不存在的 DocType，When 执行校验，Then 返回引用目标错误。
    @Test
    fun rejectsMissingReferenceTargets() {
        val definition = validDefinition()
        definition.getJsonArray("fields").add(JsonObject()
            .put("name", "parent_id").put("type", "reference").put("target", "MissingItem"))
        assertTrue(MetadataDefinitionValidator.validate(definition).any { it.code == "REFERENCE_NOT_FOUND" })
    }
}


    // Given DocType 声明合法的 Kotlin 扩展，When 校验元数据，Then 接受受控的扩展模式和完整类名。
    @Test
    fun acceptsRegisteredKotlinExtensionDeclaration() {
        val definition = validDefinition().put("extension", JsonObject()
            .put("mode", "METADATA_WITH_KOTLIN_FILE")
            .put("implementation", "com.example.purchase.PurchaseOrderBehavior"))
        assertTrue(MetadataDefinitionValidator.validate(definition).isEmpty())
    }

    // Given DocType 声明不受支持的扩展模式或不完整类名，When 校验元数据，Then 拒绝该声明。
    @Test
    fun rejectsInvalidKotlinExtensionDeclaration() {
        val definition = validDefinition().put("extension", JsonObject()
            .put("mode", "DYNAMIC_SCRIPT")
            .put("implementation", "PurchaseOrderBehavior"))
        val codes = MetadataDefinitionValidator.validate(definition).map { it.code }
        assertTrue("INVALID_EXTENSION_MODE" in codes)
        assertTrue("INVALID_EXTENSION_IMPLEMENTATION" in codes)
    }

    // Given 单据生命周期权限声明作废动作，When 校验元数据，Then 允许 void 权限配置。
    @Test
    fun acceptsVoidPermissionAction() {
        val definition = validDefinition().put("permissions", JsonObject()
            .put("read", JsonArray().add("reader"))
            .put("void", JsonArray().add("manager")))
        assertTrue(MetadataDefinitionValidator.validate(definition).isEmpty())
    }


    // Given 元数据声明系统内置大写角色，When 执行字段与功能权限校验，Then 接受合法角色标识。
    @Test
    fun acceptsUppercaseBuiltInRoles() {
        val definition = validDefinition()
        definition.getJsonArray("fields").getJsonObject(0)
            .put("readRoles", JsonArray().add("TENANT_ADMIN"))
            .put("writeRoles", JsonArray().add("TENANT_USER"))
        definition.put("permissions", JsonObject().put("read", JsonArray().add("TENANT_ADMIN")))
        assertTrue(MetadataDefinitionValidator.validate(definition).isEmpty())
    }
