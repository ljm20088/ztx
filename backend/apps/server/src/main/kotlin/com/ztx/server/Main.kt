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
        migrator.validateCurrent()
    }

    val vertx = Vertx.vertx()
    val database = PgDatabase(vertx, config.database)
    val outboxStore = PostgresOutboxStore(database)
    val authService = PlatformAuthService(database, config.mfaEncryptionKey)
    val tenantService = TenantLifecycleService(vertx, database, config.database)
    val tenantRoutes = TenantLifecycleRoutes(authService, tenantService)
    val worker = BackgroundTaskWorker(
        vertx = vertx, database = database, store = outboxStore,
        workerId = "ztx-worker-" + UUID.randomUUID(), handlers = emptyMap()
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        worker.close()
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
                        System.out.println("ZTX 首次初始化口令（15 分钟有效）：")
                        System.out.println(credential)
                    } else {
                        System.console()?.writer()?.let { writer ->
                            writer.println("ZTX 首次初始化口令（15 分钟有效）：")
                            writer.println(credential)
                            writer.flush()
                        }
                    }
                }
                HealthServer(
                    vertx, database, config.httpHost, config.httpPort,
                    PlatformAuthRoutes(authService, config.secureCookies), tenantRoutes
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
