# ZTX 统一启动与运行模式

## 目标

ZTX 以 Vert.x Server 作为 Web/API 统一运行入口，Worker 保持独立进程。项目根目录的 `./ztx` 只负责编排本地开发进程，不承载业务配置、数据库迁移或业务初始化逻辑。

## 命令

| 命令 | 行为 | 浏览器入口 |
|---|---|---|
| `./ztx dev` | 启动 PostgreSQL、Vert.x Server、Worker、Vite HMR | `http://localhost:5173` |
| `./ztx start` | 构建前端、将 dist 打包进 Server、启动 Server 和 Worker | `http://localhost:8080` |
| `./ztx status` | 查看本启动器管理的进程 | — |
| `./ztx stop` | 停止本启动器启动的应用进程 | — |

开发模式下 Vite 只负责页面模块热更新，`/api` 请求代理到 Vert.x。集成运行模式下不启动 Vite，Vert.x 同时提供静态页面和 API。

## 职责边界

- 启动器：检查 JDK/Node/pnpm/Docker、启动并等待 PostgreSQL、构建前端、启动子进程、记录 PID、汇总日志、停止自己启动的进程。
- Server：加载统一配置、执行开发环境 Flyway 迁移或正式环境迁移校验、平台管理员初始化、HTTP API、健康检查、静态资源托管。
- Worker：独立运行后台任务消费；当前无业务处理器时空闲等待，不领取未知任务。
- PostgreSQL：由 Docker Compose 提供本地开发数据库；生产数据库由部署环境管理。

## 静态资源打包

Gradle Server 的 `processResources` 接受 `ztx.frontendDist` 项目属性，将指定目录复制到 classpath 的 `webroot/`。`./ztx start` 先执行前端生产构建，再通过该属性执行 `installDist`。Vert.x 的 StaticHandler 从 classpath `webroot` 提供页面和资源。

生产部署只发布后端发行包（其中已包含前端静态资源）和必要配置。生产服务器不需要 Node.js、pnpm、Vite 或 Nginx。

## 进程与日志

- PID 与日志存放在仓库根目录 `.ztx/pids`、`.ztx/logs`。
- 启动器设置目录权限为当前用户可访问；日志可能包含开发环境的一次性平台管理员初始化口令，不得上传或共享。
- `stop` 仅按启动器保存的 PID 停止其启动的进程，不通过端口号查找并杀死其他程序。
- 数据库容器不会由 `stop` 自动关闭，以免意外影响本地数据；可单独执行 `docker compose stop postgres`。
- Server 或 Worker 启动失败时，查看对应日志并执行 `./ztx stop` 清理其余进程。

## 后端独立运行

启动器不是运行时依赖。后端仍可直接通过 Gradle 分别启动：

```bash
./gradlew :apps:server:run --args='--ztx.profile=dev'
ZTX_APP_ROLE=worker ./gradlew :apps:server:run --args='--ztx.profile=dev'
```

生产部署流程应独立负责前端构建产物的生成、纳入发行包、数据库迁移、配置和 Server/Worker 进程管理；不运行 `./ztx`。
