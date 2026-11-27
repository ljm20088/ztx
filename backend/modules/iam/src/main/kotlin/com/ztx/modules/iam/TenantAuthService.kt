package com.ztx.modules.iam

import com.ztx.adapters.postgres.PgDatabase
import io.vertx.core.Future
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.util.UUID

data class TenantPrincipal(
    val tenantId: UUID,
    val tenantCode: String,
    val databaseName: String,
    val userId: UUID,
    val username: String,
    val role: String,
    val database: PgDatabase,
    val isGroupAdmin: Boolean = false,
    val subjectType: String = "ADMIN",
    val companyIds: Set<UUID> = emptySet(),
    val bookIds: Set<UUID> = emptySet(),
    val organizationUnitIds: Set<UUID> = emptySet()
)

data class TenantLogin(val sessionToken: String, val principal: TenantPrincipal)
private data class TenantLoginOutcome(val login: TenantLogin?)
private data class TenantPrincipalLookup(val principal: TenantPrincipal?)
private data class TenantPasswordChangeOutcome(val changed: Boolean)

/** 租户管理员与业务用户共享租户数据库，但使用独立会话和审计主体。 */
class TenantAuthService(private val databases: TenantDatabaseRegistry) {
    fun login(tenantCode:String,username:String,password:String,ip:String?,userAgent:String?):Future<TenantLogin> {
        val normalizedUsername=username.trim()
        if(!normalizedUsername.matches(Regex("[A-Za-z0-9._-]{3,80}")))return Future.failedFuture("租户编码、账号或密码不正确")
        return databases.resolve(tenantCode).compose { tenantDatabase ->
            val attemptKey=AuthSupport.sha256("${tenantDatabase.tenant.id}|${ip ?: "unknown"}|${normalizedUsername.lowercase()}")
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery("SELECT locked_until FROM iam.tenant_login_attempt WHERE key_hash=$1")
                    .execute(Tuple.of(attemptKey)).compose { attempts ->
                        val lockedUntil=attempts.firstOrNull()?.getOffsetDateTime("locked_until")
                        if(lockedUntil!=null && lockedUntil.toInstant().isAfter(Instant.now())) {
                            AuthSupport.audit(connection,AuthScope.TENANT,null,"LOGIN","DENIED",ip).map { TenantLoginOutcome(null) }
                        } else {
                            connection.preparedQuery(
                                """SELECT id,username,password_hash,is_group_admin,role,subject_type FROM (
                                  SELECT id::text AS id,username,password_hash,is_group_admin,
                                    'TENANT_ADMIN' AS role,'ADMIN' AS subject_type,0 AS priority
                                  FROM iam.tenant_admin WHERE lower(username)=lower($1) AND disabled_at IS NULL
                                  UNION ALL
                                  SELECT id::text AS id,username,password_hash,is_group_admin,
                                    'TENANT_USER' AS role,'USER' AS subject_type,1 AS priority
                                  FROM iam.user_account WHERE lower(username)=lower($1)
                                    AND status='ACTIVE' AND password_hash IS NOT NULL
                                ) accounts ORDER BY priority LIMIT 1"""
                            ).execute(Tuple.of(normalizedUsername)).compose { rows ->
                                val row=rows.firstOrNull()
                                val valid=row!=null && AuthSupport.verifyPassword(password,row.getString("password_hash"))
                                if(!valid) {
                                    val scope=if(row?.getString("subject_type")=="USER")AuthScope.TENANT_USER else AuthScope.TENANT
                                    AuthSupport.recordFailure(connection,AuthScope.TENANT,attemptKey).compose {
                                        AuthSupport.audit(connection,scope,row?.getString("id")?.let(UUID::fromString),"LOGIN","FAILURE",ip)
                                    }.map { TenantLoginOutcome(null) }
                                } else {
                                    val account=requireNotNull(row)
                                    val userId=UUID.fromString(account.getString("id"))
                                    val isUser=account.getString("subject_type")=="USER"
                                    val token=AuthSupport.randomToken()
                                    val insertSession=if(isUser) {
                                        """INSERT INTO iam.user_session(id,user_id,token_hash,expires_at,user_agent,ip_address)
                                           VALUES ($1,$2,$3,clock_timestamp()+INTERVAL '8 hours',$4,$5::inet)"""
                                    } else {
                                        """INSERT INTO iam.tenant_admin_session(id,admin_id,token_hash,expires_at,user_agent,ip_address)
                                           VALUES ($1,$2,$3,clock_timestamp()+INTERVAL '8 hours',$4,$5::inet)"""
                                    }
                                    val updateLastLogin=if(isUser)
                                        "UPDATE iam.user_account SET last_login_at=clock_timestamp() WHERE id=$1"
                                    else "UPDATE iam.tenant_admin SET last_login_at=clock_timestamp() WHERE id=$1"
                                    val scope=if(isUser)AuthScope.TENANT_USER else AuthScope.TENANT
                                    connection.preparedQuery(insertSession).execute(Tuple.of(
                                        UUID.randomUUID(),userId,AuthSupport.sha256(token),userAgent?.take(512),
                                        AuthSupport.postgresInet(ip,invalidAsNull=true)
                                    )).compose { connection.preparedQuery(updateLastLogin).execute(Tuple.of(userId)) }
                                        .compose { connection.preparedQuery("DELETE FROM iam.tenant_login_attempt WHERE key_hash=$1").execute(Tuple.of(attemptKey)) }
                                        .compose { AuthSupport.audit(connection,scope,userId,"LOGIN","SUCCESS",ip) }
                                        .map {
                                            TenantLoginOutcome(TenantLogin(token,TenantPrincipal(
                                                tenantDatabase.tenant.id,tenantDatabase.tenant.code,tenantDatabase.tenant.databaseName,
                                                userId,account.getString("username"),account.getString("role"),tenantDatabase.database,
                                                account.getBoolean("is_group_admin"),account.getString("subject_type")
                                            )))
                                        }
                                }
                            }
                        }
                    }
            }.compose { result ->
                if(result.login==null)Future.failedFuture("租户编码、账号或密码不正确")
                else Future.succeededFuture(result.login)
            }
        }.recover { Future.failedFuture("租户编码、账号或密码不正确") }
    }

    fun authenticate(tenantCode:String,rawToken:String):Future<TenantPrincipal?> =
        databases.resolve(tenantCode).compose { tenantDatabase ->
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    """SELECT id,username,role,subject_type,is_group_admin FROM (
                       SELECT admin.id::text AS id,admin.username,'TENANT_ADMIN' AS role,
                         'ADMIN' AS subject_type,admin.is_group_admin
                       FROM iam.tenant_admin_session session JOIN iam.tenant_admin admin ON admin.id=session.admin_id
                       WHERE session.token_hash=$1 AND session.revoked_at IS NULL
                         AND session.expires_at>clock_timestamp() AND admin.disabled_at IS NULL
                       UNION ALL
                       SELECT account.id::text AS id,account.username,'TENANT_USER' AS role,
                         'USER' AS subject_type,account.is_group_admin
                       FROM iam.user_session session JOIN iam.user_account account ON account.id=session.user_id
                       WHERE session.token_hash=$1 AND session.revoked_at IS NULL
                         AND session.expires_at>clock_timestamp() AND account.status='ACTIVE'
                    ) sessions LIMIT 1"""
                ).execute(Tuple.of(AuthSupport.sha256(rawToken))).map { rows ->
                    TenantPrincipalLookup(rows.firstOrNull()?.let { row ->
                        TenantPrincipal(tenantDatabase.tenant.id,tenantDatabase.tenant.code,tenantDatabase.tenant.databaseName,
                            UUID.fromString(row.getString("id")),row.getString("username"),row.getString("role"),
                            tenantDatabase.database,row.getBoolean("is_group_admin"),row.getString("subject_type"))
                    })
                }
            }.compose { lookup ->
                val principal = lookup.principal ?: return@compose Future.succeededFuture<TenantPrincipal?>(null)
                tenantDatabase.database.withTransaction { connection ->
                    if (principal.subjectType == "ADMIN") {
                        connection.preparedQuery("SELECT id FROM org.company WHERE status='ACTIVE'").execute()
                            .compose { companies ->
                                connection.preparedQuery("SELECT id FROM org.book WHERE status='ACTIVE'").execute()
                                    .compose { books ->
                                        connection.preparedQuery("SELECT id FROM org.organization_unit WHERE status='ACTIVE'").execute()
                                            .map { units ->
                                                principal.copy(
                                                    companyIds = companies.map { it.getUUID("id") }.toSet(),
                                                    bookIds = books.map { it.getUUID("id") }.toSet(),
                                                    organizationUnitIds = units.map { it.getUUID("id") }.toSet()
                                                )
                                            }
                                    }
                            }
                    } else {
                        connection.preparedQuery(
                            """SELECT DISTINCT company_id,book_id,organization_unit_id FROM hr.employment
                               WHERE user_id=$1 AND status='ACTIVE' AND starts_on<=CURRENT_DATE
                                 AND (ends_on IS NULL OR ends_on>=CURRENT_DATE)"""
                        ).execute(Tuple.of(principal.userId)).map { employments ->
                            principal.copy(
                                companyIds = employments.map { it.getUUID("company_id") }.toSet(),
                                bookIds = employments.mapNotNull { it.getUUID("book_id") }.toSet(),
                                organizationUnitIds = employments.mapNotNull { it.getUUID("organization_unit_id") }.toSet()
                            )
                        }
                    }
                }.map<TenantPrincipal?> { it }
            }
        }.recover { Future.succeededFuture<TenantPrincipal?>(null) }

    fun logout(tenantCode:String,rawToken:String,ip:String?=null):Future<Void> =
        databases.resolve(tenantCode).compose { tenantDatabase ->
            tenantDatabase.database.withTransaction { connection ->
                connection.preparedQuery(
                    """UPDATE iam.tenant_admin_session SET revoked_at=clock_timestamp()
                       WHERE token_hash=$1 AND revoked_at IS NULL RETURNING admin_id::text AS subject_id"""
                ).execute(Tuple.of(AuthSupport.sha256(rawToken))).compose { adminRows ->
                    if(adminRows.size()>0) {
                        AuthSupport.audit(connection,AuthScope.TENANT,UUID.fromString(adminRows.first().getString("subject_id")),"LOGOUT","SUCCESS",ip)
                    } else {
                        connection.preparedQuery(
                            """UPDATE iam.user_session SET revoked_at=clock_timestamp()
                               WHERE token_hash=$1 AND revoked_at IS NULL RETURNING user_id::text AS subject_id"""
                        ).execute(Tuple.of(AuthSupport.sha256(rawToken))).compose { userRows ->
                            val userId=userRows.firstOrNull()?.getString("subject_id")?.let(UUID::fromString)
                            if(userId==null)Future.succeededFuture<Void>()
                            else AuthSupport.audit(connection,AuthScope.TENANT_USER,userId,"LOGOUT","SUCCESS",ip)
                        }
                    }
                }
            }
        }.recover { Future.succeededFuture<Void>() }

    fun changePassword(principal:TenantPrincipal,currentToken:String,oldPassword:String,newPassword:String,ip:String?):Future<Void> {
        if(newPassword.length<8 || !newPassword.any(Char::isLetter) || !newPassword.any(Char::isDigit))
            return Future.failedFuture("密码至少 8 位，且必须同时包含字母和数字")
        val isUser=principal.subjectType=="USER"
        val table=if(isUser)"iam.user_account" else "iam.tenant_admin"
        val sessionTable=if(isUser)"iam.user_session" else "iam.tenant_admin_session"
        val subjectColumn=if(isUser)"user_id" else "admin_id"
        val scope=if(isUser)AuthScope.TENANT_USER else AuthScope.TENANT
        val newHash=AuthSupport.hashPassword(newPassword)
        return principal.database.withTransaction { connection ->
            connection.preparedQuery("SELECT password_hash FROM $table WHERE id=$1").execute(Tuple.of(principal.userId)).compose { rows ->
                val currentHash=rows.firstOrNull()?.getString("password_hash")
                if(currentHash==null || !AuthSupport.verifyPassword(oldPassword,currentHash)) {
                    AuthSupport.audit(connection,scope,principal.userId,"PASSWORD_CHANGE","FAILURE",ip)
                        .map { TenantPasswordChangeOutcome(false) }
                } else if(AuthSupport.verifyPassword(newPassword,currentHash)) {
                    Future.failedFuture<TenantPasswordChangeOutcome>("新密码不能与当前密码相同")
                } else {
                    connection.preparedQuery("UPDATE $table SET password_hash=$2,updated_at=clock_timestamp() WHERE id=$1")
                        .execute(Tuple.of(principal.userId,newHash)).compose {
                            connection.preparedQuery("UPDATE $sessionTable SET revoked_at=clock_timestamp() WHERE $subjectColumn=$1 AND token_hash<>$2 AND revoked_at IS NULL")
                                .execute(Tuple.of(principal.userId,AuthSupport.sha256(currentToken)))
                        }.compose { AuthSupport.audit(connection,scope,principal.userId,"PASSWORD_CHANGED","SUCCESS",ip) }
                        .map { TenantPasswordChangeOutcome(true) }
                }
            }
        }.compose { result ->
            if(result.changed)Future.succeededFuture<Void>() else Future.failedFuture<Void>("当前密码不正确")
        }
    }
}
