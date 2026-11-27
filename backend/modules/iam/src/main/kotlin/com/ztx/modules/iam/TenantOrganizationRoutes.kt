package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.UUID

/**
 * 租户内部组织、人事、岗位与集团档案 API。
 * 所有请求必须先经过 TenantAuthRoutes 的全局认证守卫；写入和审计在同一事务提交。
 */
class TenantOrganizationRoutes {
    private val listQueries = mapOf(
        "companies" to "SELECT * FROM org.company ORDER BY code",
        "books" to "SELECT b.*, c.name AS company_name FROM org.book b JOIN org.company c ON c.id=b.company_id ORDER BY c.code,b.code",
        "organizations" to "SELECT * FROM org.organization_unit ORDER BY company_id, parent_id NULLS FIRST, code",
        "employees" to "SELECT * FROM hr.employee ORDER BY employee_no",
        "users" to "SELECT id, username, display_name, status, last_login_at, created_at FROM iam.user_account ORDER BY username",
        "employments" to """SELECT e.*, p.employee_no, p.name AS employee_name, u.username
            FROM hr.employment e JOIN hr.employee p ON p.id=e.employee_id
            LEFT JOIN iam.user_account u ON u.id=e.user_id ORDER BY e.starts_on DESC""",
        "positions" to "SELECT * FROM iam.job_position ORDER BY code",
        "roles" to "SELECT * FROM iam.module_role ORDER BY module_key,code",
        "group-master" to "SELECT * FROM org.group_master_item ORDER BY category,code",
        "company-group-master" to """SELECT cgm.*, c.name AS company_name, gm.category,gm.code,gm.name AS master_name
            FROM org.company_group_master cgm JOIN org.company c ON c.id=cgm.company_id
            JOIN org.group_master_item gm ON gm.id=cgm.master_item_id ORDER BY c.code,gm.category,gm.code""",
        "position-roles" to """SELECT pr.position_id,pr.role_id,p.name AS position_name,r.module_key,r.code AS role_code,r.name AS role_name
            FROM iam.job_position_module_role pr JOIN iam.job_position p ON p.id=pr.position_id
            JOIN iam.module_role r ON r.id=pr.role_id ORDER BY p.code,r.module_key,r.code""",
        "employment-positions" to """SELECT ep.employment_id,ep.position_id,e.employee_id,p.name AS position_name
            FROM hr.employment_position ep JOIN hr.employment e ON e.id=ep.employment_id
            JOIN iam.job_position p ON p.id=ep.position_id ORDER BY e.starts_on DESC,p.code""",
        "role-permissions" to """SELECT rp.*,r.module_key,r.code AS role_code FROM iam.role_permission rp
            JOIN iam.module_role r ON r.id=rp.role_id ORDER BY r.module_key,r.code,rp.permission_key"""
    )

    fun register(router: Router) {
        // 精确路径先注册，避免被单段资源参数路由提前匹配。
        router.post("/api/v1/tenant/organization/position-roles").handler(::assignPositionRole)
        router.post("/api/v1/tenant/organization/employment-positions").handler(::assignEmploymentPosition)
        router.post("/api/v1/tenant/organization/role-permissions").handler(::saveRolePermission)
        router.get("/api/v1/tenant/organization/employments/:id/effective-permissions").handler(::effectivePermissions)
        router.post("/api/v1/tenant/organization/group-master/:id/enable").handler(::enableGroupMasterForCompany)
        router.post("/api/v1/tenant/organization/group-master/:id/disable").handler(::disableGroupMaster)
        router.post("/api/v1/tenant/organization/group-master/:id/request").handler(::requestGroupMaster)
        router.post("/api/v1/tenant/organization/group-master-requests/:id/approve").handler(::approveGroupMaster)
        router.put("/api/v1/tenant/organization/company-group-master/:id").handler(::updateCompanyGroupMaster)
        router.put("/api/v1/tenant/organization/group-master/:id").handler(::updateGroupMaster)
        router.get("/api/v1/tenant/organization/:resource").handler(::list)
        router.post("/api/v1/tenant/organization/:resource").handler(::create)
        router.put("/api/v1/tenant/organization/:resource/:id").handler(::update)
    }

    private fun list(context: RoutingContext) = withAdmin(context) { principal ->
        val query = listQueries[context.pathParam("resource")]
        if (query == null) return@withAdmin fail(context, 404, "管理资源不存在")
        principal.database.withTransaction { connection ->
            connection.query(query).execute().map { rows -> JsonArray(rows.map { it.toJson() }) }
        }.onSuccess { send(context, JsonObject().put("items", it)) }
            .onFailure { fail(context, 503, "读取管理数据失败") }
    }

    private fun create(context: RoutingContext) = withAdmin(context) { principal ->
        val resource = context.pathParam("resource")
        val body = body(context)
        val id = UUID.randomUUID()
        val statement: String
        val values: Tuple
        when (resource) {
            "companies" -> {
                statement = """INSERT INTO org.company(id,code,name,legal_name,tax_number)
                    VALUES ($1,$2,$3,$4,$5) RETURNING id"""
                values = Tuple.of(id, required(body,"code",40), required(body,"name",160),
                    body.getString("legalName"), body.getString("taxNumber"))
            }
            "books" -> {
                statement = """INSERT INTO org.book(id,company_id,code,name,currency)
                    VALUES ($1,$2,$3,$4,$5) RETURNING id"""
                values = Tuple.of(id, uuid(body,"companyId"), required(body,"code",40),
                    required(body,"name",160), body.getString("currency","CNY"))
            }
            "organizations" -> {
                statement = """INSERT INTO org.organization_unit(id,company_id,parent_id,code,name,unit_type)
                    VALUES ($1,$2,$3,$4,$5,$6) RETURNING id"""
                values = Tuple.of(id, optionalUuid(body,"companyId"), optionalUuid(body,"parentId"),
                    required(body,"code",40), required(body,"name",160),
                    body.getString("unitType","DEPARTMENT"))
            }
            "employees" -> {
                statement = """INSERT INTO hr.employee(id,employee_no,name,mobile,email)
                    VALUES ($1,$2,$3,$4,$5) RETURNING id"""
                values = Tuple.of(id, required(body,"employeeNo",40), required(body,"name",120),
                    body.getString("mobile"), body.getString("email"))
            }
            "users" -> {
                statement = """INSERT INTO iam.user_account(id,username,password_hash,display_name)
                    VALUES ($1,$2,$3,$4) RETURNING id"""
                val password = body.getString("password")
                val hash = password?.takeIf { it.isNotBlank() }?.let {
                    require(it.length >= 8 && it.any(Char::isLetter) && it.any(Char::isDigit)) {
                        "密码至少 8 位，且必须同时包含字母和数字"
                    }
                    AuthSupport.hashPassword(it)
                }
                values = Tuple.of(id, required(body,"username",80), hash,
                    required(body,"displayName",120))
            }
            "employments" -> {
                statement = """INSERT INTO hr.employment(id,employee_id,user_id,company_id,organization_unit_id,
                    employment_type,starts_on,ends_on)
                    VALUES ($1,$2,$3,$4,$5,$6,$7::date,$8::date) RETURNING id"""
                values = Tuple.of(id, uuid(body,"employeeId"), optionalUuid(body,"userId"),
                    uuid(body,"companyId"), optionalUuid(body,"organizationUnitId"),
                    body.getString("employmentType","EMPLOYEE"), required(body,"startsOn",10),
                    body.getString("endsOn"))
            }
            "positions" -> {
                statement = """INSERT INTO iam.job_position(id,company_id,code,name,description)
                    VALUES ($1,$2,$3,$4,$5) RETURNING id"""
                values = Tuple.of(id, optionalUuid(body,"companyId"), required(body,"code",40),
                    required(body,"name",120), body.getString("description"))
            }
            "roles" -> {
                statement = """INSERT INTO iam.module_role(id,module_key,code,name,description)
                    VALUES ($1,$2,$3,$4,$5) RETURNING id"""
                values = Tuple.of(id, required(body,"moduleKey",64), required(body,"code",64),
                    required(body,"name",120), body.getString("description"))
            }
            "group-master" -> {
                if (!principal.isGroupAdmin) return@withAdmin fail(context,403,"需要集团档案管理员权限")
                statement = """INSERT INTO org.group_master_item(id,category,code,name,master_data,protected_fields,created_by,updated_by)
                    VALUES ($1,$2,$3,$4,$5::jsonb,$6,$7,$7) RETURNING id"""
                values = Tuple.of(id, required(body,"category",64), required(body,"code",64),
                    required(body,"name",160), (body.getJsonObject("masterData") ?: JsonObject()).encode(),
                    (body.getJsonArray("protectedFields") ?: JsonArray()).map { it.toString() }, principal.userId)
            }
            else -> return@withAdmin fail(context,404,"该资源不支持新增")
        }
        principal.database.withTransaction { connection ->
            connection.preparedQuery(statement).execute(values).compose {
                audit(connection, principal.userId, resource, id, "CREATE", body)
            }.map { id }
        }.onSuccess { send(context, JsonObject().put("id", it.toString()), 201) }
            .onFailure { fail(context, 400, "保存失败，请检查必填项、关联记录和唯一编码") }
    }

    private fun update(context: RoutingContext) = withAdmin(context) { principal ->
        val resource = context.pathParam("resource")
        val id = parseId(context.pathParam("id")) ?: return@withAdmin fail(context,400,"记录标识无效")
        val body = body(context)
        val statement: String
        val values: Tuple
        when (resource) {
            "companies" -> {
                statement = "UPDATE org.company SET name=$2,legal_name=$3,tax_number=$4,updated_at=clock_timestamp() WHERE id=$1"
                values = Tuple.of(id, required(body,"name",160), body.getString("legalName"),body.getString("taxNumber"))
            }
            "books" -> {
                statement = "UPDATE org.book SET name=$2,currency=$3,updated_at=clock_timestamp() WHERE id=$1"
                values = Tuple.of(id, required(body,"name",160),body.getString("currency","CNY"))
            }
            "organizations" -> {
                statement = """UPDATE org.organization_unit SET name=$2,parent_id=$3,unit_type=$4,
                    updated_at=clock_timestamp() WHERE id=$1"""
                values = Tuple.of(id,required(body,"name",160),optionalUuid(body,"parentId"),body.getString("unitType","DEPARTMENT"))
            }
            "employees" -> {
                statement = "UPDATE hr.employee SET name=$2,mobile=$3,email=$4,updated_at=clock_timestamp() WHERE id=$1"
                values = Tuple.of(id,required(body,"name",120),body.getString("mobile"),body.getString("email"))
            }
            "users" -> {
                statement = """UPDATE iam.user_account SET display_name=$2,status=$3,updated_at=clock_timestamp()
                    WHERE id=$1"""
                values = Tuple.of(id,required(body,"displayName",120),body.getString("status","ACTIVE"))
            }
            "employments" -> {
                statement = """UPDATE hr.employment SET organization_unit_id=$2,ends_on=$3::date,status=$4,
                    updated_at=clock_timestamp() WHERE id=$1"""
                values = Tuple.of(id,optionalUuid(body,"organizationUnitId"),body.getString("endsOn"),
                    body.getString("status","ACTIVE"))
            }
            "positions" -> {
                statement = "UPDATE iam.job_position SET name=$2,description=$3,status=$4,updated_at=clock_timestamp() WHERE id=$1"
                values = Tuple.of(id,required(body,"name",120),body.getString("description"),body.getString("status","ACTIVE"))
            }
            "roles" -> {
                statement = "UPDATE iam.module_role SET name=$2,description=$3,status=$4 WHERE id=$1"
                values = Tuple.of(id,required(body,"name",120),body.getString("description"),body.getString("status","ACTIVE"))
            }
            else -> return@withAdmin fail(context,404,"该资源不支持修改")
        }
        principal.database.withTransaction { connection ->
            connection.preparedQuery(statement).execute(values).compose { result ->
                if (result.rowCount() != 1) return@compose io.vertx.core.Future.failedFuture<Void>("记录不存在")
                audit(connection,principal.userId,resource,id,"UPDATE",body)
            }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,400,"修改失败，请检查记录及关联关系") }
    }

    private fun assignPositionRole(context: RoutingContext) = withAdmin(context) { principal ->
        val body = body(context)
        val positionId = uuid(body,"positionId")
        val roleIds = body.getJsonArray("roleIds") ?: JsonArray()
        if (roleIds.size() > 100) return@withAdmin fail(context,400,"单个岗位最多组合 100 个模块角色")
        principal.database.withTransaction { connection ->
            connection.preparedQuery("DELETE FROM iam.job_position_module_role WHERE position_id=$1")
                .execute(Tuple.of(positionId)).compose {
                    roleIds.fold(io.vertx.core.Future.succeededFuture<Void>()) { future, raw ->
                        future.compose {
                            connection.preparedQuery("INSERT INTO iam.job_position_module_role(position_id,role_id) VALUES ($1,$2)")
                                .execute(Tuple.of(positionId,UUID.fromString(raw.toString()))).mapEmpty()
                        }
                    }
                }.compose { audit(connection,principal.userId,"position",positionId,"ROLES_REPLACED",body) }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,400,"岗位角色关联失败") }
    }

    private fun assignEmploymentPosition(context: RoutingContext) = withAdmin(context) { principal ->
        val body=body(context); val employmentId=uuid(body,"employmentId")
        val positionIds=body.getJsonArray("positionIds")?:JsonArray()
        if(positionIds.size()>50)return@withAdmin fail(context,400,"单个任职最多分配 50 个岗位")
        principal.database.withTransaction { connection ->
            connection.preparedQuery("DELETE FROM hr.employment_position WHERE employment_id=$1")
                .execute(Tuple.of(employmentId)).compose {
                    positionIds.fold(io.vertx.core.Future.succeededFuture<Void>()) { future, raw ->
                        future.compose {
                            connection.preparedQuery("INSERT INTO hr.employment_position(employment_id,position_id) VALUES ($1,$2)")
                                .execute(Tuple.of(employmentId,UUID.fromString(raw.toString()))).mapEmpty()
                        }
                    }
                }.compose { audit(connection,principal.userId,"employment",employmentId,"POSITIONS_REPLACED",body) }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,400,"任职岗位分配失败") }
    }

    private fun saveRolePermission(context: RoutingContext) = withAdmin(context) { principal ->
        val body=body(context); val id=UUID.randomUUID()
        val roleId=uuid(body,"roleId")
        val permissionKey=required(body,"permissionKey",160)
        val effect=body.getString("effect","ALLOW")
        val scope=body.getString("dataScope","SELF")
        val fields=body.getJsonObject("fieldPermissions")?:JsonObject()
        if(effect !in setOf("ALLOW","DENY") || scope !in setOf("SELF","UNIT","SUBTREE","COMPANY","ALL") ||
            fields.any { it.value !in setOf("VISIBLE","EDITABLE","READ_ONLY","HIDDEN","MASKED") }) {
            return@withAdmin fail(context,400,"权限配置值不合法")
        }
        principal.database.withTransaction { connection ->
            connection.preparedQuery("""INSERT INTO iam.role_permission(id,role_id,permission_key,effect,data_scope,field_permissions)
                VALUES ($1,$2,$3,$4,$5,$6::jsonb)
                ON CONFLICT(role_id,permission_key) DO UPDATE SET effect=EXCLUDED.effect,
                data_scope=EXCLUDED.data_scope,field_permissions=EXCLUDED.field_permissions""")
                .execute(Tuple.of(id,roleId,permissionKey,effect,scope,fields.encode())).compose {
                    audit(connection,principal.userId,"role_permission",id,"UPSERT",body)
                }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,400,"角色权限保存失败") }
    }

    private fun effectivePermissions(context: RoutingContext) = withAdmin(context) { principal ->
        val employmentId=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"任职标识无效")
        val query="""SELECT rp.permission_key,rp.effect,rp.data_scope,rp.field_permissions::text AS field_permissions,
            e.company_id,e.organization_unit_id
            FROM hr.employment e
            JOIN hr.employment_position ep ON ep.employment_id=e.id
            JOIN iam.job_position_module_role pr ON pr.position_id=ep.position_id
            JOIN iam.role_permission rp ON rp.role_id=pr.role_id
            WHERE e.id=$1 AND e.status='ACTIVE' AND (e.ends_on IS NULL OR e.ends_on>=CURRENT_DATE)"""
        principal.database.withTransaction { connection ->
            connection.preparedQuery(query).execute(Tuple.of(employmentId)).map { rows ->
                val decisions=rows.groupBy { it.getString("permission_key") }
                val permissions=JsonObject()
                decisions.forEach { (key, grants) ->
                    val denied=grants.any { it.getString("effect")=="DENY" }
                    val maxScope=if(grants.first().getValue("organization_unit_id")!=null) "SUBTREE" else "COMPANY"
                    val requested=grants.maxBy { scopeRank(it.getString("data_scope")) }.getString("data_scope")
                    val scope=if(scopeRank(requested)>scopeRank(maxScope)) maxScope else requested
                    val fieldRanks=mutableMapOf<String,String>()
                    grants.forEach { row ->
                        val fields=runCatching { JsonObject(row.getString("field_permissions")) }.getOrDefault(JsonObject())
                        fields.forEach { (field,mode) ->
                            val candidate=mode.toString()
                            val current=fieldRanks[field]
                            if(current==null || fieldRank(candidate)>fieldRank(current))fieldRanks[field]=candidate
                        }
                    }
                    permissions.put(key,JsonObject().put("allowed",!denied).put("effect",if(denied)"DENY" else "ALLOW")
                        .put("dataScope",scope).put("fieldPermissions",JsonObject(fieldRanks)))
                }
                JsonObject().put("employmentId",employmentId.toString()).put("permissions",permissions)
            }
        }.onSuccess { send(context,it) }.onFailure { fail(context,404,"任职不存在或权限读取失败") }
    }

    private fun updateCompanyGroupMaster(context: RoutingContext) = withAdmin(context) { principal ->
        val masterId=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"档案标识无效")
        val body=body(context)
        val companyId=parseId(body.getString("companyId"))?:return@withAdmin fail(context,400,"企业标识无效")
        val localData=body.getJsonObject("localData")?:JsonObject()
        principal.database.withTransaction { connection ->
            connection.preparedQuery("""UPDATE org.company_group_master cgm SET local_data=$3::jsonb,
                updated_at=clock_timestamp() FROM org.group_master_item gm
                WHERE cgm.company_id=$1 AND cgm.master_item_id=$2 AND gm.id=cgm.master_item_id
                AND NOT EXISTS (SELECT 1 FROM unnest(gm.protected_fields) AS protected(field_name) WHERE $3::jsonb ? field_name)""")
                .execute(Tuple.of(companyId,masterId,localData.encode())).compose { result ->
                    if(result.rowCount()!=1)return@compose io.vertx.core.Future.failedFuture<Void>("集团控制字段不可由企业修改")
                    audit(connection,principal.userId,"company_group_master",masterId,"LOCAL_DATA_UPDATE",localData)
                }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,403,"本地补充信息保存失败；集团控制字段不可修改") }
    }

    private fun updateGroupMaster(context: RoutingContext) = withAdmin(context) { principal ->
        if(!principal.isGroupAdmin)return@withAdmin fail(context,403,"需要集团档案管理员权限")
        val id=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"档案标识无效")
        val body=body(context)
        val masterData=body.getJsonObject("masterData")?:JsonObject()
        val protectedFields=body.getJsonArray("protectedFields")?:JsonArray()
        principal.database.withTransaction { connection ->
            connection.preparedQuery("""UPDATE org.group_master_item SET name=$2,master_data=$3::jsonb,
                protected_fields=$4,updated_by=$5,updated_at=clock_timestamp() WHERE id=$1""")
                .execute(Tuple.of(id,required(body,"name",160),masterData.encode(),
                    protectedFields.map { it.toString() },principal.userId)).compose { result ->
                    if(result.rowCount()!=1)return@compose io.vertx.core.Future.failedFuture<Void>("档案不存在")
                    audit(connection,principal.userId,"group_master_item",id,"UPDATE",body)
                }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,400,"集团档案更新失败") }
    }

    private fun enableGroupMasterForCompany(context: RoutingContext) = withAdmin(context) { principal ->
        if(!principal.isGroupAdmin)return@withAdmin fail(context,403,"需要集团档案管理员权限")
        if(!principal.isGroupAdmin)return@withAdmin fail(context,403,"需要集团档案管理员权限")
        val masterId=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"档案标识无效")
        val companyId=parseId(body(context).getString("companyId"))?:return@withAdmin fail(context,400,"企业标识无效")
        principal.database.withTransaction { connection ->
            connection.preparedQuery("""INSERT INTO org.company_group_master(company_id,master_item_id,status,enabled_at)
                VALUES ($1,$2,'ACTIVE',clock_timestamp())
                ON CONFLICT(company_id,master_item_id) DO UPDATE SET status='ACTIVE',enabled_at=clock_timestamp(),
                updated_at=clock_timestamp()""").execute(Tuple.of(companyId,masterId)).compose {
                audit(connection,principal.userId,"company_group_master",masterId,"ENABLE",JsonObject().put("companyId",companyId.toString()))
            }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,400,"企业启用集团档案失败") }
    }

    private fun requestGroupMaster(context: RoutingContext) = withAdmin(context) { principal ->
        val masterId=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"档案标识无效")
        val companyId=parseId(body(context).getString("companyId"))?:return@withAdmin fail(context,400,"企业标识无效")
        val requestId=UUID.randomUUID()
        principal.database.withTransaction { connection ->
            connection.preparedQuery("""INSERT INTO org.group_master_request(id,company_id,master_item_id,requested_by)
                VALUES ($1,$2,$3,$4) ON CONFLICT(company_id,master_item_id) DO UPDATE SET
                status='PENDING',requested_by=EXCLUDED.requested_by,requested_at=clock_timestamp()""")
                .execute(Tuple.of(requestId,companyId,masterId,principal.userId)).compose {
                    audit(connection,principal.userId,"group_master_request",requestId,"REQUEST",JsonObject())
                }
        }.onSuccess { send(context,JsonObject().put("id",requestId.toString()),201) }
            .onFailure { fail(context,400,"集团档案申请失败") }
    }

    private fun approveGroupMaster(context: RoutingContext) = withAdmin(context) { principal ->
        if(!principal.isGroupAdmin)return@withAdmin fail(context,403,"需要集团档案管理员权限")
        val requestId=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"申请标识无效")
        principal.database.withTransaction { connection ->
            connection.preparedQuery("""UPDATE org.group_master_request SET status='APPROVED',reviewed_by=$2,
                reviewed_at=clock_timestamp() WHERE id=$1 AND status='PENDING'
                RETURNING company_id,master_item_id""").execute(Tuple.of(requestId,principal.userId)).compose { rows ->
                if(rows.size()!=1)return@compose io.vertx.core.Future.failedFuture<Void>("申请不存在")
                val row=rows.first()
                connection.preparedQuery("""INSERT INTO org.company_group_master(company_id,master_item_id,status,enabled_at)
                    VALUES ($1,$2,'ACTIVE',clock_timestamp()) ON CONFLICT(company_id,master_item_id)
                    DO UPDATE SET status='ACTIVE',enabled_at=clock_timestamp(),updated_at=clock_timestamp()""")
                    .execute(Tuple.of(row.getUUID("company_id"),row.getUUID("master_item_id"))).compose {
                        audit(connection,principal.userId,"group_master_request",requestId,"APPROVE",JsonObject())
                    }
            }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,409,"集团档案申请状态已变化") }
    }

    private fun disableGroupMaster(context: RoutingContext) = withAdmin(context) { principal ->
        if(!isGroupAdmin(principal.database,principal.userId))return@withAdmin fail(context,403,"需要集团档案管理员权限")
        val id=parseId(context.pathParam("id"))?:return@withAdmin fail(context,400,"档案标识无效")
        principal.database.withTransaction { connection ->
            connection.preparedQuery("SELECT count(*) AS refs FROM org.company_group_master WHERE master_item_id=$1 AND status='ACTIVE'")
                .execute(Tuple.of(id)).compose { rows ->
                    if(rows.first().getLong("refs")>0L)return@compose io.vertx.core.Future.failedFuture<Void>("集团档案仍被企业引用")
                    connection.preparedQuery("UPDATE org.group_master_item SET status='INACTIVE',updated_at=clock_timestamp() WHERE id=$1")
                        .execute(Tuple.of(id)).compose {
                            audit(connection,principal.userId,"group_master_item",id,"DISABLE",JsonObject())
                        }
                }
        }.onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { fail(context,409,"档案仍被引用或状态已变化，不能停用") }
    }

    private fun withAdmin(context: RoutingContext, action:(TenantPrincipal)->Unit) {
        val principal=context.get<TenantPrincipal>(TENANT_CONTEXT_KEY)
        if(principal==null){fail(context,401,"租户登录已失效");return}
        if(principal.role!="TENANT_ADMIN"){fail(context,403,"没有组织管理权限");return}
        action(principal)
    }

    private fun audit(connection:SqlConnection,actor:UUID,type:String,id:UUID,action:String,details:JsonObject) =
        connection.preparedQuery("""INSERT INTO audit.organization_event(id,actor_admin_id,entity_type,entity_id,action,details)
            VALUES ($1,$2,$3,$4,$5,$6::jsonb)""")
            .execute(Tuple.of(UUID.randomUUID(),actor,type,id,action,details.encode())).mapEmpty()

    private fun body(context:RoutingContext)=context.body().asJsonObject()?:JsonObject()
    private fun required(body:JsonObject,key:String,max:Int):String {
        val value=body.getString(key,"").trim()
        require(value.isNotEmpty()&&value.length<=max) { "$key 必填且长度不能超过 $max" }
        return value
    }
    private fun uuid(body:JsonObject,key:String)=UUID.fromString(required(body,key,36))
    private fun optionalUuid(body:JsonObject,key:String)=body.getString(key)?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
    private fun parseId(value:String?)=runCatching { UUID.fromString(value) }.getOrNull()
    private fun scopeRank(value:String)=mapOf("SELF" to 0,"UNIT" to 1,"SUBTREE" to 2,"COMPANY" to 3,"ALL" to 4)[value]?:0
    private fun fieldRank(value:String)=mapOf("VISIBLE" to 0,"EDITABLE" to 1,"READ_ONLY" to 2,"MASKED" to 3,"HIDDEN" to 4)[value]?:4
    private fun send(context:RoutingContext,json:JsonObject,status:Int=200) {
        context.response().setStatusCode(status).putHeader("cache-control","no-store")
            .putHeader("content-type","application/json; charset=utf-8").end(json.encode())
    }
    private fun fail(context:RoutingContext,status:Int,message:String) {
        if(context.response().ended())return
        context.response().setStatusCode(status).putHeader("cache-control","no-store")
            .putHeader("content-type","application/json; charset=utf-8")
            .end(JsonObject().put("error",JsonObject().put("code","ORGANIZATION_REQUEST_FAILED").put("message",message)).encode())
    }
}
