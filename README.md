# ZTX

ZTX 是面向制造业的元数据驱动低代码 ERP 平台。当前仓库采用 Monorepo 与模块化单体，先建设可复用的平台基座，不在基座工程中提前实现采购、库存、成本、生产或财务业务应用。

## 本地开发

环境要求：JDK 21 LTS、Gradle Wrapper 9.7.0、Docker Engine / Docker Compose。前端开发另需 Node.js 26.13.1 和 pnpm 12.18.0。

首次初始化：

    cp .env.example .env
    docker compose up -d postgres
    ./gradlew clean test build

启动 API Server（默认加载 application.yml）：

    ./gradlew :apps:server:run

指定环境 Profile：

    ./gradlew :apps:server:run --args='--ztx.profile=dev'

启动 Worker 角色：

    ZTX_APP_ROLE=worker ./gradlew :apps:server:run --args='--ztx.profile=dev'

健康检查：
- GET http://localhost:8080/health/live：只反映进程存活，不依赖数据库。
- GET http://localhost:8080/health/ready：检查 PostgreSQL 连通性；数据库不可用时返回 HTTP 503。

执行数据库迁移：

    ZTX_DB_MIGRATE_ON_START=true ./gradlew :apps:server:run --args='--ztx.profile=dev'

## 配置约定

后端以 `application.yml` 为唯一默认配置入口，支持 `application-{profile}.yml`。默认不激活 Profile；指定 `--ztx.profile=dev`、系统属性 `-Dztx.profile=dev` 或环境变量 `ZTX_PROFILE=dev` 后，加载对应 Profile 文件。加载顺序为：

1. classpath 的 `application.yml`；
2. classpath 的 `application-{profile}.yml`（如果指定 Profile）；
3. 外部 YAML 文件（通过 `--ztx.config=/path/to/application.yml`、`-Dztx.config=...` 或 `ZTX_CONFIG_FILE` 指定）；
4. 明确支持的环境变量覆盖（如 `ZTX_DB_PASSWORD`、`ZTX_HTTP_PORT`）。

后加载的同名配置覆盖先加载的值；嵌套对象按键递归合并。业务模块只读取统一的 `AppConfig`，不得直接读取环境变量或自行解析 YAML。普通私有化部署可直接在外部 YAML 中配置数据库账号和密码；环境变量/Secrets 是可选的覆盖和敏感信息注入方式。Docker Compose 的 `.env` 只负责 Compose 变量插值，不是后端配置文件。真实密码和私有部署配置不得提交 Git。

默认配置位于 `backend/platform/common/src/main/resources/application.yml`；dev、test、prod Profile 文件与其放在同一资源目录。配置加载失败时在启动阶段报错，不输出密码等敏感值。

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
