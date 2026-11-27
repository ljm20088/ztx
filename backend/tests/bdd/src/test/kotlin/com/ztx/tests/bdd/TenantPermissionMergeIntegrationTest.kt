package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.TenantOrganizationRoutes
import com.ztx.modules.iam.TenantPrincipal
import com.ztx.modules.iam.TENANT_CONTEXT_KEY
import com.ztx.platform.common.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class TenantPermissionMergeIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres=PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    // 场景：同一任职的多个岗位组合多个模块角色，显式拒绝优先且数据范围不能超出任职组织
    @Test
    fun shouldMergePositionRolesWithDenyPriorityAndEmploymentScopeLimit() {
        // Given：租户数据库已迁移，员工在企业工厂任职并担任两个岗位
        val projectDir=Path.of(System.getProperty("ztx.projectDir"))
        val config=DatabaseConfig(host=postgres.host,port=postgres.firstMappedPort,
            database=postgres.databaseName,user=postgres.username,password=postgres.password,poolSize=4,
            migrationLocations="filesystem:${projectDir.resolve("database/tenant-migrations")}")
        FlywayMigrator(config).migrate()
        val adminId=UUID.randomUUID();val tenantId=UUID.randomUUID();val companyId=UUID.randomUUID()
        val unitId=UUID.randomUUID();val bookId=UUID.randomUUID();val employeeId=UUID.randomUUID();val employmentId=UUID.randomUUID()
        val positionA=UUID.randomUUID();val positionB=UUID.randomUUID()
        val roleA=UUID.randomUUID();val roleB=UUID.randomUUID()
        DriverManager.getConnection(config.jdbcUrl,config.user,config.password).use { connection ->
            connection.prepareStatement("INSERT INTO iam.tenant_admin(id,username,password_hash,is_group_admin) VALUES (?,?,'unused',TRUE)").use { s ->
                s.setObject(1,adminId);s.setString(2,"test.admin");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO org.company(id,code,name) VALUES (?,?,'测试企业')").use { s ->
                s.setObject(1,companyId);s.setString(2,"testco");s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO org.organization_unit(id,company_id,code,name,unit_type)
                VALUES (?,?,?,'装配工厂','FACTORY')""").use { s ->
                s.setObject(1,unitId);s.setObject(2,companyId);s.setString(3,"FACTORY-1");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO org.book(id,company_id,code,name) VALUES (?,?,?,'制造账套')").use { s ->
                s.setObject(1,bookId);s.setObject(2,companyId);s.setString(3,"MFG");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO org.book(id,company_id,code,name) VALUES (?,?,?,'制造账套')").use { s ->
                s.setObject(1,bookId);s.setObject(2,companyId);s.setString(3,"MFG");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employee(id,employee_no,name) VALUES (?,?,?)").use { s ->
                s.setObject(1,employeeId);s.setString(2,"EMP-1");s.setString(3,"测试员工");s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO hr.employment(id,employee_id,company_id,book_id,organization_unit_id,starts_on)
                VALUES (?,?,?,?,?,CURRENT_DATE)""").use { s ->
                s.setObject(1,employmentId);s.setObject(2,employeeId);s.setObject(3,companyId);s.setObject(4,bookId);s.setObject(5,unitId);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.job_position(id,company_id,book_id,organization_unit_id,code,name) VALUES (?,?,?,?,?,'岗位 A'),(?,?,?,?,?,'岗位 B')").use { s ->
                s.setObject(1,positionA);s.setObject(2,companyId);s.setObject(3,bookId);s.setObject(4,unitId);s.setString(5,"PA")
                s.setObject(6,positionB);s.setObject(7,companyId);s.setObject(8,bookId);s.setObject(9,unitId);s.setString(10,"PB");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employment_position(employment_id,position_id) VALUES (?,?),(?,?)").use { s ->
                s.setObject(1,employmentId);s.setObject(2,positionA);s.setObject(3,employmentId);s.setObject(4,positionB);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.module_role(id,module_key,code,name) VALUES (?, 'purchase','approver','采购审批'),(?, 'purchase','auditor','采购审计')").use { s ->
                s.setObject(1,roleA);s.setObject(2,roleB);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.job_position_module_role(position_id,role_id) VALUES (?,?),(?,?)").use { s ->
                s.setObject(1,positionA);s.setObject(2,roleA);s.setObject(3,positionB);s.setObject(4,roleB);s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO iam.role_permission(id,role_id,permission_key,effect,data_scope,field_permissions)
                VALUES (?,?,'purchase.order.approve','ALLOW','ALL','{"amount":"EDITABLE","supplier":"VISIBLE"}'::jsonb),
                       (?,?,'purchase.order.approve','DENY','COMPANY','{"amount":"READ_ONLY","supplier":"MASKED"}'::jsonb)""").use { s ->
                s.setObject(1,UUID.randomUUID());s.setObject(2,roleA);s.setObject(3,UUID.randomUUID());s.setObject(4,roleB);s.executeUpdate()
            }
        }

        val vertx=Vertx.vertx()
        val database=PgDatabase(vertx,config)
        val principal=TenantPrincipal(tenantId,"testtenant",config.database,adminId,"test.admin","TENANT_ADMIN",database,true)
        val router=Router.router(vertx)
        router.route("/api/v1/tenant/*").handler(BodyHandler.create().setBodyLimit(16*1024))
        router.route("/api/v1/tenant/*").handler { context -> context.put(TENANT_CONTEXT_KEY,principal);context.next() }
        TenantOrganizationRoutes().register(router)
        val server=vertx.createHttpServer().requestHandler(router).listen(0,"127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS)

        try {
            // When：读取该任职的最终权限
            val request=HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort()}/api/v1/tenant/organization/employments/$employmentId/effective-permissions")).GET().build()
            val response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString())
            // Then：两个岗位权限合并，DENY 覆盖 ALLOW，数据范围裁剪为组织子树，字段按更严格规则合并
            assertEquals(200,response.statusCode())
            val permission=JsonObject(response.body()).getJsonObject("permissions").getJsonObject("purchase.order.approve")
            assertFalse(permission.getBoolean("allowed"))
            assertEquals("DENY",permission.getString("effect"))
            assertEquals("BOOK_AND_SUBTREE",permission.getString("dataScope"))
            val scopeConstraints=permission.getJsonObject("scopeConstraints")
            assertEquals(bookId.toString(),scopeConstraints.getString("bookId"))
            assertEquals(unitId.toString(),scopeConstraints.getString("organizationUnitId"))
            val fields=permission.getJsonObject("fieldPermissions")
            assertEquals("READ_ONLY",fields.getString("amount"))
            assertEquals("MASKED",fields.getString("supplier"))
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS)
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS)
        }
    }

    // 场景：企业管理员只能管理其有效任职和 organization.manage 授权覆盖的企业
    @Test
    fun companyAdminShouldOnlyManageCompaniesGrantedByEmploymentRoles() {
        // Given：用户仅在企业 A 任职，并通过岗位角色获得组织管理权限
        val projectDir=Path.of(System.getProperty("ztx.projectDir"))
        val config=DatabaseConfig(host=postgres.host,port=postgres.firstMappedPort,
            database=postgres.databaseName,user=postgres.username,password=postgres.password,poolSize=4,
            migrationLocations="filesystem:${projectDir.resolve("database/tenant-migrations")}")
        FlywayMigrator(config).migrate()
        val companyA=UUID.randomUUID();val companyB=UUID.randomUUID()
        val userId=UUID.randomUUID();val employeeId=UUID.randomUUID();val employmentId=UUID.randomUUID()
        val positionId=UUID.randomUUID();val roleId=UUID.randomUUID()
        DriverManager.getConnection(config.jdbcUrl,config.user,config.password).use { connection ->
            connection.prepareStatement("INSERT INTO org.company(id,code,name) VALUES (?,?,'企业 A'),(?,?,'企业 B')").use { s ->
                s.setObject(1,companyA);s.setString(2,"admin-a");s.setObject(3,companyB);s.setString(4,"admin-b");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.user_account(id,username,display_name) VALUES (?,?,?)").use { s ->
                s.setObject(1,userId);s.setString(2,"company.admin");s.setString(3,"企业管理员");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employee(id,employee_no,name) VALUES (?,?,?)").use { s ->
                s.setObject(1,employeeId);s.setString(2,"COMP-ADMIN-1");s.setString(3,"企业管理员");s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO hr.employment(id,employee_id,user_id,company_id,starts_on)
                VALUES (?,?,?,?,CURRENT_DATE)""").use { s ->
                s.setObject(1,employmentId);s.setObject(2,employeeId);s.setObject(3,userId);s.setObject(4,companyA);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.job_position(id,company_id,code,name) VALUES (?,?,?,'企业管理员岗位')").use { s ->
                s.setObject(1,positionId);s.setObject(2,companyA);s.setString(3,"COMP-ADMIN");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employment_position(employment_id,position_id) VALUES (?,?)").use { s ->
                s.setObject(1,employmentId);s.setObject(2,positionId);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.module_role(id,module_key,code,name) VALUES (?, 'organization','company-admin','企业管理员')").use { s ->
                s.setObject(1,roleId);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.job_position_module_role(position_id,role_id) VALUES (?,?)").use { s ->
                s.setObject(1,positionId);s.setObject(2,roleId);s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO iam.role_permission(id,role_id,permission_key,effect,data_scope)
                VALUES (?,?,'organization.manage','ALLOW','COMPANY')""").use { s ->
                s.setObject(1,UUID.randomUUID());s.setObject(2,roleId);s.executeUpdate()
            }
        }

        val vertx=Vertx.vertx()
        val database=PgDatabase(vertx,config)
        val principal=TenantPrincipal(UUID.randomUUID(),"testtenant",config.database,userId,
            "company.admin","TENANT_USER",database,false,"USER")
        val router=Router.router(vertx)
        router.route("/api/v1/tenant/*").handler(BodyHandler.create().setBodyLimit(16*1024))
        router.route("/api/v1/tenant/*").handler { context -> context.put(TENANT_CONTEXT_KEY,principal);context.next() }
        TenantOrganizationRoutes().register(router)
        val server=vertx.createHttpServer().requestHandler(router).listen(0,"127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS)
        try {
            val client=HttpClient.newHttpClient()
            // When：企业管理员读取企业清单
            val get=HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort()}/api/v1/tenant/organization/companies")).GET().build()
            val response=client.send(get,HttpResponse.BodyHandlers.ofString())
            // Then：只返回其有效任职授权覆盖的企业 A
            assertEquals(200,response.statusCode())
            val items=JsonObject(response.body()).getJsonArray("items")
            assertEquals(1,items.size())
            assertEquals("admin-a",items.getJsonObject(0).getString("code"))

            // When：企业管理员尝试为企业 B 新增账套
            val denied=HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort()}/api/v1/tenant/organization/books"))
                .header("content-type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonObject().put("companyId",companyB.toString())
                    .put("code","B-BOOK").put("name","企业 B 账套").encode())).build()
            // Then：服务端拒绝越权操作
            assertEquals(403,client.send(denied,HttpResponse.BodyHandlers.ofString()).statusCode())

            // When：企业管理员为企业 A 新增账套
            val allowed=HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort()}/api/v1/tenant/organization/books"))
                .header("content-type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonObject().put("companyId",companyA.toString())
                    .put("code","A-BOOK").put("name","企业 A 账套").encode())).build()
            // Then：允许写入，并将审计主体记录为业务用户
            assertEquals(201,client.send(allowed,HttpResponse.BodyHandlers.ofString()).statusCode())
            DriverManager.getConnection(config.jdbcUrl,config.user,config.password).use { connection ->
                connection.prepareStatement("""SELECT count(*) FROM audit.organization_event
                    WHERE actor_user_id=? AND actor_admin_id IS NULL AND entity_type='books' AND action='CREATE'""").use { s ->
                    s.setObject(1,userId);s.executeQuery().use { rows -> rows.next();assertEquals(1,rows.getInt(1)) }
                }
            }
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS)
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS)
        }
    }

}
