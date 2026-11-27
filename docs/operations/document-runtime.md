# Document Runtime 与动态表单

## 运行时接口

- GET /api/v1/tenant/runtime/doctypes/{doctype}：读取已校验的租户级 DocType 元数据，并检查 read 权限。
- POST /api/v1/tenant/documents/{doctype}：创建草稿。
- GET /api/v1/tenant/documents/{doctype}/{id}：读取单据。
- PUT /api/v1/tenant/documents/{doctype}/{id}：更新草稿。
- POST /api/v1/tenant/documents/{doctype}/{id}/actions/submit：提交。
- POST /api/v1/tenant/documents/{doctype}/{id}/actions/cancel：撤销已提交单据。
- POST /api/v1/tenant/documents/{doctype}/{id}/actions/void：作废草稿。

所有接口只读取当前租户数据库中的 VALIDATED 定义。角色权限在服务端检查；字段 readRoles 控制返回字段，writeRoles/readOnly 控制可写字段。company_id、book_id、organization_unit_id（若定义在 DocType 中）按认证主体的有效任职范围约束。

## 物理表约定

Document 当前运行时映射到元数据声明的租户业务表。物理表必须提供 UUID 主键 id、整数状态列 docstatus、created_at 和 updated_at；created_at 应有数据库默认值。状态编码为 0 草稿、1 已提交、2 已撤销、3 已作废。普通字段按元数据字段名映射到物理列；json/table 类型以 JSONB 保存，table 子字段通过 childFields 描述并由统一 Codec 转换。

保存、更新和生命周期动作在同一 PostgreSQL 事务中执行；对应审计记录写入 audit.document_event。审计失败会导致业务事务回滚。更新和生命周期动作使用行锁，避免并发状态迁移。

## 类型转换

Codec 支持 UUID、LocalDate、LocalDateTime、BigDecimal、Long、Boolean、JSON 对象/数组及嵌套 table 明细。金额/小数使用 BigDecimal，输入应使用十进制字符串避免 JavaScript 浮点精度损失。字段缺失、类型错误和未知字段会返回稳定的字段错误。

## 当前实现边界

当前切片覆盖纯元数据 Document 的通用 CRUD、草稿/提交/撤销/作废、嵌套 JSON 明细、统一权限/事务/审计和动态表单。可插拔 Kotlin 业务文件与 Kotlin 专用实现注册机制尚未纳入此切片；复杂业务扩展模式需要后续实现和单独验收。
