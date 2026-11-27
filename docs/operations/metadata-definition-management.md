# 元数据定义管理

## 范围

当前实现提供租户级 DocType 元数据草稿管理、结构校验、版本复制和变更历史。它是 Issue #9 的元数据管理切片，不代表元数据发布或 Document/Query Runtime 已完成。

平台级元数据属于控制面范围，不存放在租户数据库中。租户 API 固定使用 `scope=TENANT`，请求体不能把定义提升为平台级；租户管理员也不能读取或修改平台范围的定义。平台级元数据的编辑入口由平台控制面单独提供，不由租户管理 API 暴露。

## API

所有接口要求有效租户会话。租户管理员可管理定义；其他用户必须具有 `metadata.manage` 功能权限。租户上下文由认证守卫注入，客户端不能提交租户 ID 或数据库路由。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/tenant/metadata/doctypes` | 查询租户 DocType 定义 |
| POST | `/api/v1/tenant/metadata/doctypes` | 创建版本 1 草稿 |
| GET | `/api/v1/tenant/metadata/doctypes/:doctype` | 查询最新版本 |
| PUT | `/api/v1/tenant/metadata/doctypes/:doctype` | 更新最新草稿 |
| POST | `/api/v1/tenant/metadata/doctypes/:doctype/validate` | 校验最新版本 |
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
    { "name": "code", "label": "编码", "type": "string", "required": true, "unique": true },
    { "name": "name", "label": "名称", "type": "string" }
  ],
  "form": { "fields": ["code", "name"] },
  "list": {
    "columns": ["code", "name"],
    "filters": ["code"],
    "sort": ["name"],
    "search": ["code", "name"]
  },
  "permissions": {
    "read": ["reader"],
    "update": ["manager"]
  }
}
```

支持字段类型：`string`、`text`、`uuid`、`date`、`datetime`、`decimal`、`integer`、`boolean`、`reference`、`select`、`money`、`json`。

## 校验与状态

- 标识符使用白名单规则，不允许客户端提供 SQL 片段或任意数据库标识符。
- 检查字段重复、字段类型、默认值、引用目标、引用循环、表单/列表字段引用和权限动作。
- 拒绝脚本、事件处理属性和危险 HTML 内容；元数据不执行脚本。
- 草稿创建或编辑后状态为 `DRAFT`；校验通过后为 `VALIDATED`；校验失败时保留草稿并返回字段路径、错误码和说明。
- 复制会生成下一个版本的 `DRAFT`，旧版本不覆盖。
- 每次创建、编辑、复制和校验均写入变更历史，包含定义快照、操作者和时间。
- 本阶段没有发布、运行时激活、DDL 自动迁移或回滚能力；这些由后续 Issue 实现。

## 数据存储

租户数据库的 `meta.document_definition` 保存定义版本；`meta.document_definition_history` 保存不可覆盖的变更快照。租户数据天然位于独立租户数据库，查询再固定限定 `scope='TENANT'`。此实现不会将标准业务表改成 JSONB/EAV；JSONB 仅用于保存可编辑的元数据定义本身。
