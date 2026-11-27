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
