# 租户组织、人事与权限管理

本能力运行在每个租户独立的 PostgreSQL 数据库内，由租户认证守卫提供可信的 `TenantPrincipal`。客户端不能提交数据库路由信息；API 只使用认证上下文中的租户数据库连接。

## 模型边界

- `org.company`：企业法律/经营主体。
- `org.book`：企业账套；一个企业可以有多个账套，账套不代表组织节点。
- `org.organization_unit`：企业组织树，支持分公司、事业部、部门、工厂、车间和班组。
- `hr.employee`：员工档案，不要求存在登录账号。
- `iam.user_account`：独立用户账号，不要求存在员工档案。
- `hr.employment`：员工在企业/组织中的任职及起止日期；员工可有多段任职，任职可选关联用户。
- `iam.job_position`：真实岗位；一个任职可分配多个岗位。
- `iam.module_role`：模块角色；一个岗位可组合多个模块角色。
- `iam.role_permission`：功能授权效果、数据范围和字段权限。

## 权限合并

对任职关联的全部岗位及模块角色执行合并：

1. 同一权限标识只要出现显式 `DENY`，最终结果就是拒绝；否则按 `ALLOW` 授权。
2. 多角色数据范围取可授予范围的并集，再裁剪到任职企业/组织范围。任职绑定组织节点时，最大范围为该节点子树；未绑定组织节点时最大为企业。
3. 字段权限采用限制更严格的结果：`HIDDEN > MASKED > READ_ONLY > EDITABLE > VISIBLE`。
4. 未配置的权限不返回授权结果，业务 API 应按默认拒绝处理。

## 集团基础档案

- 集团档案管理员维护 `org.group_master_item`，并标记由集团控制的字段。
- 企业通过 `org.group_master_request` 申请启用；集团档案管理员审批后创建/启用企业关联。
- 企业补充数据单独保存在 `org.company_group_master.local_data`，写入时拒绝包含集团保护字段。
- 停用集团档案前检查仍处于启用状态的企业引用；停用仅修改状态，不删除集团主数据和历史关联。
- 租户首位管理员初始化时授予集团档案管理员标记；后续管理员授权由受控管理流程扩展。

## API

所有路径均以 `/api/v1/tenant/organization` 为前缀，并要求有效租户会话。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET/POST | `/:resource` | 查询/新增企业、账套、组织、员工、用户、任职、岗位、角色和集团档案 |
| PUT | `/:resource/:id` | 更新支持修改的档案 |
| POST | `/position-roles` | 替换岗位的模块角色集合 |
| POST | `/employment-positions` | 替换任职的岗位集合 |
| POST | `/role-permissions` | 新增或更新角色权限 |
| GET | `/employments/:id/effective-permissions` | 查询合并后的有效权限 |
| POST | `/group-master/:id/request` | 企业申请启用集团档案 |
| POST | `/group-master-requests/:id/approve` | 集团档案管理员审批申请 |
| POST | `/group-master/:id/enable` | 集团档案管理员直接启用 |
| PUT | `/company-group-master/:id` | 更新企业本地补充字段 |
| POST | `/group-master/:id/disable` | 检查企业引用后停用集团档案 |

所有成功写入与 `audit.organization_event` 审计记录在同一事务内提交。数据库唯一约束、外键和触发器负责阻止重复编码、无效关联及跨企业岗位授权。
