# 元数据定义管理

## 范围

当前实现提供租户级 DocType 元数据草稿管理、字段与视图配置、结构校验、版本复制、受控 Schema Migration、发布激活和变更历史。

平台级元数据属于控制面范围，不存放在租户数据库中。租户 API 固定使用 `scope=TENANT`，请求体不能把定义提升为平台级；租户管理员也不能读取或修改平台范围的定义。平台级元数据的编辑入口由平台控制面单独提供，不由租户管理 API 暴露。

## API

所有接口要求有效租户会话。租户管理员可管理定义；其他用户必须具有 `metadata.manage` 功能权限。租户上下文由认证守卫注入，客户端不能提交租户 ID 或数据库路由。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/tenant/metadata/doctypes` | 查询租户 DocType 定义 |
| POST | `/api/v1/tenant/metadata/doctypes` | 创建版本 1 草稿 |
| GET | `/api/v1/tenant/metadata/doctypes/:doctype` | 查询最新版本 |
| PUT | `/api/v1/tenant/metadata/doctypes/:doctype` | 更新最新草稿 |
| POST | `/api/v1/tenant/metadata/doctypes/:doctype/validate` | 校验并发布最新草稿 |
| POST | `/api/v1/tenant/metadata/doctypes/:doctype/copy` | 从最新版本复制新草稿 |
| GET | `/api/v1/tenant/metadata/doctypes/:doctype/versions/:version` | 查询指定版本 |
| GET | `/api/v1/tenant/metadata/doctypes/:doctype/history` | 查询变更历史 |

## 定义示例

```json
{
  "doctype": "SampleItem",
  "scope": "TENANT",
  "table": { "schema": "org", "name": "sample_item" },
  "fields": [
    { "name": "code", "label": "编码", "type": "string", "required": true, "unique": true, "defaultValue": "NEW" },
    { "name": "supplier_id", "label": "供应商", "type": "reference", "target": "Supplier" },
    { "name": "status", "label": "状态", "type": "select", "options": ["OPEN", "CLOSED"] },
    { "name": "lines", "label": "明细", "type": "table", "childFields": [
      { "name": "item_code", "label": "物料编码", "type": "string" },
      { "name": "quantity", "label": "数量", "type": "decimal" }
    ]}
  ],
  "form": { "fields": ["code", "supplier_id", "status", "lines"] },
  "list": {
    "columns": ["code", "supplier_id", "status"],
    "filters": ["supplier_id", "status"],
    "sort": ["code"],
    "search": ["code"]
  },
  "permissions": {
    "read": ["reader"],
    "update": ["manager"]
  }
}
```

支持字段类型：`string`、`text`、`uuid`、`date`、`datetime`、`time`、`decimal`、`integer`、`boolean`、`reference`、`select`、`money`、`json`、`table`。字段支持默认值、必填、只读、唯一性、按需加载及读写角色配置；引用字段必须声明已存在的目标 DocType；下拉字段必须声明选项；明细字段通过 `childFields` 描述，不允许无限嵌套明细表。

## 设计器配置

DocType 设计器可配置：

- 字段标识、显示名称、类型、默认值、必填、只读、唯一性和按需加载。
- 引用字段的目标 DocType；Select 字段的选项。
- 表单字段及顺序。
- 列表显示列、筛选字段、排序字段和搜索字段。

多个布局字段使用英文逗号分隔，按输入顺序保存。留空时设计器默认使用当前定义的全部字段。保存草稿后重新打开定义，会回读字段属性和布局配置。

## 校验与版本状态

- 标识符使用服务端白名单规则；不允许客户端提供 SQL 片段或任意数据库标识符。
- 检查字段重复、字段类型、默认值、引用目标、引用循环、表单/列表字段引用和权限动作。
- 拒绝脚本、事件处理属性和危险 HTML 内容；元数据不执行脚本。
- 创建或编辑后状态为 `DRAFT`；校验失败时保留草稿并返回字段路径、错误码和说明。
- 校验通过后，服务端先基于元数据生成受控 Schema Migration 计划，并在当前认证主体对应的租户数据库中执行 DDL。
- 只有 Schema Migration 成功，元数据版本才更新为 `VALIDATED` 并记录 `PUBLISH` 历史；迁移或激活失败时不提前激活定义。
- 已发布版本不可原地修改；复制会生成下一个版本的 `DRAFT`，旧版本保持不变。
- 对已发布版本重复执行校验是幂等操作，不重复创建发布历史。

## Schema Migration 与数据存储

运行期 DDL 由 `SchemaMigrationService` 从服务端校验后的元数据生成，不接受客户端 SQL。目标数据库取自认证后的 `principal.database`，Schema、表和字段标识符由服务端校验，受保护 Schema 不允许租户映射。

普通动态 DocType 使用 PostgreSQL 物理表和原生列，不使用 EAV。JSON 与明细字段使用 JSONB；其余字段映射为 PostgreSQL 原生类型。迁移采用 Expand-only 策略，不自动删除列或缩窄类型。Flyway 与运行期迁移共享 PostgreSQL advisory lock 和 `meta.schema_migration` 治理记录，但保留 `FLYWAY` / `RUNTIME` 来源及各自入口。详细规则见 [Schema Migration 治理](schema-migrations.md)。

租户数据库的 `meta.document_definition` 保存定义版本；`meta.document_definition_history` 保存定义快照、操作者、诊断和时间。JSONB 仅用于保存元数据定义本身及明确声明为 JSON/明细的业务字段，不把普通业务字段统一存入 JSONB/EAV。
