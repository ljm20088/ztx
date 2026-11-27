package com.ztx.modules.document

import com.ztx.modules.iam.TenantPrincipal
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import java.util.ServiceLoader
import java.util.UUID

/**
 * 可信部署代码的 Document 扩展点。
 *
 * 扩展与通用写入、状态迁移和审计共用同一 SqlConnection；
 * Hook 返回失败 Future 或抛出异常都会使当前事务回滚。
 */
enum class DocumentExtensionMode {
    METADATA_WITH_KOTLIN_FILE,
    KOTLIN_IMPLEMENTATION
}

data class DocumentBehaviorContext(
    val doctype: String,
    val definition: JsonObject,
    val principal: TenantPrincipal,
    val connection: SqlConnection,
    val documentId: UUID? = null,
    val before: JsonObject? = null,
    val after: JsonObject? = null,
    val action: String? = null
)

/**
 * 一个 DocType 对应一个已编译并随服务部署的 Kotlin 实现。
 * 普通扩展只需覆盖需要的 Hook；专用实现可以覆盖完整 Hook 集合。
 */
interface DocumentBehavior {
    val doctype: String
    val mode: DocumentExtensionMode

    fun beforeCreate(context: DocumentBehaviorContext, document: JsonObject): Future<Void> =
        Future.succeededFuture()

    fun afterCreate(context: DocumentBehaviorContext, document: JsonObject): Future<Void> =
        Future.succeededFuture()

    fun beforeUpdate(
        context: DocumentBehaviorContext,
        before: JsonObject,
        changes: JsonObject
    ): Future<Void> = Future.succeededFuture()

    fun afterUpdate(
        context: DocumentBehaviorContext,
        before: JsonObject,
        after: JsonObject
    ): Future<Void> = Future.succeededFuture()

    fun beforeAction(
        context: DocumentBehaviorContext,
        before: JsonObject,
        action: String
    ): Future<Void> = Future.succeededFuture()

    fun afterAction(
        context: DocumentBehaviorContext,
        before: JsonObject,
        after: JsonObject,
        action: String
    ): Future<Void> = Future.succeededFuture()
}

/** 只装载服务端 classpath 中显式注册的可信实现，不编译或执行租户提交的源码。 */
class DocumentBehaviorRegistry(implementations: Iterable<DocumentBehavior>) {
    private val byDocType: Map<String, DocumentBehavior>

    init {
        val indexed = implementations.toList()
        require(indexed.all { it.doctype.matches(Regex("[a-z][a-z0-9_]{0,62}")) }) {
            "Document 扩展必须声明合法 DocType"
        }
        require(indexed.map { it.doctype }.distinct().size == indexed.size) {
            "同一个 DocType 不能注册多个 Document 扩展"
        }
        byDocType = indexed.associateBy { it.doctype }
    }

    fun resolve(definition: JsonObject, doctype: String): DocumentBehavior? {
        val extension = definition.getJsonObject("extension") ?: return null
        val mode = runCatching {
            DocumentExtensionMode.valueOf(extension.getString("mode", ""))
        }.getOrElse { throw IllegalArgumentException("Document 扩展模式不受支持") }
        val implementation = extension.getString("implementation")
            ?: throw IllegalArgumentException("Document 扩展必须指定 implementation")
        val behavior = byDocType[doctype]
            ?: throw IllegalArgumentException("DocType $doctype 声明了扩展，但服务端未注册对应 Kotlin 实现")
        if (behavior.mode != mode) throw IllegalArgumentException("DocType $doctype 的扩展模式与 Kotlin 实现不一致")
        if (behavior::class.qualifiedName != implementation) {
            throw IllegalArgumentException("DocType $doctype 的 Kotlin 实现标识不匹配")
        }
        return behavior
    }

    companion object {
        fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): DocumentBehaviorRegistry =
            DocumentBehaviorRegistry(ServiceLoader.load(DocumentBehavior::class.java, classLoader).toList())
    }
}
