package com.ztx.modules.document

import com.ztx.modules.iam.TenantPrincipal
import com.ztx.modules.metadata.MetadataQueryEngine
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 绑定当前租户身份和当前事务连接的数据访问门面。
 * 查询直接返回 JSON；Document 写入显式调用 update，不存在隐式保存。
 */
class Db(
    private val principal: TenantPrincipal,
    private val connection: SqlConnection,
    private val runtime: DocumentRuntimeSupport = DocumentRuntimeSupport()
) {
    /** 创建尚未持久化的 Document；调用方设置字段后必须显式调用 insert。 */
    fun new(doctype: String): Future<DynamicDocument> =
        runtime.loadDefinition(connection, doctype).map { definition ->
            runtime.authorize(definition, principal, "create")
            DynamicDocument.new(doctype, definition)
        }

    /** 显式插入新 Document；创建 Hook、物理写入和审计共用当前事务连接。 */
    fun insert(document: DynamicDocument): Future<DynamicDocument> {
        if (document.id != null) return Future.failedFuture("已持久化 Document 不能再次 insert")
        val definition = document.metadata()
        return try {
            runtime.authorize(definition, principal, "create")
            val values = document.toJson()
            runtime.enforceCompanyScope(definition, values, principal, requireValue = true)
            val behavior = runtime.behaviors.resolve(definition, document.doctype)
            val context = DocumentBehaviorContext(document.doctype, definition, principal, connection)
            (behavior?.beforeCreate(context, values) ?: Future.succeededFuture()).compose {
                runtime.insert(connection, definition, values, principal).compose { row ->
                    val id = UUID.fromString(row.getString("id"))
                    val afterContext = context.copy(documentId = id, after = row)
                    (behavior?.afterCreate(afterContext, row) ?: Future.succeededFuture()).compose {
                        runtime.audit(connection, principal, document.doctype, id, "CREATE", null, row).map {
                            DynamicDocument.loaded(document.doctype, definition, runtime.redact(definition, principal, row))
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            Future.failedFuture(error)
        }
    }

    fun get(doctype: String, id: UUID): Future<DynamicDocument> =
        runtime.loadDefinition(connection, doctype).compose { definition ->
            runtime.authorize(definition, principal, "read")
            runtime.selectOne(connection, definition, id, principal, lock = false, includeDeferred = false).map { row ->
                DynamicDocument.loaded(doctype, definition, runtime.redact(definition, principal, row))
            }
        }

    /** 将同一 Document Runtime 结果转换为平台核心 Kotlin 强类型模型。 */
    fun <T : Any> getAs(doctype: String, id: UUID, target: KClass<T>): Future<T> =
        get(doctype, id).map { document ->
            DocumentValueCodec.decodeAs(document.metadata(), document.toJson(), target)
        }

    fun update(document: DynamicDocument): Future<DynamicDocument> {
        val id = document.id ?: return Future.failedFuture("新建 Document 尚未持久化，不能调用 db.update")
        val definition = document.metadata()
        return try {
            runtime.authorize(definition, principal, "update")
            val changes = document.changes()
            if (changes.isEmpty) return Future.failedFuture("Document 没有待保存的修改")
            val fields = (definition.getJsonArray("fields") ?: JsonArray())
                .filterIsInstance<JsonObject>().associateBy { it.getString("name") }
            val normalized = JsonObject()
            changes.fieldNames().forEach { name ->
                val field = fields[name] ?: throw IllegalArgumentException("DocType 未定义字段：$name")
                val value = changes.getValue(name)
                if (value == null && field.getBoolean("required", false)) {
                    throw IllegalArgumentException("字段 $name 为必填项，不允许清空")
                }
                normalized.put(name, value?.let { DocumentValueCodec.normalizeField(field, it) })
            }
            runtime.enforceCompanyScope(definition, normalized, principal, requireValue = false)
            val entries = runtime.writableEntries(definition, normalized, principal, includeNulls = true)
            if (entries.size != normalized.size()) throw IllegalArgumentException("存在不可写字段")
            runtime.selectOne(connection, definition, id, principal, lock = true).compose { before ->
                if (before.getInteger("version", 1) != document.version) {
                    return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                }
                if (before.getInteger("docstatus", 0) != DocumentStatus.DRAFT.code) {
                    return@compose Future.failedFuture<DynamicDocument>("只有草稿单据可以修改")
                }
                val behavior = runtime.behaviors.resolve(definition, document.doctype)
                val context = DocumentBehaviorContext(document.doctype, definition, principal, connection, id, before)
                (behavior?.beforeUpdate(context, before, normalized) ?: Future.succeededFuture()).compose {
                    runtime.updateRow(connection, definition, id, normalized, principal, document.version).compose { after ->
                        val afterContext = context.copy(after = after)
                        (behavior?.afterUpdate(afterContext, before, after) ?: Future.succeededFuture()).compose {
                            runtime.audit(connection, principal, document.doctype, id, "UPDATE", before, after).map {
                                DynamicDocument.loaded(document.doctype, definition, runtime.redact(definition, principal, after))
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            Future.failedFuture(error)
        }
    }

    /** 返回受控列表投影 JSON，不额外包装业务 DTO。 */
    fun list(doctype: String, request: JsonObject = JsonObject()): Future<JsonObject> =
        runtime.loadDefinition(connection, doctype).compose { definition ->
            runtime.authorize(definition, principal, "read")
            val compiled = MetadataQueryEngine.compile(
                definition, request, principal.role,
                principal.companyIds, principal.bookIds, principal.organizationUnitIds
            )
            connection.preparedQuery(compiled.countSql).execute(Tuple.from(compiled.countParameters))
                .compose { countRows ->
                    val total = countRows.first().getLong("total")
                    connection.preparedQuery(compiled.selectSql).execute(Tuple.from(compiled.selectParameters))
                        .map { rows ->
                            JsonObject()
                                .put("items", JsonArray(rows.map { it.toJson() }))
                                .put("total", total)
                                .put("page", request.getInteger("page", 1).coerceIn(1, 100000))
                                .put("pageSize", request.getInteger("pageSize", 20).coerceIn(1, 200))
                        }
                }
        }

    fun exists(doctype: String, id: UUID): Future<Boolean> =
        runtime.loadDefinition(connection, doctype).compose { definition ->
            runtime.authorize(definition, principal, "read")
            val parameters = mutableListOf<Any?>(id)
            val where = runtime.scopeWhere(definition, principal, parameters)
            val sql = "SELECT EXISTS (SELECT 1 FROM " + runtime.tableSql(definition) +
                " WHERE \"id\"=$1" + where + ") AS present"
            connection.preparedQuery(sql).execute(Tuple.from(parameters))
                .map { it.first().getBoolean("present") }
        }

    /** 一次批量读取多个 Document，不逐条发起 SELECT，避免 N+1。 */
    fun getAll(doctype: String, ids: Collection<UUID>): Future<List<DynamicDocument>> {
        if (ids.isEmpty()) return Future.succeededFuture(emptyList())
        return runtime.loadDefinition(connection, doctype).compose { definition ->
            runtime.authorize(definition, principal, "read")
            val parameters = mutableListOf<Any?>(ids.distinct().toTypedArray())
            val where = runtime.scopeWhere(definition, principal, parameters)
            val sql = "SELECT " + runtime.documentProjection(definition, includeDeferred = false) +
                " FROM " + runtime.tableSql(definition) + " WHERE \"id\" = ANY($1::uuid[])" + where
            connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
                rows.map { row ->
                    val json = row.toJson()
                    DynamicDocument.loaded(doctype, definition, runtime.redact(definition, principal, json))
                }
            }
        }
    }

    /** Db 已绑定一个活动事务连接；transaction 块复用该连接，不创建嵌套事务。 */
    fun <T> transaction(block: (Db) -> Future<T>): Future<T> = block(this)

    fun submit(document: DynamicDocument): Future<DynamicDocument> = action(document, "submit")
    fun cancel(document: DynamicDocument): Future<DynamicDocument> = action(document, "cancel")
    fun void(document: DynamicDocument): Future<DynamicDocument> = action(document, "void")

    fun action(document: DynamicDocument, action: String): Future<DynamicDocument> {
        val id = document.id ?: return Future.failedFuture("新建 Document 尚未持久化")
        val definition = document.metadata()
        return try {
            runtime.authorize(definition, principal, action)
            runtime.selectOne(connection, definition, id, principal, lock = true).compose { before ->
                if (before.getInteger("version", 1) != document.version) {
                    return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                }
                val behavior = runtime.behaviors.resolve(definition, document.doctype)
                val hookContext = DocumentBehaviorContext(document.doctype, definition, principal, connection, id, before, action = action)
                (behavior?.beforeAction(hookContext, before, action) ?: Future.succeededFuture()).compose {
                    val next = DocumentLifecycle.transition(before.getInteger("docstatus", 0), action)
                    val parameters = mutableListOf<Any?>(next, id, before.getInteger("docstatus", 0), document.version)
                    val where = runtime.scopeWhere(definition, principal, parameters)
                    val sql = "UPDATE " + runtime.tableSql(definition) +
                        " SET \"docstatus\"=$1,\"version\"=\"version\"+1,\"updated_at\"=clock_timestamp()" +
                        " WHERE \"id\"=$2 AND \"docstatus\"=$3 AND \"version\"=$4" + where + " RETURNING *"
                    connection.preparedQuery(sql).execute(Tuple.from(parameters)).compose { rows ->
                        val after = rows.firstOrNull()?.toJson()
                            ?: return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                        val afterContext = hookContext.copy(after = after)
                        (behavior?.afterAction(afterContext, before, after, action) ?: Future.succeededFuture())
                            .compose {
                                runtime.audit(connection, principal, document.doctype, id, action.uppercase(), before, after)
                                    .map {
                                        DynamicDocument.loaded(document.doctype, definition, runtime.redact(definition, principal, after))
                                    }
                            }
                    }
                }
            }
        } catch (error: Throwable) {
            Future.failedFuture(error)
        }
    }

    fun getValue(doctype: String, id: UUID, field: String): Future<Any?> =
        runtime.loadDefinition(connection, doctype).compose { definition ->
            val metadata = (definition.getJsonArray("fields") ?: JsonArray())
                .filterIsInstance<JsonObject>().firstOrNull { it.getString("name") == field }
                ?: return@compose Future.failedFuture<Any?>("DocType 未定义字段：$field")
            val readRoles = metadata.getJsonArray("readRoles")
            if (readRoles != null && !readRoles.isEmpty &&
                readRoles.none { it == principal.role || it == "TENANT_ADMIN" }) {
                return@compose Future.failedFuture<Any?>("无权读取字段：$field")
            }
            if (!metadata.getBoolean("loadOnDemand", false)) {
                return@compose get(doctype, id).map { it[field] }
            }
            runtime.authorize(definition, principal, "read")
            val parameters = mutableListOf<Any?>(id)
            val where = runtime.scopeWhere(definition, principal, parameters)
            val sql = "SELECT \"$field\" FROM " + runtime.tableSql(definition) + " WHERE \"id\"=$1" + where
            connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
                val row = rows.firstOrNull() ?: throw IllegalArgumentException("单据不存在或无权访问")
                row.getValue(field)
            }
        }
}


/**
 * 为普通业务执行上下文开启一个事务并创建作用域 Db。
 * 不持有全局租户状态；每次调用都绑定调用者提供的可信 TenantPrincipal。
 */
class DbProvider(private val runtime: DocumentRuntimeSupport = DocumentRuntimeSupport()) {
    private data class TransactionResult<T>(val value: T)

    /** 支持 nullable 查询结果；使用非空包装值跨过 Vert.x 事务泛型边界，事务完成后再还原结果。 */
    fun <T> transaction(principal: TenantPrincipal, block: (Db) -> Future<T>): Future<T> =
        principal.database.withTransaction<Any> { connection ->
            try {
                block(Db(principal, connection, runtime)).map { result -> TransactionResult(result) as Any }
            } catch (error: Throwable) {
                Future.failedFuture<Any>(error)
            }
        }.map { result -> (result as TransactionResult<T>).value }
}
