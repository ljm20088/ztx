# 受控查询与元数据列表

租户列表通过 POST /api/v1/tenant/query/{doctype} 查询。请求体只接受 JSON Filter AST、分页、排序和搜索，不接受 SQL、表名、列名或表达式。

请求示例：

    {
      "page": 1,
      "pageSize": 20,
      "search": "轴承",
      "filter": {
        "op": "and",
        "conditions": [
          { "field": "supplier_name", "operator": "contains", "value": "华东" },
          { "field": "status", "operator": "eq", "value": "OPEN" }
        ]
      },
      "sort": [{ "field": "created_at", "direction": "desc" }]
    }

允许操作符：eq、ne、gt、gte、lt、lte、contains、startsWith、in、isNull。逻辑节点只支持 and / or，最多 8 层；单个逻辑节点最多 50 个条件。分页大小限制为 1–200。

字段、筛选、排序和搜索列由 DocType 的 list 元数据白名单决定。字段可通过 readRoles 限定可读角色。若定义包含 company_id 字段，查询强制使用认证主体的 companyIds 作为数据范围；企业、账套和组织字段均由认证主体的有效任职范围约束；任一已声明范围为空时返回空集。列表查询只读取已校验（VALIDATED）的租户级定义。

所有筛选值、搜索值和分页值均使用 PostgreSQL 参数绑定。物理表与列标识符只来自已校验元数据，并再次通过标识符格式校验。
