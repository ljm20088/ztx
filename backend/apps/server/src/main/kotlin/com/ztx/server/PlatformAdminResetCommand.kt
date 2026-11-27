package com.ztx.server

import com.ztx.adapters.postgres.FlywayMigrator
import com.ztx.adapters.postgres.PgDatabase
import com.ztx.modules.iam.PlatformAuthService
import com.ztx.platform.common.AppConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.io.Console
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 仅在服务器交互式终端执行的平台管理员密码恢复命令。
 * 密码由 Console.readPassword 无回显读取，不接受命令行参数或环境变量传入。
 */
object PlatformAdminResetCommand {
    fun execute(config: AppConfig, console: Console? = System.console()): Int {
        val vertx = Vertx.vertx()
        val database = PgDatabase(vertx, config.database)
        val operator = System.getProperty("user.name")?.takeIf(String::isNotBlank) ?: "unknown"
        val service = PlatformAuthService(database, config.mfaEncryptionKey)

        return try {
            // 只校验迁移状态，不在密码恢复命令中自动修改数据库结构。
            FlywayMigrator(config.database).validateCurrent()
            database.ping().await()

            if (console == null) {
                service.recordPasswordResetCommandFailure(operator, "INTERACTIVE_TERMINAL_REQUIRED").await()
                System.err.println("密码重置必须在服务器交互式终端执行；请直接登录服务器后重试。")
                return 2
            }
            promptAndReset(console, service, operator)
        } catch (error: Exception) {
            System.err.println("平台管理员密码重置未完成。请检查数据库连接、迁移状态和服务器日志；不要将密码或凭据写入日志。")
            1
        } finally {
            database.close()
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }

    private fun promptAndReset(
        console: Console,
        service: PlatformAuthService,
        operator: String
    ): Int {
        console.printf("%nZTX 平台管理员密码重置%n")
        console.printf("此操作只重置现有唯一平台管理员的密码，保留 MFA，并撤销全部现有会话。%n")
        console.printf("操作者：%s%n%n", operator)

        val password = console.readPassword("新密码（至少 8 位，包含字母和数字）：")
        if (password == null) {
            service.recordPasswordResetCommandFailure(operator, "PASSWORD_INPUT_CANCELLED").await()
            System.err.println("密码重置已取消。")
            return 1
        }
        val confirmation = try {
            console.readPassword("再次输入新密码：")
        } catch (error: Exception) {
            password.fill('\u0000')
            service.recordPasswordResetCommandFailure(operator, "PASSWORD_CONFIRMATION_READ_FAILED").await()
            System.err.println("无法读取确认密码，操作已取消。")
            return 1
        }

        if (confirmation == null) {
            password.fill('\u0000')
            service.recordPasswordResetCommandFailure(operator, "PASSWORD_CONFIRMATION_CANCELLED").await()
            System.err.println("密码重置已取消。")
            return 1
        }

        return try {
            val matches = MessageDigest.isEqual(
                String(password).toByteArray(Charsets.UTF_8),
                String(confirmation).toByteArray(Charsets.UTF_8)
            )
            if (!matches) {
                service.recordPasswordResetCommandFailure(operator, "PASSWORD_CONFIRMATION_MISMATCH").await()
                System.err.println("两次输入不一致，密码未修改。")
                1
            } else {
                val result = service.resetPasswordFromServerCommand(String(password), operator).await()
                if (result.success) {
                    console.printf("%n平台管理员密码重置成功。所有现有平台会话已撤销，MFA 设置保持不变。%n")
                    0
                } else {
                    System.err.println("密码重置失败：未满足唯一管理员或密码策略要求；详细原因已写入安全审计。")
                    1
                }
            }
        } finally {
            password.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS)
}
