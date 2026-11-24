# ZTX

ZTX 是面向制造业的元数据驱动低代码 ERP 平台。当前仓库采用 Monorepo 与模块化单体，先建设可复用的平台基座，不在基座工程中提前实现采购、库存、成本、生产或财务业务应用。

## 本地开发

环境要求：JDK 21 LTS、Gradle Wrapper 9.7.0、Docker Engine / Docker Compose。前端开发另需 Node.js 26.13.1 和 pnpm 12.18.0。

首次初始化：

    cp .env.example .env
    docker compose up -d postgres
    ./gradlew clean test build

启动 API Server（默认加载 application.yaml）：

    ./gradlew :apps:server:run

使用开发环境配置（包含仅供本地使用的数据库默认密码）：

    ./gradlew :apps:server:run --args='--ztx.profile=dev'

启动 Worker 角色：

    ZTX_APP_ROLE=worker ./gradlew :apps:server:run --args='--ztx.profile=dev'

健康检查：
- GET http://localhost:8080/health/live：只反映进程存活，不依赖数据库。
- GET http://localhost:8080/health/ready：检查 PostgreSQL 连通性；数据库不可用时返回 HTTP 503。

开发环境首次启动会自动执行 Flyway 数据库迁移：

    ./gradlew :apps:server:run --args='--ztx.profile=dev'

首次启动时，若尚未创建平台管理员，Server 会自动生成 15 分钟有效的一次性初始化口令。开发环境口令显示在启动终端；每次重启都会使旧口令失效并生成新口令。打开 ZTX 地址即可自动进入初始化页面，创建成功后自动登录。

正式部署由受控部署流程执行数据库迁移，应用启动只校验迁移状态，不自动修改生产数据库。首次初始化口令不会写入普通应用日志：可在服务器交互式终端查看，或通过服务器本机/SSH 本地端口转发访问 loopback 安装管理接口 `GET /api/v1/platform/auth/setup-token` 获取。该接口仅接受 loopback 来源及本机 Host，拒绝转发请求；口令仅保存在当前 Server 进程内存，重启轮换，15 分钟后失效。

平台管理员首次初始化只需用户名和密码，不要求邮箱或 MFA。以后启用 MFA 时，才需要在 `application.yaml` 的 `security.mfa-encryption-key` 配置至少 32 字节随机密钥；可使用 `openssl rand -base64 32` 生成。密钥配置后必须长期备份，不能随意更换。

## 配置约定

后端以 `application.yaml` 为唯一默认配置入口，支持 `application-{profile}.yaml`。默认不激活 Profile；指定 `--ztx.profile=dev`、系统属性 `-Dztx.profile=dev` 或环境变量 `ZTX_PROFILE=dev` 后，加载对应 Profile 文件。Profile 名称必须对应仓库内存在的配置文件，拼写错误会在启动时直接报错。加载顺序为：

1. classpath 的 `application.yaml`；
2. classpath 的 `application-{profile}.yaml`（如果指定 Profile）；
3. 外部 YAML 文件（通过 `--ztx.config=/path/to/application.yaml`、`-Dztx.config=...` 或 `ZTX_CONFIG_FILE` 指定）；
4. 明确支持的环境变量覆盖（如 `ZTX_DB_PASSWORD`、`ZTX_HTTP_PORT`）。

后加载的同名配置覆盖先加载的值；嵌套对象按键递归合并。业务模块只读取统一的 `AppConfig`，不得直接读取环境变量或自行解析 YAML。普通私有化部署可直接在外部 YAML 中配置数据库账号和密码；环境变量/Secrets 是可选的覆盖和敏感信息注入方式。Docker Compose 的 `.env` 只负责 Compose 变量插值，不是后端配置文件。真实密码和私有部署配置不得提交 Git。

默认配置位于 `backend/platform/common/src/main/resources/application.yaml`；dev、test、prod Profile 文件与其放在同一资源目录。dev Profile 内的默认密码仅用于本机开发，不得用于生产。配置加载失败时在启动阶段报错，不输出密码等敏感值。

## 工程结构

- backend/apps/server：API Server / Worker 启动入口与 HTTP 健康检查。
- backend/platform/common：运行角色、统一配置加载与模块注册契约。
- backend/adapters/postgres：Vert.x PostgreSQL 连接池、连通性检查和 Flyway 迁移。
- backend/modules：平台模块边界与依赖声明。
- backend/tests/bdd：JUnit 5、Testcontainers 与中文 BDD 场景注释。
- database/migrations：集中管理的数据库迁移。
- frontend：Vue Web 工作区。

目录和 Gradle 子项目用于职责隔离与测试，不代表独立部署或微服务拆分。

## 生产静态资源策略

生产发布时由前端流水线构建 erp-web，将 dist 产物纳入后端发布包的 webroot 资源目录；Vert.x 在 API 路由之后提供静态资源和 SPA fallback。当前 Issue #3 先交付 Server/Worker 启动及健康检查，前端资源打包接入随前端发布流程完成，不在此处复制或重建前端应用。

## 常用命令

- ./gradlew clean：清理构建产物。
- ./gradlew test：运行全部后端测试。
- ./gradlew build：构建全部后端模块。
- ./gradlew :apps:server:run：启动 API Server。
- docker compose up -d postgres：启动本地 PostgreSQL。
- docker compose down：停止本地依赖（保留数据卷）。

### 环境变量覆盖清单

- `ZTX_PROFILE`：激活配置 Profile。
- `ZTX_CONFIG_FILE`：指定外部 YAML 配置文件。
- `ZTX_APP_ROLE`：覆盖运行角色（server / worker）。
- `ZTX_HTTP_HOST`、`ZTX_HTTP_PORT`：覆盖 HTTP 监听地址和端口。
- `ZTX_DB_HOST`、`ZTX_DB_PORT`、`ZTX_DB_NAME`、`ZTX_DB_USER`、`ZTX_DB_PASSWORD`：覆盖数据库连接参数。
- `ZTX_DB_POOL_SIZE`、`ZTX_DB_MIGRATIONS`、`ZTX_DB_MIGRATE_ON_START`：覆盖连接池和迁移设置。

未列出的环境变量不会自动注入配置，避免环境变量意外覆盖 YAML 中的设置。
