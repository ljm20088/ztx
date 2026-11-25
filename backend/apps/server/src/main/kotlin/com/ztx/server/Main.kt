package com.ztx.server

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.adapters.postgres.PostgresModule
import com.ztx.adapters.postgres.PostgresOutboxStore
import com.ztx.modules.document.DocumentRuntimeModule
import com.ztx.modules.iam.IamModule
import com.ztx.modules.iam.PlatformAuthRoutes
import com.ztx.modules.iam.PlatformAuthService
import com.ztx.modules.iam.TenantLifecycleRoutes
import com.ztx.modules.iam.TenantLifecycleService
import com.ztx.modules.iam.TenantDatabaseRegistry
import com.ztx.modules.iam.TenantAuthService
import com.ztx.modules.iam.TenantAuthRoutes
import com.ztx.modules.metadata.MetadataModule
import com.ztx.platform.common.AppConfig
import com.ztx.platform.common.ModuleRegistry
import com.ztx.platform.common.RuntimeRole
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("com.ztx.server")

fun main(args: Array<String>) {
    val config = AppConfig.load(args)
    val moduleOrder = ModuleRegistry()
        .register(PostgresModule())
        .register(IamModule())
        .register(MetadataModule())
        .register(DocumentRuntimeModule())
        .validateAndOrder()
    logger.info("ZTX 模块装配完成：{}", moduleOrder.joinToString(" -> ") { it.id })

    val migrator = FlywayMigrator(config.database)
    if (config.migrateOnStart) {
        val count = migrator.migrate()
        logger.info("数据库迁移完成，执行迁移数：{}", count)
    } else {
        // 正式部署只校验迁移状态；数据库变更由受控部署流程执行。
        migrator.validateCurrent()
    }

    val vertx = Vertx.vertx()
    val database = PgDatabase(vertx, config.database)
    val outboxStore = PostgresOutboxStore(database)
    val authService = PlatformAuthService(database, config.mfaEncryptionKey)
    val tenantService = TenantLifecycleService(vertx, database, config.database)
    val tenantRoutes = TenantLifecycleRoutes(authService, tenantService)
    val tenantDatabases = TenantDatabaseRegistry(vertx, tenantService, config.database)
    val tenantAuthService = TenantAuthService(tenantDatabases)
    val tenantAuthRoutes = TenantAuthRoutes(tenantAuthService, tenantService, config.secureCookies)

    // 具体业务处理器由后续平台能力/业务扩展注册；空处理器集合不会领取未知任务。
    val worker = BackgroundTaskWorker(
        vertx = vertx, database = database, store = outboxStore,
        workerId = "ztx-worker-" + UUID.randomUUID(), handlers = emptyMap()
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        worker.close()
        tenantDatabases.close()
        database.close()
        vertx.close()
    })

    when (config.role) {
        RuntimeRole.SERVER -> database.ping()
            .compose { authService.initializationStatus() }
            .compose { initialized ->
                if (initialized) Future.succeededFuture<String?>(null)
                else authService.createSetupCredential().map<String?> { it }
            }
            .compose { credential ->
                if (credential != null) {
                    val isDevelopment = args.any { it == "--ztx.profile=dev" } ||
                        System.getProperty("ztx.profile") == "dev" || System.getenv("ZTX_PROFILE") == "dev"
                    if (isDevelopment) {
                        // 开发环境终端提示仅供本机使用；正式环境不写入普通应用日志。
                        System.out.println("ZTX 首次初始化口令（15 分钟有效）：")
                        System.out.println(credential)
                    } else {
                        // 正式部署仅在交互式服务器终端显示；无终端时通过 loopback 安装管理入口获取。
                        System.console()?.writer()?.let { writer ->
                            writer.println("ZTX 首次初始化口令（15 分钟有效）：")
                            writer.println(credential)
                            writer.flush()
                        }
                    }
                }
                HealthServer(
                    vertx, database, config.httpHost, config.httpPort,
                    PlatformAuthRoutes(authService, config.secureCookies), tenantRoutes, tenantAuthRoutes
                ).start()
            }
            .onSuccess { logger.info("ZTX API Server 已启动，监听 {}:{}", config.httpHost, config.httpPort) }
            .onFailure { error ->
                logger.error("ZTX API Server 启动失败", error)
                database.close()
                vertx.close()
                exitProcess(1)
            }
        RuntimeRole.WORKER -> database.ping()
            .compose { worker.start() }
            .onSuccess { logger.info("ZTX Worker 运行角色已启动，数据库连接正常；已注册任务处理器：0") }
            .onFailure { error ->
                logger.error("ZTX Worker 启动失败", error)
                worker.close()
                database.close()
                vertx.close()
                exitProcess(1)
            }
    }
}
