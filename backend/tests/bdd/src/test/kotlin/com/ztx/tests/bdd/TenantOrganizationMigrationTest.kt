package com.ztx.tests.bdd

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.platform.common.DatabaseConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class TenantOrganizationMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.6")
    }

    // 场景：租户数据库迁移后分别保存企业、账套、组织、员工、用户和任职
    @Test
    fun shouldKeepCompanyBookOrganizationAndIdentityAsSeparateModels() {
        // Given：空租户数据库和独立的租户迁移目录
        val projectDir=Path.of(System.getProperty("ztx.projectDir"))
        val config=DatabaseConfig(host=postgres.host,port=postgres.firstMappedPort,
            database=postgres.databaseName,user=postgres.username,password=postgres.password,
            poolSize=2,migrationLocations="filesystem:${projectDir.resolve("database/tenant-migrations")}")
        // When：执行租户数据库版本化迁移
        assertTrue(FlywayMigrator(config).migrate() >= 3)
        // Then：企业、账套、组织、人事、身份和授权表分别存在
        DriverManager.getConnection(config.jdbcUrl,config.user,config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT count(*) FROM information_schema.tables
                    WHERE table_schema IN ('org','hr','iam') AND table_name IN
                    ('company','book','organization_unit','employee','employment','user_account',
                     'job_position','module_role','position_module_role','employment_position','role_permission')""").use { rows ->
                    rows.next(); assertEquals(11,rows.getInt(1))
                }
            }
        }
    }

    // 场景：一个企业可有多个账套，组织不要求与账套一一对应，员工和账号也可独立存在
    @Test
    fun shouldAllowIndependentBooksEmployeesAndUserAccounts() {
        // Given：租户数据库已迁移，准备两个企业、多个账套、组织、员工和用户
        val config=migratedConfig()
        DriverManager.getConnection(config.jdbcUrl,config.user,config.password).use { connection ->
            val company=UUID.randomUUID(); val otherCompany=UUID.randomUUID()
            val bookA=UUID.randomUUID(); val bookB=UUID.randomUUID()
            val unit=UUID.randomUUID(); val employee=UUID.randomUUID(); val user=UUID.randomUUID()
            connection.prepareStatement("INSERT INTO org.company(id,code,name) VALUES (?,?,?)").use { s ->
                s.setObject(1,company);s.setString(2,"acme");s.setString(3,"Acme");s.executeUpdate()
                s.setObject(1,otherCompany);s.setString(2,"other");s.setString(3,"Other");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO org.book(id,company_id,code,name) VALUES (?,?,?,?)").use { s ->
                listOf(bookA to "MAIN",bookB to "TAX").forEach { (id,code) ->
                    s.setObject(1,id);s.setObject(2,company);s.setString(3,code);s.setString(4,code);s.executeUpdate()
                }
            }
            connection.prepareStatement("""INSERT INTO org.organization_unit(id,company_id,code,name,unit_type)
                VALUES (?,?,?,'一号工厂','FACTORY')""").use { s ->
                s.setObject(1,unit);s.setObject(2,company);s.setString(3,"F1");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employee(id,employee_no,name) VALUES (?,?,?)").use { s ->
                s.setObject(1,employee);s.setString(2,"E001");s.setString(3,"张三");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.user_account(id,username,display_name) VALUES (?,?,?)").use { s ->
                s.setObject(1,user);s.setString(2,"user.only");s.setString(3,"独立账号");s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO hr.employment(id,employee_id,company_id,organization_unit_id,starts_on)
                VALUES (?,?,?,?,'2026-01-01')""").use { s ->
                s.setObject(1,UUID.randomUUID());s.setObject(2,employee);s.setObject(3,company);s.setObject(4,unit);s.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO hr.employment(id,employee_id,user_id,company_id,starts_on)
                VALUES (?,?,?,?,'2026-02-01')""").use { s ->
                s.setObject(1,UUID.randomUUID());s.setObject(2,employee);s.setObject(3,user);s.setObject(4,otherCompany);s.executeUpdate()
            }
            // Then：同一企业可以有多个账套；组织不依赖账套；员工可有多个任职，账号可独立存在
            connection.createStatement().executeQuery("SELECT count(*) FROM org.book WHERE company_id='$company'").use { r ->
                r.next();assertEquals(2,r.getInt(1))
            }
            connection.createStatement().executeQuery("SELECT count(*) FROM hr.employment WHERE employee_id='$employee'").use { r ->
                r.next();assertEquals(2,r.getInt(1))
            }
            connection.createStatement().executeQuery("SELECT count(*) FROM iam.user_account WHERE id='$user'").use { r ->
                r.next();assertEquals(1,r.getInt(1))
            }
        }
    }

    // 场景：岗位权限只能分配给同一企业任职，避免跨企业扩大数据范围
    @Test
    fun shouldRejectPositionOutsideEmploymentCompany() {
        // Given：两个企业分别拥有任职和岗位
        val config=migratedConfig()
        DriverManager.getConnection(config.jdbcUrl,config.user,config.password).use { connection ->
            val companyA=UUID.randomUUID();val companyB=UUID.randomUUID()
            val employee=UUID.randomUUID();val employment=UUID.randomUUID();val position=UUID.randomUUID()
            connection.prepareStatement("INSERT INTO org.company(id,code,name) VALUES (?,?,'A'),(?,?,'B')").use { s ->
                s.setObject(1,companyA);s.setString(2,"a");s.setObject(3,companyB);s.setString(4,"b");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employee(id,employee_no,name) VALUES (?,?,'员工')").use { s ->
                s.setObject(1,employee);s.setString(2,"E002");s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO hr.employment(id,employee_id,company_id,starts_on) VALUES (?,?,?,'2026-01-01')").use { s ->
                s.setObject(1,employment);s.setObject(2,employee);s.setObject(3,companyA);s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam.job_position(id,company_id,code,name) VALUES (?,?,?,'岗位')").use { s ->
                s.setObject(1,position);s.setObject(2,companyB);s.setString(3,"P1");s.executeUpdate()
            }
            // When：尝试把企业 B 的岗位分配给企业 A 的任职
            // Then：数据库触发器拒绝跨企业岗位授权
            assertThrows(SQLException::class.java) {
                connection.prepareStatement("INSERT INTO hr.employment_position(employment_id,position_id) VALUES (?,?)").use { s ->
                    s.setObject(1,employment);s.setObject(2,position);s.executeUpdate()
                }
            }
        }
    }

    private fun migratedConfig():DatabaseConfig {
        val projectDir=Path.of(System.getProperty("ztx.projectDir"))
        val config=DatabaseConfig(host=postgres.host,port=postgres.firstMappedPort,
            database=postgres.databaseName,user=postgres.username,password=postgres.password,
            poolSize=2,migrationLocations="filesystem:${projectDir.resolve("database/tenant-migrations")}")
        FlywayMigrator(config).migrate()
        return config
    }
}
