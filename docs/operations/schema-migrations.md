# ZTX Schema Migration 治理

## 两种来源，一个治理记录

ZTX 保留两种 Schema 变更入口：

- **开发期 Migration**：开发人员提交 Flyway SQL，随代码版本部署；Flyway 原生历史仍是执行依据。
- **客户运行期 Migration**：租户管理员在元数据设计器定义 DocType/字段，由平台根据已校验元数据生成 DDL 计划并执行；不接受客户端 SQL 或任意标识符。

两种来源都镜像到数据库内的 `meta.schema_migration`，统一记录来源、版本、目标表、校验和、执行状态、计划、诊断、操作者和时间。Flyway 的 `flyway_schema_history` 保留为原生历史，不被替代。

## 运行期动态 Schema 规则

`SchemaMigrationService` 是元数据发布流程的 Schema 执行器。发布协调由元数据发布流程调用，本服务不负责 RuntimeSnapshot 多实例发布协调。

- Schema、表和字段标识符必须符合小写蛇形命名规则；受保护 Schema（platform、iam、meta、audit 等）不可作为租户业务表目标。
- DDL 只由服务端从字段元数据生成；字段类型映射到 PostgreSQL 原生列，不使用 EAV。
- 新表包含系统列：UUID 主键 `id`、`docstatus`、乐观锁 `version`、`created_at`、`updated_at`。
- 字段类型映射：文本→TEXT、UUID/引用→UUID、日期→DATE、日期时间→TIMESTAMP、时间→TIME、金额/小数→NUMERIC、整数→BIGINT、布尔→BOOLEAN、JSON/明细→JSONB。
- 新增字段使用 Expand-only 策略；不自动删除列、不自动缩窄或转换既有列类型。
- 对已有数据的表新增必填列，必须提供可安全转换的默认值；否则迁移拒绝并要求显式数据回填方案。
- 将可空列改为必填时，先回填默认值或确认没有空值；存在无法处理的空值时拒绝迁移。
- 元数据声明 unique/indexed 时创建对应索引；唯一索引遇到重复数据时迁移失败，不会绕过约束。
- 每个目标表通过 PostgreSQL advisory transaction lock 串行化；同一 DocType 版本和 Schema checksum 重复执行幂等。
- DDL 和成功状态在同一事务提交；失败回滚 DDL，并在独立事务记录 FAILED 状态及诊断。失败版本可使用相同 checksum 重试。
- 同一版本 checksum 不一致必须提升版本号，不允许覆盖已记录的迁移内容。

## 版本发布集成约定

元数据发布流程（#13）应按以下顺序调用：

1. 校验 DocType 定义、权限、字段类型、物理映射和风险。
2. 调用 `SchemaMigrationService.plan(...)` 展示服务端生成的 DDL 计划及诊断。
3. 获得发布授权后调用 `migrate(database, doctype, version, definition, actorId)`。
4. 只有迁移状态为 SUCCEEDED，才允许激活对应元数据版本并继续 RuntimeSnapshot 发布。
5. 失败时保留旧元数据版本可用，记录 FAILED 状态；不得激活与物理 Schema 不一致的新定义。

本服务不实现 #13 的多实例 RuntimeSnapshot 协调，也不把租户运行期 DDL 写入源码 Flyway migration 目录。
