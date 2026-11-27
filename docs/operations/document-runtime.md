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

当前切片覆盖纯元数据 Document 的通用 CRUD、草稿/提交/撤销/作废、嵌套 JSON 明细、统一权限/事务/审计、动态表单，以及通过服务端可信代码注册的 Kotlin 业务扩展。独立关系型明细表映射不属于本 Issue 的交付范围；当前明细按 JSONB 保存。

## Kotlin 业务扩展

Document Runtime 支持三种执行模式：

- **纯元数据**：DocType 不声明 `extension`，由通用运行时处理。
- **元数据 + 单 Kotlin 业务文件**：在业务模块中提供一个实现 `DocumentBehavior` 的 Kotlin 类；通常只覆盖需要的 `beforeCreate`、`beforeUpdate` 或 `beforeAction` 等 Hook。
- **Kotlin 专用实现**：同样实现 `DocumentBehavior`，但可以覆盖完整生命周期 Hook，承载该 DocType 的专用业务规则。

元数据通过 `extension.mode` 和 `extension.implementation` 指定模式及实现类全名。实现必须由服务端构建并部署到 classpath，并通过 Java `ServiceLoader` 注册（资源文件：`META-INF/services/com.ztx.modules.document.DocumentBehavior`，每行一个实现类全名）。服务启动时构建注册表；运行时只接受 DocType、模式和实现类名三者完全匹配的实现。

**安全边界：** 不在运行时编译、加载或执行租户提交的 Kotlin 源码；Kotlin 扩展属于可信部署代码，必须经过代码审查和正常发布。扩展 Hook 接收当前事务的 `SqlConnection`，与单据写入、状态迁移和审计共用同一事务。Hook 抛出异常或返回失败 Future 时，整个事务回滚。


### Kotlin 扩展示例

业务模块中的单个 Kotlin 文件可以直接实现 Hook，不需要为常规单据机械拆分多层类：

```kotlin
class PurchaseOrderBehavior : DocumentBehavior {
    override val doctype = "PurchaseOrder"
    override val mode = DocumentExtensionMode.METADATA_WITH_KOTLIN_FILE

    override fun beforeAction(
        context: DocumentBehaviorContext,
        before: JsonObject,
        action: String
    ): Future<Void> {
        if (action != "submit") return Future.succeededFuture()
        // 在当前事务中执行必要的业务校验；失败时阻止提交并回滚。
        return context.connection.preparedQuery(
            "SELECT 1 WHERE EXISTS (SELECT 1 FROM pur.supplier WHERE id = $1)"
        ).execute(Tuple.of(before.getValue("supplier_id")))
            .compose { rows ->
                if (rows.size() == 1) Future.succeededFuture()
                else Future.failedFuture("供应商不存在或不可用")
            }
    }
}
```

将实现类全名加入 `META-INF/services/com.ztx.modules.document.DocumentBehavior`，并在 DocType 元数据中声明：

```json
{
  "extension": {
    "mode": "METADATA_WITH_KOTLIN_FILE",
    "implementation": "com.example.purchase.PurchaseOrderBehavior"
  }
}
```

上例通过 `beforeAction` 在提交动作前校验供应商；Hook 失败会使状态迁移和审计记录一起回滚。
