# ZTX

ZTX 是面向制造业的元数据驱动低代码 ERP 平台。当前仓库采用 Monorepo 与模块化单体，先建设可复用的平台基座，不在基座工程中提前实现采购、库存、成本、生产或财务业务应用。

## 本地开发与启动

环境要求：JDK 21 LTS、Gradle Wrapper 9.7.0、Docker Engine / Docker Compose。只有前端开发热更新或自行构建 Vue 静态资源时才需要 Node.js 26.13.1 和 pnpm 12.18.0。

首次初始化：

```bash
cp .env.example .env
```

推荐使用仓库根目录的统一启动器，不需要分别启动数据库、Server、Worker 和前端：

```bash
./ztx dev
```

一条命令会等待 PostgreSQL 健康就绪，再启动 Vert.x API Server、独立 Worker 和 Vite 热更新服务器。浏览器访问 `http://localhost:5173`；Vite 将 API 请求代理到 Vert.x（默认 `http://localhost:8080`）。

构建并验证由 Vert.x 托管静态页面的完整运行形态：

```bash
./ztx start
```

该命令不调用 Node.js、pnpm 或 Vite。它使用已经构建好的 `frontend/apps/erp-web/dist`（可通过 `ZTX_FRONTEND_DIST` 指定）打包 Server；也可以直接复用发布流水线生成的、已包含 `webroot/index.html` 的 Server 发行包。随后启动 Server 和独立 Worker，浏览器访问 `http://localhost:8080`，页面和 API 使用同一个入口。若本机尚无前端构建产物，可在有 Node.js 的构建环境执行 `pnpm --dir frontend build`，或直接使用 CI 发布的完整发行包。

其他命令：

```bash
./ztx status
./ztx stop
```

`./ztx stop` 只停止启动器记录的应用进程，保留 PostgreSQL 数据和容器。需要停止数据库时执行 `docker compose stop postgres`。启动器日志与 PID 文件位于仓库根目录 `.ztx/`；日志目录权限限制为当前用户，请勿共享日志。

### 独立启动后端

Server 和 Worker 仍可独立运行，不依赖启动器：

```bash
./gradlew :apps:server:run --args='--ztx.profile=dev'
ZTX_APP_ROLE=worker ./gradlew :apps:server:run --args='--ztx.profile=dev'
```

只启动 Server 时，API 可用；若已构建并打包前端静态资源，Server 也会提供 Web 页面。开发前端热更新时，使用 `./ztx dev` 或单独运行 Vite。

## 健康检查

- GET http://localhost:8080/health/live：只反映进程存活，不依赖数据库。
- GET http://localhost:8080/health/ready：检查 PostgreSQL 连通性；数据库不可用时返回 HTTP 503。

## 平台管理员首次初始化

开发环境首次启动会自动执行 Flyway 数据库迁移。若尚未创建平台管理员，打开 ZTX 的平台管理登录入口会自动显示首次初始化表单，只需填写用户名、密码和确认密码；创建成功后自动登录。开发环境和正式部署使用同一安全规则，不生成一次性初始化口令，也不提供默认管理员密码。

初始化 API 仅接受服务器本机回环连接。直接在服务器本机浏览器访问，或由部署人员建立 SSH 本地端口转发后，在本机浏览器打开转发地址：

```bash
ssh -L 18080:127.0.0.1:8080 <部署用户>@<服务器地址>
# 浏览器打开 http://127.0.0.1:18080/platform/login
```

从公网域名直接打开初始化表单时，页面会提示需要本机或 SSH 隧道访问；初始化 API 会在服务端拒绝非回环请求。初始化成功后接口永久关闭，重启不能重新开放。

正式部署由受控部署流程执行数据库迁移，应用启动只校验迁移状态，不自动修改生产数据库。平台管理员初期只支持一个账号。密码遗失后的服务器受控重置命令由 [Issue #24](https://github.com/ljm20088/ztx/issues/24) 跟踪，当前尚未交付；在该能力交付前，不要手工修改密码哈希、删除管理员记录或尝试重新初始化。

平台管理员首次初始化不要求邮箱或 MFA。MFA 为可选能力；以后主动启用 MFA 时，才需要在 `application.yaml` 的 `security.mfa-encryption-key` 配置至少 32 字节随机密钥；可使用 `openssl rand -base64 32` 生成。密钥配置后必须长期备份，不能随意更换。详细步骤见[《平台管理员初始化与运维操作手册》](docs/operations/平台管理员初始化与运维操作手册.md)。

## 配置约定

后端以 `application.yaml` 为唯一默认配置入口，支持 `application-{profile}.yaml`。默认不激活 Profile；指定 `--ztx.profile=dev`、系统属性 `-Dztx.profile=dev` 或环境变量 `ZTX_PROFILE=dev` 后，加载对应 Profile 文件。Profile 名称必须对应仓库内存在的配置文件，拼写错误会在启动时直接报错。加载顺序为：

1. classpath 的 `application.yaml`；
2. classpath 的 `application-{profile}.yaml`（如果指定 Profile）；
3. 外部 YAML 文件（通过 `--ztx.config=/path/to/application.yaml`、`-Dztx.config=...` 或 `ZTX_CONFIG_FILE` 指定）；
4. 明确支持的环境变量覆盖（如 `ZTX_DB_PASSWORD`、`ZTX_HTTP_PORT`）。

后加载的同名配置覆盖先加载的值；嵌套对象按键递归合并。业务模块只读取统一的 `AppConfig`，不得直接读取环境变量或自行解析 YAML。普通私有化部署可直接在外部 YAML 中配置数据库账号和密码；环境变量/Secrets 是可选的覆盖和敏感信息注入方式。Docker Compose 的 `.env` 只负责 Compose 变量插值，不是后端配置文件。真实密码和私有部署配置不得提交 Git。

默认配置位于 `backend/platform/common/src/main/resources/application.yaml`；dev、test、prod Profile 文件与其放在同一资源目录。dev Profile 内的默认密码仅用于本机开发，不得用于生产。配置加载失败时在启动阶段报错，不输出密码等敏感值。

## 工程结构

- backend/apps/server：API Server / Worker 启动入口、HTTP 健康检查与静态资源托管。
- backend/platform/common：运行角色、统一配置加载与模块注册契约。
- backend/adapters/postgres：Vert.x PostgreSQL 连接池、连通性检查和 Flyway 迁移。
- backend/modules：平台模块边界与依赖声明。
- backend/tests/bdd：JUnit 5、Testcontainers 与中文 BDD 场景注释。
- database/migrations：集中管理的数据库迁移。
- frontend：Vue Web 工作区。
- ztx：项目级开发/本地运行进程编排入口。

目录和 Gradle 子项目用于职责隔离与测试，不代表独立部署或微服务拆分。

## 生产静态资源策略

生产发布时由前端流水线构建 erp-web，将 dist 产物纳入后端发布包的 `webroot` classpath 资源目录；Vert.x 在 API 路由之后提供静态资源。用户通过同一地址访问 Web 页面和 API。生产运行只需要 JDK 和后端发布产物，不需要 Node.js、pnpm 或 Nginx。前端构建由发布流水线完成，生产应用启动不执行前端构建。

## 常用命令

- `./ztx dev`：完整本地开发环境（Vite + Server + Worker + PostgreSQL）。
- `./ztx start`：使用预构建静态资源启动 Vert.x Web/API，并启动 Worker；启动阶段不依赖 Node.js/pnpm。
- `./ztx stop`：停止启动器管理的应用进程。
- `./ztx status`：查看应用进程状态。
- `./gradlew clean test build`：运行全部后端测试并构建。
- `pnpm --dir frontend check`：前端类型检查、Lint、测试和构建。
- `docker compose down`：停止本地依赖（保留数据卷）。

### 环境变量覆盖清单

- `ZTX_PROFILE`：激活配置 Profile。
- `ZTX_CONFIG_FILE`：指定外部 YAML 配置文件。
- `ZTX_APP_ROLE`：覆盖运行角色（server / worker）。
- `ZTX_HTTP_HOST`、`ZTX_HTTP_PORT`：覆盖 HTTP 监听地址和端口。
- `ZTX_DB_HOST`、`ZTX_DB_PORT`、`ZTX_DB_NAME`、`ZTX_DB_USER`、`ZTX_DB_PASSWORD`：覆盖数据库连接参数。
- `ZTX_DB_POOL_SIZE`、`ZTX_DB_MIGRATIONS`、`ZTX_DB_MIGRATE_ON_START`：覆盖连接池和迁移设置。

未列出的环境变量不会自动注入配置，避免环境变量意外覆盖 YAML 中的设置。
