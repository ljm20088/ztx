package com.ztx.modules.metadata

import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.modules.iam.TenantPrincipal
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.security.MessageDigest
import java.util.UUID

/**
 * 租户元数据管理 API。租户身份来自全局认证守卫，不接受请求体中的租户或数据库标识。
 * 平台级元数据不通过租户数据库 API 暴露；租户接口始终限定 scope=TENANT。
 */
class MetadataRoutes(private val schemaMigrations: SchemaMigrationService = SchemaMigrationService()) {
    fun register(router: Router) {
        router.route("/api/v1/tenant/metadata/*").handler(BodyHandler.create().setBodyLimit(512 * 1024))
        router.get("/api/v1/tenant/metadata/doctypes").handler(::list)
        router.post("/api/v1/tenant/metadata/doctypes").handler(::create)
        router.get("/api/v1/tenant/metadata/doctypes/:doctype/history").handler(::history)
        router.post("/api/v1/tenant/metadata/doctypes/:doctype/copy").handler(::copy)
        router.post("/api/v1/tenant/metadata/doctypes/:doctype/validate").handler(::validate)
        router.get("/api/v1/tenant/metadata/doctypes/:doctype/versions/:version").handler(::getVersion)
        router.get("/api/v1/tenant/metadata/doctypes/:doctype").handler(::getLatest)
        router.put("/api/v1/tenant/metadata/doctypes/:doctype").handler(::update)
    }

    private fun list(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT DISTINCT ON (doctype) id,doctype,version,status,checksum,created_at,updated_at
                   FROM meta.document_definition WHERE scope='TENANT'
                   ORDER BY doctype,version DESC"""
            ).execute().map { rows -> JsonArray(rows.map { it.toJson() }) }
        }.onSuccess { send(context, JsonObject().put("items", it)) }
            .onFailure { fail(context, 503, "读取元数据定义失败") }
    }

    private fun create(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        val definition = body(context)
        if (definition.getString("scope", "TENANT") != "TENANT") return@withMetadataAdmin fail(context, 403, "租户管理员不能创建平台级元数据")
        val doctype = definition.getString("doctype", "").trim()
        if (doctype.isBlank()) return@withMetadataAdmin fail(context, 400, "DocType 标识不能为空")
        definition.put("scope", "TENANT")
        principal.database.withTransaction { connection ->
            loadDefinitions(connection).compose { known ->
                val diagnostics = MetadataDefinitionValidator.validate(definition, known)
                if (diagnostics.isNotEmpty()) return@compose Future.failedFuture<MetadataWriteResult>(
                    MetadataValidationException(diagnostics)
                )
                val id = UUID.randomUUID()
                val checksum = checksum(definition)
                connection.preparedQuery(
                    """INSERT INTO meta.document_definition
                       (id,scope,doctype,version,status,definition_json,checksum,created_by,updated_by)
                       VALUES ($1,'TENANT',$2,1,'DRAFT',$3::jsonb,$4,$5,$5)"""
                ).execute(Tuple.of(id, doctype, definition.encode(), checksum, principal.userId))
                    .compose {
                        writeHistory(connection, id, doctype, 1, "CREATE", definition, JsonArray(), principal.userId)
                    }.map { MetadataWriteResult(id, 1, checksum) }
            }
        }.onSuccess { result ->
            send(context, JsonObject().put("id", result.id.toString()).put("doctype", doctype)
                .put("version", result.version).put("status", "DRAFT").put("checksum", result.checksum), 201)
        }.onFailure { error ->
            if (error is MetadataValidationException) validationFailure(context, error.diagnostics)
            else fail(context, if (error.message?.contains("duplicate key") == true) 409 else 400,
                if (error.message?.contains("duplicate key") == true) "DocType 已存在" else "创建元数据草稿失败")
        }
    }

    private fun update(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        val doctype = context.pathParam("doctype")
        val definition = body(context)
        if (definition.getString("scope", "TENANT") != "TENANT") return@withMetadataAdmin fail(context, 403, "租户管理员不能修改平台级元数据")
        if (definition.getString("doctype") != doctype) return@withMetadataAdmin fail(context, 400, "DocType 标识不能通过更新请求修改")
        definition.put("scope", "TENANT")
        principal.database.withTransaction { connection ->
            loadDefinitions(connection).compose { known ->
                val diagnostics = MetadataDefinitionValidator.validate(definition, known - doctype)
                if (diagnostics.isNotEmpty()) return@compose Future.failedFuture<MetadataWriteResult>(
                    MetadataValidationException(diagnostics)
                )
                connection.preparedQuery(
                    """SELECT id,version,status FROM meta.document_definition
                       WHERE scope='TENANT' AND doctype=$1 ORDER BY version DESC LIMIT 1 FOR UPDATE"""
                ).execute(Tuple.of(doctype)).compose { rows ->
                    val row = rows.firstOrNull() ?: return@compose Future.failedFuture<MetadataWriteResult>("DocType 不存在")
                    if (row.getString("status") != "DRAFT") {
                        return@compose Future.failedFuture<MetadataWriteResult>("已发布或退役定义不可直接修改，请复制为新版本后编辑")
                    }
                    val id = row.getUUID("id")
                    val version = row.getInteger("version")
                    val checksum = checksum(definition)
                    connection.preparedQuery(
                        """UPDATE meta.document_definition SET definition_json=$2::jsonb,checksum=$3,
                           status='DRAFT',updated_by=$4,updated_at=clock_timestamp()
                           WHERE id=$1 AND scope='TENANT'"""
                    ).execute(Tuple.of(id, definition.encode(), checksum, principal.userId)).compose {
                        writeHistory(connection, id, doctype, version, "UPDATE", definition, JsonArray(), principal.userId)
                    }.map { MetadataWriteResult(id, version, checksum) }
                }
            }
        }.onSuccess { result ->
            send(context, JsonObject().put("id", result.id.toString()).put("doctype", doctype)
                .put("version", result.version).put("status", "DRAFT").put("checksum", result.checksum))
        }.onFailure { error ->
            if (error is MetadataValidationException) validationFailure(context, error.diagnostics)
            else fail(context, 409, error.message ?: "更新元数据草稿失败")
        }
    }

    private fun validate(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        val doctype = context.pathParam("doctype")
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id,version,definition_json FROM meta.document_definition
                   WHERE scope='TENANT' AND doctype=$1 ORDER BY version DESC LIMIT 1 FOR UPDATE"""
            ).execute(Tuple.of(doctype)).compose { rows ->
                val row = rows.firstOrNull() ?: return@compose Future.failedFuture<JsonObject>("DocType 不存在")
                val definition = row.getJsonObject("definition_json")
                loadDefinitions(connection).compose { known ->
                    val diagnostics = MetadataDefinitionValidator.validate(definition, known - doctype)
                    val status = if (diagnostics.isEmpty()) "VALIDATED" else "DRAFT"
                    val id = row.getUUID("id")
                    val version = row.getInteger("version")
                    // Given 元数据校验通过，When 发布该版本，Then 先完成受控物理 Schema Migration，再激活定义。
                    val migration: Future<Void> = if (diagnostics.isEmpty()) {
                        schemaMigrations.migrate(principal.database, doctype, version, definition, principal.userId).map { Unit }
                    } else Future.succeededFuture()
                    migration.compose {
                        connection.preparedQuery(
                            """UPDATE meta.document_definition SET status=$2,updated_by=$3,updated_at=clock_timestamp()
                               WHERE id=$1 AND scope='TENANT'"""
                        ).execute(Tuple.of(id, status, principal.userId)).compose {
                            writeHistory(connection, id, doctype, version,
                                if (diagnostics.isEmpty()) "PUBLISH" else "VALIDATION_FAILED",
                                definition, JsonArray(diagnostics.map { it.toJson() }), principal.userId)
                        }.map {
                            JsonObject().put("doctype", doctype).put("version", version).put("status", status)
                                .put("valid", diagnostics.isEmpty()).put("diagnostics", JsonArray(diagnostics.map { it.toJson() }))
                        }
                    }
                }
            }
        }.onSuccess { send(context, it) }
            .onFailure { error ->
                val code = if (error is SchemaMigrationConflictException) 409 else 400
                fail(context, code, error.message ?: "DocType 不存在或 Schema Migration 无法完成")
            }
    }

    private fun copy(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        val doctype = context.pathParam("doctype")
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT definition_json,version FROM meta.document_definition
                   WHERE scope='TENANT' AND doctype=$1 ORDER BY version DESC LIMIT 1 FOR UPDATE"""
            ).execute(Tuple.of(doctype)).compose { rows ->
                val row = rows.firstOrNull() ?: return@compose Future.failedFuture<MetadataWriteResult>("DocType 不存在")
                val nextVersion = row.getInteger("version") + 1
                val definition = row.getJsonObject("definition_json")
                val id = UUID.randomUUID()
                val checksum = checksum(definition)
                connection.preparedQuery(
                    """INSERT INTO meta.document_definition
                       (id,scope,doctype,version,status,definition_json,checksum,created_by,updated_by)
                       VALUES ($1,'TENANT',$2,$3,'DRAFT',$4::jsonb,$5,$6,$6)"""
                ).execute(Tuple.of(id, doctype, nextVersion, definition.encode(), checksum, principal.userId))
                    .compose {
                        writeHistory(connection, id, doctype, nextVersion, "COPY", definition, JsonArray(), principal.userId)
                    }.map { MetadataWriteResult(id, nextVersion, checksum) }
            }
        }.onSuccess { result ->
            send(context, JsonObject().put("id", result.id.toString()).put("doctype", doctype)
                .put("version", result.version).put("status", "DRAFT").put("checksum", result.checksum), 201)
        }.onFailure { fail(context, 409, it.message ?: "复制元数据版本失败") }
    }

    private fun getLatest(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        principal.database.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(
                """SELECT id,doctype,version,status,definition_json,checksum,created_at,updated_at
                   FROM meta.document_definition WHERE scope='TENANT' AND doctype=$1
                   ORDER BY version DESC LIMIT 1"""
            ).execute(Tuple.of(context.pathParam("doctype"))).map { rows -> (rows.firstOrNull()?.toJson() as? JsonObject) ?: JsonObject() }
        }.onSuccess { raw ->
            val row = raw as JsonObject
            if (!row.containsKey("id")) fail(context, 404, "DocType 不存在")
            else {
                row.put("definition", row.getJsonObject("definition_json"))
                row.remove("definition_json")
                send(context, row)
            }
        }.onFailure { fail(context, 503, "读取元数据定义失败") }
    }

    private fun getVersion(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        val version = context.pathParam("version").toIntOrNull()
            ?: return@withMetadataAdmin fail(context, 400, "版本号无效")
        principal.database.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(
                """SELECT id,doctype,version,status,definition_json,checksum,created_at,updated_at
                   FROM meta.document_definition WHERE scope='TENANT' AND doctype=$1 AND version=$2"""
            ).execute(Tuple.of(context.pathParam("doctype"), version)).map { rows -> (rows.firstOrNull()?.toJson() as? JsonObject) ?: JsonObject() }
        }.onSuccess { raw ->
            val row = raw as JsonObject
            if (!row.containsKey("id")) fail(context, 404, "元数据版本不存在")
            else {
                row.put("definition", row.getJsonObject("definition_json"))
                row.remove("definition_json")
                send(context, row)
            }
        }.onFailure { fail(context, 503, "读取元数据版本失败") }
    }

    private fun history(context: RoutingContext) = withMetadataAdmin(context) { principal ->
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT id,version,action,definition_json,diagnostics,actor_id,changed_at
                   FROM meta.document_definition_history WHERE scope='TENANT' AND doctype=$1
                   ORDER BY changed_at DESC LIMIT 200"""
            ).execute(Tuple.of(context.pathParam("doctype"))).map { rows -> JsonArray(rows.map { it.toJson() }) }
        }.onSuccess { send(context, JsonObject().put("items", it)) }
            .onFailure { fail(context, 503, "读取元数据变更历史失败") }
    }

    private fun loadDefinitions(connection: SqlConnection): Future<Map<String, JsonObject>> =
        connection.query(
            """SELECT DISTINCT ON (doctype) doctype,definition_json FROM meta.document_definition
               WHERE scope='TENANT' ORDER BY doctype,version DESC"""
        ).execute().map { rows -> rows.associate { it.getString("doctype") to it.getJsonObject("definition_json") } }

    private fun writeHistory(
        connection: SqlConnection, id: UUID, doctype: String, version: Int, action: String,
        definition: JsonObject, diagnostics: JsonArray, actorId: UUID
    ): Future<Boolean> = connection.preparedQuery(
        """INSERT INTO meta.document_definition_history
           (id,definition_id,scope,doctype,version,action,definition_json,diagnostics,actor_id)
           VALUES ($1,$2,'TENANT',$3,$4,$5,$6::jsonb,$7::jsonb,$8)"""
    ).execute(Tuple.of(UUID.randomUUID(), id, doctype, version, action, definition.encode(), diagnostics.encode(), actorId))
        .map { true }

    private fun withMetadataAdmin(context: RoutingContext, action: (TenantPrincipal) -> Unit) {
        val principal = context.get<TenantPrincipal>(TENANT_CONTEXT_KEY)
        if (principal == null) return fail(context, 401, "租户登录已失效")
        if (principal.role == "TENANT_ADMIN") return action(principal)
        principal.database.withTransaction { connection ->
            connection.preparedQuery(
                """SELECT EXISTS (
                    SELECT 1 FROM hr.employment e
                    JOIN hr.employment_position ep ON ep.employment_id=e.id
                    JOIN iam.job_position_module_role pr ON pr.position_id=ep.position_id
                    JOIN iam.role_permission rp ON rp.role_id=pr.role_id
                    WHERE e.user_id=$1 AND e.status='ACTIVE'
                      AND (e.ends_on IS NULL OR e.ends_on>=CURRENT_DATE)
                      AND rp.permission_key='metadata.manage' AND rp.effect='ALLOW'
                ) AND NOT EXISTS (
                    SELECT 1 FROM hr.employment e
                    JOIN hr.employment_position ep ON ep.employment_id=e.id
                    JOIN iam.job_position_module_role pr ON pr.position_id=ep.position_id
                    JOIN iam.role_permission rp ON rp.role_id=pr.role_id
                    WHERE e.user_id=$1 AND e.status='ACTIVE'
                      AND (e.ends_on IS NULL OR e.ends_on>=CURRENT_DATE)
                      AND rp.permission_key='metadata.manage' AND rp.effect='DENY'
                ) AS allowed"""
            ).execute(Tuple.of(principal.userId)).map { rows -> rows.first().getBoolean("allowed") }
        }.onSuccess { allowed ->
            if (allowed) action(principal) else fail(context, 403, "缺少元数据管理权限")
        }.onFailure { fail(context, 503, "元数据管理权限校验失败") }
    }

    private fun body(context: RoutingContext): JsonObject =
        runCatching { context.body().asJsonObject() }.getOrNull() ?: JsonObject()

    private fun checksum(definition: JsonObject): String =
        MessageDigest.getInstance("SHA-256").digest(definition.encode().toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun validationFailure(context: RoutingContext, diagnostics: List<MetadataDiagnostic>) {
        send(context, JsonObject().put("error", JsonObject().put("code", "METADATA_VALIDATION_FAILED")
            .put("message", "元数据定义校验失败").put("diagnostics", JsonArray(diagnostics.map { it.toJson() }))), 422)
    }

    private fun send(context: RoutingContext, json: JsonObject, status: Int = 200) {
        if (context.response().ended()) return
        context.response().setStatusCode(status).putHeader("cache-control", "no-store")
            .putHeader("content-type", "application/json; charset=utf-8").end(json.encode())
    }

    private fun fail(context: RoutingContext, status: Int, message: String) {
        send(context, JsonObject().put("error", JsonObject()
            .put("code", "METADATA_REQUEST_FAILED").put("message", message)), status)
    }

    private data class MetadataWriteResult(val id: UUID, val version: Int, val checksum: String)
    private class MetadataValidationException(val diagnostics: List<MetadataDiagnostic>) : RuntimeException("元数据校验失败")
}
