package com.ztx.modules.metadata

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

data class RuntimeSchemaMigrationResult(
    val id: UUID, val doctype: String, val version: Int,
    val schema: String, val table: String, val checksum: String,
    val status: String, val plan: JsonObject
)

class SchemaMigrationConflictException(message: String) : RuntimeException(message)

/**
 * 客户运行期受控 PostgreSQL Schema Migration。
 * 只执行平台从已校验元数据生成的 DDL；失败记录在回滚后的新事务写入。
 * 采用 Expand-only 策略，不自动删除字段或缩窄物理列类型。
 */
class SchemaMigrationService {
    private val identifier = Regex("[a-z][a-z0-9_]{0,62}")
    private val protectedSchemas = setOf("platform", "iam", "meta", "audit", "pg_catalog", "information_schema")

    fun plan(doctype: String, version: Int, definition: JsonObject): JsonObject {
        require(version > 0) { "Schema Migration 版本必须大于 0" }
        require(definition.getString("doctype") == doctype) { "DocType 标识与元数据不一致" }
        val table = definition.getJsonObject("table") ?: throw IllegalArgumentException("缺少物理表映射")
        val schema = table.getString("schema", "")
        val tableName = table.getString("name", "")
        require(identifier.matches(schema) && schema !in protectedSchemas) { "不允许使用目标 Schema：$schema" }
        require(identifier.matches(tableName)) { "物理表名不合法：$tableName" }
        val fields = definition.getJsonArray("fields") ?: JsonArray()
        require(fields.size() in 1..200) { "DocType 字段数量必须在 1 到 200 之间" }
        val seen = mutableSetOf<String>()
        val plannedFields = JsonArray()
        fields.filterIsInstance<JsonObject>().forEach { field ->
            val name = field.getString("name", "")
            val type = field.getString("type", "")
            require(identifier.matches(name)) { "字段名不合法：$name" }
            require(name !in SYSTEM_COLUMNS) { "字段名与系统列冲突：$name" }
            require(seen.add(name)) { "字段名重复：$name" }
            val physicalType = sqlType(type)
            val defaultValue = field.getValue("defaultValue")
            if (defaultValue != null) defaultLiteral(type, defaultValue)
            plannedFields.add(
                JsonObject().put("name", name).put("type", physicalType)
                    .put("logicalType", type).put("required", field.getBoolean("required", false))
                    .put("unique", field.getBoolean("unique", false))
                    .put("indexed", field.getBoolean("indexed", false))
                    .put("defaultValue", defaultValue)
            )
        }
        return JsonObject().put("doctype", doctype).put("version", version)
            .put("schema", schema).put("table", tableName)
            .put("columns", plannedFields).put("policy", "EXPAND_ONLY")
    }

    fun migrate(
        database: PgDatabase, doctype: String, version: Int,
        definition: JsonObject, actorId: UUID? = null
    ): Future<RuntimeSchemaMigrationResult> {
        val migrationPlan = try { plan(doctype, version, definition) }
        catch (error: Throwable) { return Future.failedFuture(error) }
        val schema = migrationPlan.getString("schema")
        val table = migrationPlan.getString("table")
        val checksum = sha256(migrationPlan.encode())
        val migrationId = UUID.randomUUID()
        return database.withTransaction { connection ->
            connection.preparedQuery("SELECT pg_advisory_xact_lock(hashtext($1),hashtext($2))")
                .execute(Tuple.of("ztx.schema-migration", "$schema.$table"))
                .compose {
                    connection.preparedQuery(
                        """SELECT id,checksum,status FROM meta.schema_migration
                           WHERE source='RUNTIME' AND version=$1 AND doctype=$2 FOR UPDATE"""
                    ).execute(Tuple.of(version.toString(), doctype))
                }.compose { existingRows ->
                    val existing = existingRows.firstOrNull()
                    if (existing != null && existing.getString("checksum") != checksum) {
                        return@compose Future.failedFuture<RuntimeSchemaMigrationResult>(
                            SchemaMigrationConflictException("同一 DocType 版本的 Schema 内容不可变；请提升版本号")
                        )
                    }
                    if (existing != null && existing.getString("status") == "SUCCEEDED") {
                        return@compose Future.succeededFuture(
                            RuntimeSchemaMigrationResult(existing.getUUID("id"), doctype, version,
                                schema, table, checksum, "SUCCEEDED", migrationPlan)
                        )
                    }
                    markRunning(connection, migrationId, doctype, version, schema, table, checksum, migrationPlan, actorId)
                        .compose { applyPlan(connection, migrationPlan) }
                        .compose {
                            connection.preparedQuery(
                                """UPDATE meta.schema_migration
                                   SET status='SUCCEEDED',completed_at=clock_timestamp(),error_message=NULL
                                   WHERE source='RUNTIME' AND version=$1 AND doctype=$2"""
                            ).execute(Tuple.of(version.toString(), doctype))
                        }.map {
                            RuntimeSchemaMigrationResult(migrationId, doctype, version,
                                schema, table, checksum, "SUCCEEDED", migrationPlan)
                        }
                }
        }.recover { error ->
            if (error is SchemaMigrationConflictException) return@recover Future.failedFuture(error)
            database.withTransaction { connection ->
                recordFailure(connection, migrationId, doctype, version, schema, table,
                    checksum, migrationPlan, actorId, error)
            }.compose { Future.failedFuture(error) }
        }
    }

    private fun markRunning(
        connection: SqlConnection, id: UUID, doctype: String, version: Int,
        schema: String, table: String, checksum: String, plan: JsonObject, actorId: UUID?
    ): Future<Void> = connection.preparedQuery(
        """INSERT INTO meta.schema_migration
           (id,source,version,doctype,schema_name,table_name,checksum,status,plan_json,diagnostics,actor_id,started_at)
           VALUES ($1,'RUNTIME',$2,$3,$4,$5,$6,'RUNNING',$7::jsonb,'[]'::jsonb,$8,clock_timestamp())
           ON CONFLICT(source,version,doctype) DO UPDATE SET
             schema_name=EXCLUDED.schema_name,table_name=EXCLUDED.table_name,checksum=EXCLUDED.checksum,
             status='RUNNING',plan_json=EXCLUDED.plan_json,diagnostics='[]'::jsonb,actor_id=EXCLUDED.actor_id,
             started_at=clock_timestamp(),completed_at=NULL,error_message=NULL"""
    ).execute(Tuple.of(id, version.toString(), doctype, schema, table, checksum, plan.encode(), actorId)).mapEmpty()

    private fun recordFailure(
        connection: SqlConnection, id: UUID, doctype: String, version: Int,
        schema: String, table: String, checksum: String, plan: JsonObject,
        actorId: UUID?, error: Throwable
    ): Future<Void> = connection.preparedQuery(
        """INSERT INTO meta.schema_migration
           (id,source,version,doctype,schema_name,table_name,checksum,status,plan_json,diagnostics,actor_id,started_at,completed_at,error_message)
           VALUES ($1,'RUNTIME',$2,$3,$4,$5,$6,'FAILED',$7::jsonb,$8::jsonb,$9,clock_timestamp(),clock_timestamp(),$10)
           ON CONFLICT(source,version,doctype) DO UPDATE SET
             status='FAILED',plan_json=EXCLUDED.plan_json,diagnostics=EXCLUDED.diagnostics,
             actor_id=EXCLUDED.actor_id,completed_at=clock_timestamp(),error_message=EXCLUDED.error_message"""
    ).execute(Tuple.of(
        id, version.toString(), doctype, schema, table, checksum, plan.encode(),
        JsonArray().add(JsonObject().put("code", "DDL_FAILED")
            .put("message", error.message ?: "Schema Migration failed")).encode(),
        actorId, (error.message ?: "Schema Migration failed").take(4000)
    )).mapEmpty()

    private fun applyPlan(connection: SqlConnection, plan: JsonObject): Future<Void> {
        val schema = plan.getString("schema")
        val table = plan.getString("table")
        val tableSql = "\"$schema\".\"$table\""
        return connection.query("CREATE SCHEMA IF NOT EXISTS \"$schema\"").execute().mapEmpty()
            .compose {
                connection.preparedQuery(
                    """SELECT EXISTS (SELECT 1 FROM information_schema.tables
                       WHERE table_schema=$1 AND table_name=$2 AND table_type='BASE TABLE') AS present"""
                ).execute(Tuple.of(schema, table))
            }.compose { rows ->
                if (!rows.first().getBoolean("present")) createTable(connection, tableSql, plan)
                else ensureExistingTable(connection, tableSql, plan)
            }.compose { createIndexes(connection, tableSql, plan) }
    }

    private fun createTable(connection: SqlConnection, tableSql: String, plan: JsonObject): Future<Void> {
        val columns = mutableListOf(
            "\"id\" UUID PRIMARY KEY", "\"docstatus\" INTEGER NOT NULL DEFAULT 0",
            "\"version\" INTEGER NOT NULL DEFAULT 1",
            "\"created_at\" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()",
            "\"updated_at\" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()"
        )
        plan.getJsonArray("columns").filterIsInstance<JsonObject>().forEach { columns += columnDefinition(it, true) }
        return connection.query("CREATE TABLE $tableSql (${columns.joinToString(",")})").execute().mapEmpty()
    }

    private fun ensureExistingTable(connection: SqlConnection, tableSql: String, plan: JsonObject): Future<Void> =
        loadColumns(connection, plan.getString("schema"), plan.getString("table")).compose { columns ->
            if (columns["id"]?.getString("udt_name") != "uuid") {
                return@compose Future.failedFuture<Void>("现有物理表必须包含 UUID 系统主键 id")
            }
            val system = listOf(
                Triple("docstatus", "int4", "\"docstatus\" INTEGER NOT NULL DEFAULT 0"),
                Triple("version", "int4", "\"version\" INTEGER NOT NULL DEFAULT 1"),
                Triple("created_at", "timestamptz", "\"created_at\" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()"),
                Triple("updated_at", "timestamptz", "\"updated_at\" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()")
            )
            val operations = mutableListOf<() -> Future<Void>>()
            system.forEach { (name, expected, ddl) ->
                val existing = columns[name]
                if (existing == null) operations += {
                    connection.query("ALTER TABLE $tableSql ADD COLUMN $ddl").execute().mapEmpty()
                } else if (existing.getString("udt_name") != expected) {
                    throw IllegalArgumentException("系统列 $name 类型不兼容，期望 $expected")
                }
            }
            connection.query("SELECT EXISTS (SELECT 1 FROM $tableSql LIMIT 1) AS present").execute()
                .map { it.first().getBoolean("present") }.compose { hasRows ->
                    plan.getJsonArray("columns").filterIsInstance<JsonObject>().forEach { field ->
                        val name = field.getString("name")
                        val expected = udtType(field.getString("type"))
                        val existing = columns[name]
                        if (existing == null) {
                            if (field.getBoolean("required", false) &&
                                field.getValue("defaultValue") == null && hasRows) {
                                throw IllegalArgumentException("新增必填字段 $name 需要默认值或显式数据回填方案")
                            }
                            operations += {
                                connection.query("ALTER TABLE $tableSql ADD COLUMN " +
                                    columnDefinition(field, true)).execute().mapEmpty()
                            }
                        } else {
                            if (existing.getString("udt_name") != expected) {
                                throw IllegalArgumentException("字段 $name 的物理类型不兼容，不能自动缩窄或转换")
                            }
                            if (field.getBoolean("required", false) && existing.getString("is_nullable") == "YES") {
                                operations += {
                                    val defaultValue = field.getValue("defaultValue")
                                    val backfill = if (defaultValue != null) {
                                        val literal = defaultLiteral(field.getString("logicalType"), defaultValue)
                                        connection.query("UPDATE $tableSql SET \"$name\"=$literal WHERE \"$name\" IS NULL")
                                            .execute().mapEmpty()
                                    } else {
                                        connection.preparedQuery("SELECT EXISTS (SELECT 1 FROM $tableSql WHERE \"$name\" IS NULL) AS present")
                                            .execute().compose { nullRows ->
                                                if (nullRows.first().getBoolean("present"))
                                                    Future.failedFuture<Void>("字段 $name 含空值，不能直接改为必填")
                                                else Future.succeededFuture()
                                            }
                                    }
                                    backfill.compose {
                                        connection.query("ALTER TABLE $tableSql ALTER COLUMN \"$name\" SET NOT NULL")
                                            .execute().mapEmpty()
                                    }
                                }
                            }
                            operations += {
                                val defaultValue = field.getValue("defaultValue")
                                val action = if (defaultValue == null) "DROP DEFAULT"
                                    else "SET DEFAULT " + defaultLiteral(field.getString("logicalType"), defaultValue)
                                connection.query("ALTER TABLE $tableSql ALTER COLUMN \"$name\" $action")
                                    .execute().mapEmpty()
                            }
                        }
                    }
                    runSequential(operations)
                }
        }

    private fun loadColumns(connection: SqlConnection, schema: String, table: String): Future<Map<String, Row>> =
        connection.preparedQuery(
            """SELECT column_name,udt_name,is_nullable,column_default FROM information_schema.columns
               WHERE table_schema=$1 AND table_name=$2"""
        ).execute(Tuple.of(schema, table)).map { rows -> rows.associateBy { it.getString("column_name") } }

    private fun createIndexes(connection: SqlConnection, tableSql: String, plan: JsonObject): Future<Void> {
        val schema = plan.getString("schema")
        val table = plan.getString("table")
        val operations = mutableListOf<() -> Future<Void>>()
        plan.getJsonArray("columns").filterIsInstance<JsonObject>().forEach { field ->
            val unique = field.getBoolean("unique", false)
            if (unique || field.getBoolean("indexed", false)) {
                val name = indexName(schema, table, field.getString("name"), unique)
                val kind = if (unique) "UNIQUE " else ""
                val fieldName = field.getString("name")
                operations += {
                    connection.query("CREATE $kind INDEX IF NOT EXISTS \"$name\" ON $tableSql (\"$fieldName\")")
                        .execute().mapEmpty()
                }
            }
        }
        return runSequential(operations)
    }

    private fun runSequential(operations: List<() -> Future<Void>>): Future<Void> {
        var result: Future<Void> = Future.succeededFuture()
        operations.forEach { operation -> result = result.compose { operation() } }
        return result
    }

    private fun columnDefinition(field: JsonObject, includeRequired: Boolean): String {
        val name = field.getString("name")
        val defaultValue = field.getValue("defaultValue")
        val defaultSql = if (defaultValue == null) "" else
            " DEFAULT " + defaultLiteral(field.getString("logicalType"), defaultValue)
        val notNull = if (includeRequired && field.getBoolean("required", false)) " NOT NULL" else ""
        return "\"$name\" ${field.getString("type")}$defaultSql$notNull"
    }

    private fun sqlType(type: String): String = when (type) {
        "string", "text", "select" -> "TEXT"
        "uuid", "reference" -> "UUID"
        "date" -> "DATE"
        "datetime" -> "TIMESTAMP"
        "time" -> "TIME"
        "decimal", "money" -> "NUMERIC"
        "integer" -> "BIGINT"
        "boolean" -> "BOOLEAN"
        "json", "table" -> "JSONB"
        else -> throw IllegalArgumentException("不支持的字段类型：$type")
    }

    private fun udtType(type: String): String = when (type) {
        "TEXT" -> "text"
        "UUID" -> "uuid"
        "DATE" -> "date"
        "TIMESTAMP" -> "timestamp"
        "TIME" -> "time"
        "NUMERIC" -> "numeric"
        "BIGINT" -> "int8"
        "BOOLEAN" -> "bool"
        "JSONB" -> "jsonb"
        else -> throw IllegalArgumentException("不支持的物理类型：$type")
    }

    private fun defaultLiteral(type: String, value: Any): String = when (type) {
        "string", "text", "select" -> quote(value.toString())
        "uuid", "reference" -> quote(UUID.fromString(value.toString()).toString()) + "::uuid"
        "date" -> quote(LocalDate.parse(value.toString()).toString()) + "::date"
        "datetime" -> quote(LocalDateTime.parse(value.toString()).toString()) + "::timestamp"
        "time" -> quote(LocalTime.parse(value.toString()).toString()) + "::time"
        "decimal", "money" -> BigDecimal(value.toString()).toPlainString()
        "integer" -> value.toString().toLong().toString()
        "boolean" -> value.toString().toBooleanStrict().toString()
        "json", "table" -> {
            val json = when (value) {
                is JsonObject -> value.encode()
                is JsonArray -> value.encode()
                else -> throw IllegalArgumentException("JSON 默认值必须是对象或数组")
            }
            quote(json) + "::jsonb"
        }
        else -> throw IllegalArgumentException("不支持默认值类型：$type")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun indexName(schema: String, table: String, field: String, unique: Boolean): String {
        val kind = if (unique) "unique" else "index"
        val suffix = if (unique) "uq" else "ix"
        val hash = sha256("$schema.$table.$field.$kind").take(10)
        return "ztx_${table}_${field}_${suffix}_$hash".take(63)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val SYSTEM_COLUMNS = setOf("id", "docstatus", "version", "created_at", "updated_at")
    }
}
