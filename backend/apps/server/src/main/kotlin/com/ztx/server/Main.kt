package com.ztx.server

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.adapters.postgres.PostgresModule
import com.ztx.adapters.postgres.PostgresOutboxStore
import com.ztx.modules.document.DocumentRuntimeModule
import com.ztx.modules.iam.IamModule
import com.ztx.modules.metadata.MetadataModule
import com.ztx.platform.common.AppConfig
import com.ztx.platform.common.ModuleRegistry
import com.ztx.platform.common.RuntimeRole
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

    if (config.migrateOnStart) {
        val count = FlywayMigrator(config.database).migrate()
        logger.info("数据库迁移完成，执行迁移数：{}", count)
    }

    val vertx = Vertx.vertx()
    val database = PgDatabase(vertx, config.database)
    val outboxStore = PostgresOutboxStore(database)
    // 具体业务处理器由后续平台能力/业务扩展注册；空处理器集合不会领取未知任务。
    val worker = BackgroundTaskWorker(
        vertx = vertx,
        database = database,
        store = outboxStore,
        workerId = "ztx-worker-${UUID.randomUUID()}",
        handlers = emptyMap()
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        worker.close()
        database.close()
        vertx.close()
    })

    when (config.role) {
        RuntimeRole.SERVER -> HealthServer(vertx, database, config.httpHost, config.httpPort)
            .start()
            .onSuccess {
                logger.info("ZTX API Server 已启动，监听 {}:{}", config.httpHost, config.httpPort)
            }
            .onFailure { error ->
                logger.error("ZTX API Server 启动失败", error)
                database.close()
                vertx.close()
                exitProcess(1)
            }

        RuntimeRole.WORKER -> database.ping()
            .compose { worker.start() }
            .onSuccess {
                logger.info("ZTX Worker 运行角色已启动，数据库连接正常；已注册任务处理器：0")
            }
            .onFailure { error ->
                logger.error("ZTX Worker 启动失败", error)
                worker.close()
                database.close()
                vertx.close()
                exitProcess(1)
            }
    }
}
