package com.ztx.modules.document

import com.ztx.modules.iam.TenantPrincipal
import com.ztx.modules.metadata.MetadataQueryEngine
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.UUID

/**
 * 绑定当前租户身份和当前事务连接的数据访问门面。
 * 查询直接返回 JSON；Document 写入显式调用 update，不存在隐式保存。
 */
class Db(
    private val principal: TenantPrincipal,
    private val connection: SqlConnection,
    private val routes: DocumentRuntimeRoutes = DocumentRuntimeRoutes()
) {
    fun new(doctype: String): Future<DynamicDocument> =
        routes.loadDefinition(connection, doctype).map { definition ->
            routes.authorize(definition, principal, "create")
            DynamicDocument.new(doctype, definition)
        }

    fun get(doctype: String, id: UUID): Future<DynamicDocument> =
        routes.loadDefinition(connection, doctype).compose { definition ->
            routes.authorize(definition, principal, "read")
            routes.selectOne(connection, definition, id, principal, lock = false).map { row ->
                DynamicDocument.loaded(doctype, definition, routes.redact(definition, principal, row))
            }
        }

    fun update(document: DynamicDocument): Future<DynamicDocument> {
        val id = document.id ?: return Future.failedFuture("新建 Document 尚未持久化，不能调用 db.update")
        val definition = document.metadata()
        return try {
            routes.authorize(definition, principal, "update")
            val changes = document.changes()
            if (changes.isEmpty) return Future.failedFuture("Document 没有待保存的修改")
            val fields = (definition.getJsonArray("fields") ?: JsonArray())
                .filterIsInstance<JsonObject>().associateBy { it.getString("name") }
            val normalized = JsonObject()
            changes.fieldNames().forEach { name ->
                val field = fields[name] ?: throw IllegalArgumentException("DocType 未定义字段：$name")
                val value = changes.getValue(name) ?: throw IllegalArgumentException("字段 $name 不支持写入 null")
                normalized.put(name, DocumentValueCodec.normalizeField(field, value))
            }
            routes.enforceCompanyScope(definition, normalized, principal, requireValue = false)
            val entries = routes.writableEntries(definition, normalized, principal)
            if (entries.size != normalized.size()) throw IllegalArgumentException("存在不可写字段")
            routes.selectOne(connection, definition, id, principal, lock = true).compose { before ->
                if (before.getInteger("version", 1) != document.version) {
                    return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                }
                if (before.getInteger("docstatus", 0) != DocumentStatus.DRAFT.code) {
                    return@compose Future.failedFuture<DynamicDocument>("只有草稿单据可以修改")
                }
                routes.updateRow(connection, definition, id, normalized, principal, document.version).compose { after ->
                    routes.audit(connection, principal, document.doctype, id, "UPDATE", before, after).map {
                        DynamicDocument.loaded(document.doctype, definition, routes.redact(definition, principal, after))
                    }
                }
            }
        } catch (error: Throwable) {
            Future.failedFuture(error)
        }
    }

    /** 返回受控列表投影 JSON，不额外包装业务 DTO。 */
    fun list(doctype: String, request: JsonObject = JsonObject()): Future<JsonObject> =
        routes.loadDefinition(connection, doctype).compose { definition ->
            routes.authorize(definition, principal, "read")
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
        routes.loadDefinition(connection, doctype).compose { definition ->
            routes.authorize(definition, principal, "read")
            val parameters = mutableListOf<Any?>(id)
            val where = routes.scopeWhere(definition, principal, parameters)
            val sql = "SELECT EXISTS (SELECT 1 FROM " + routes.tableSql(definition) +
                " WHERE \"id\"=$1" + where + ") AS present"
            connection.preparedQuery(sql).execute(Tuple.from(parameters))
                .map { it.first().getBoolean("present") }
        }

    /** 一次批量读取多个 Document，不逐条发起 SELECT，避免 N+1。 */
    fun getAll(doctype: String, ids: Collection<UUID>): Future<List<DynamicDocument>> {
        if (ids.isEmpty()) return Future.succeededFuture(emptyList())
        return routes.loadDefinition(connection, doctype).compose { definition ->
            routes.authorize(definition, principal, "read")
            val parameters = mutableListOf<Any?>(ids.distinct().toTypedArray())
            val where = routes.scopeWhere(definition, principal, parameters)
            val sql = "SELECT * FROM " + routes.tableSql(definition) +
                " WHERE \"id\" = ANY($1::uuid[])" + where
            connection.preparedQuery(sql).execute(Tuple.from(parameters)).map { rows ->
                rows.map { row ->
                    val json = row.toJson()
                    DynamicDocument.loaded(doctype, definition, routes.redact(definition, principal, json))
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
            routes.authorize(definition, principal, action)
            routes.selectOne(connection, definition, id, principal, lock = true).compose { before ->
                if (before.getInteger("version", 1) != document.version) {
                    return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                }
                val behavior = routes.behaviors.resolve(definition, document.doctype)
                val hookContext = DocumentBehaviorContext(document.doctype, definition, principal, connection, id, before, action = action)
                (behavior?.beforeAction(hookContext, before, action) ?: Future.succeededFuture()).compose {
                    val next = DocumentLifecycle.transition(before.getInteger("docstatus", 0), action)
                    val parameters = mutableListOf<Any?>(next, id, before.getInteger("docstatus", 0), document.version)
                    val where = routes.scopeWhere(definition, principal, parameters)
                    val sql = "UPDATE " + routes.tableSql(definition) +
                        " SET \"docstatus\"=$1,\"version\"=\"version\"+1,\"updated_at\"=clock_timestamp()" +
                        " WHERE \"id\"=$2 AND \"docstatus\"=$3 AND \"version\"=$4" + where + " RETURNING *"
                    connection.preparedQuery(sql).execute(Tuple.from(parameters)).compose { rows ->
                        val after = rows.firstOrNull()?.toJson()
                            ?: return@compose Future.failedFuture<DynamicDocument>(DocumentVersionConflictException())
                        val afterContext = hookContext.copy(after = after)
                        (behavior?.afterAction(afterContext, before, after, action) ?: Future.succeededFuture())
                            .compose {
                                routes.audit(connection, principal, document.doctype, id, action.uppercase(), before, after)
                                    .map {
                                        DynamicDocument.loaded(document.doctype, definition, routes.redact(definition, principal, after))
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
        routes.loadDefinition(connection, doctype).compose { definition ->
            val metadata = (definition.getJsonArray("fields") ?: JsonArray())
                .filterIsInstance<JsonObject>().firstOrNull { it.getString("name") == field }
                ?: return@compose Future.failedFuture<Any?>("DocType 未定义字段：$field")
            val readRoles = metadata.getJsonArray("readRoles")
            if (readRoles != null && !readRoles.isEmpty &&
                readRoles.none { it == principal.role || it == "TENANT_ADMIN" }) {
                return@compose Future.failedFuture<Any?>("无权读取字段：$field")
            }
            get(doctype, id).map { it[field] }
        }
}
