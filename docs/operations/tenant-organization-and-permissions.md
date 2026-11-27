# 租户组织、人事与权限管理

本能力运行在每个租户独立的 PostgreSQL 数据库内。租户管理员和业务用户使用独立身份主体、会话表和审计主体；所有 API 继续使用认证上下文绑定的租户数据库连接。

## 模型边界

- `org.company`：企业法律/经营主体。
- `org.book`：企业账套；一个企业可以有多个账套，账套不代表组织节点。
- `org.organization_unit`：企业组织树，支持分公司、事业部、部门、工厂、车间和班组。
- `hr.employee`：员工档案，不要求存在登录账号。
- `iam.user_account`：独立登录用户，不要求存在员工档案；账号有独立会话和密码。
- `hr.employment`：员工在企业、账套和组织中的任职及起止日期；员工可有多段任职，任职可选关联用户。
- `iam.job_position`：岗位可限定企业、账套和组织范围；一个任职可分配多个岗位。
- `iam.module_role`：模块角色；一个岗位可组合多个模块角色。
- `iam.role_permission`：功能授权效果、数据范围和字段权限。

## 管理员授权

- 租户管理员可维护租户内企业、账套、组织、人事和权限基础配置。
- 企业管理员使用独立用户账号登录；必须存在有效任职，并通过岗位/模块角色获得 `organization.manage` 权限。服务端按授权任职所属企业过滤查询，并在新增、修改、岗位分配和集团档案申请时再次校验企业范围。
- 集团档案管理员是受控的租户管理员标记；集团档案创建、修改、启用、审批和停用操作均在服务端校验该标记。
- 未配置管理权限的业务用户不能调用组织管理 API。

## 权限合并

对任职关联的全部岗位及模块角色执行合并：

1. 同一权限标识只要出现显式 `DENY`，最终结果就是拒绝；否则按 `ALLOW` 授权。
2. 多角色数据范围取可授予范围的并集，再裁剪到任职企业、账套和组织范围。有效权限结果同时返回 `scopeConstraints`，业务查询必须同时应用这些约束。
3. 数据范围支持 `SELF`、`UNIT`、`SUBTREE`、`BOOK`、`COMPANY` 和 `TENANT`。
4. 字段权限采用限制更严格的结果：`HIDDEN > MASKED > READ_ONLY > EDITABLE > VISIBLE`。
5. 未配置的权限不返回授权结果，业务 API 应按默认拒绝处理。

## 集团基础档案

- 集团档案管理员维护 `org.group_master_item`，并标记由集团控制的字段。
- 企业管理员通过 `org.group_master_request` 申请启用；集团档案管理员审批后创建/启用企业关联。
- 企业补充数据单独保存在 `org.company_group_master.local_data`，写入时拒绝包含集团保护字段。
- 停用集团档案前检查仍处于启用状态的企业引用；停用仅修改状态，不删除集团主数据和历史关联。
- 企业申请和集团审批分别记录用户/管理员主体，组织变更审计与业务写入在同一事务中提交。

## API

所有路径均以 `/api/v1/tenant/organization` 为前缀，并要求有效租户会话。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET/POST | `/:resource` | 查询/新增企业、账套、组织、员工、用户、任职、岗位、角色和集团档案 |
| PUT | `/:resource/:id` | 更新支持修改的档案 |
| POST | `/position-roles` | 替换岗位的模块角色集合 |
| POST | `/employment-positions` | 替换任职的岗位集合 |
| POST | `/role-permissions` | 新增或更新角色权限 |
| GET | `/employments/:id/effective-permissions` | 查询合并后的有效权限及范围约束 |
| POST | `/group-master/:id/request` | 企业申请启用集团档案 |
| POST | `/group-master-requests/:id/approve` | 集团档案管理员审批申请 |
| POST | `/group-master/:id/enable` | 集团档案管理员直接启用 |
| PUT | `/company-group-master/:id` | 更新企业本地补充字段 |
| POST | `/group-master/:id/disable` | 检查企业引用后停用集团档案 |

## 自动化验证

- PostgreSQL/Testcontainers：租户迁移、模型独立性、跨企业岗位约束、业务用户登录、企业管理员数据范围和有效权限合并。
- BDD 场景及关键测试注释采用中文 Given-When-Then。
- Playwright：企业、账套管理流程和任职权限合并展示。


> CI 验收状态以对应 Pull Request 的实际工作流结果为准；未执行的检查不得标记为通过。
