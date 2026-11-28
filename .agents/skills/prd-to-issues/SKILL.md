---
name: prd-to-issues
description: 将产品需求文档（PRD）拆分为粒度合理、依赖清晰、可独立实施和验收的 GitHub Issues。采用垂直切片和 Tracer Bullet 方法，适用于功能规划、项目阶段拆分和开发任务分解。
---

# PRD 拆分为 GitHub Issues

以 PRD 为唯一需求依据，采用垂直切片（Vertical Slicing）和 Tracer Bullet 方法，将需求转化为可执行任务。

## 工作流程

### 1. 获取 PRD

- 请用户提供 PRD 对应的 GitHub Issue 编号或 URL。

- 如果当前上下文没有 PRD 内容，使用 `gh issue view <number>` 获取完整内容，包括评论。

- 不要关闭或修改原始 PRD Issue。

### 2. 检查需求与代码库

- 检查需求完整性、业务规则、验收标准和技术约束。
- 必要时探索代码库，了解现有架构、实现和开发规范。
- 识别已经实现的功能，避免重复拆分。
- 发现影响实施的重大歧义时，先向用户确认。

### 3. 拆分垂直切片

采用 Tracer Bullet（示踪式开发）方法，将 PRD 拆分为独立的垂直功能切片，而不是按照数据库、后端、前端等技术层次横向拆分。

拆分原则：

- 每个 Issue 有明确的交付目标和完成条件。
- 尽可能贯穿所需的数据库、API、UI 和测试。
- 粒度适中，避免任务过大或过度细碎。
- 明确范围和不包含的内容。
- 必要的基础设施、迁移、安全、性能和测试建设可以独立成任务。
- 保留 PRD 中 User Story、Acceptance Criteria 和 BDD Scenario 的追溯关系。
- 不重复编写 PRD 已经定义的业务需求。

### 4. 分类与依赖

**HITL / AFK**

- **HITL**：需要人工决策、架构评审、安全批准或人工验收。
- **AFK**：需求明确，可以由 AI 独立实施和验证。

**依赖关系**

- 只建立真实的前置依赖。
- 可以并行的任务不应互相阻塞。
- 依赖关系必须构成无环有向图（DAG）。
- 基础任务优先，按照依赖顺序安排实施。
- 必要时创建独立 QA Issue，包含所有需要人工验证的项目及详细验收计划，并依赖所有相关功能切片，不将所有任务无条件串行化。

### 5. 用户审核

以编号列表展示拆分结果，每个 Issue 包含：

- **标题**：任务名称。
- **类型**：HITL / AFK。
- **Blocked by**：前置依赖。
- **关联需求**：PRD 章节及 User Story。
- **交付范围**：完成什么、不包含什么。
- **验收标准**：明确、可验证的条件。
- **测试策略**：测试类型和验证方式。

与用户确认任务粒度、依赖关系及分类。根据反馈调整，直到用户明确批准。

**未经用户批准，不创建 GitHub Issues。**

### 6. 创建 GitHub Issues

批准后，使用 `gh issue create` 创建任务。

按照依赖顺序创建，先创建前置任务，以便引用真实 Issue 编号。

## Issue 模板

### Parent PRD
#<prd-issue-number>

### What to build
描述本次完整交付能力，引用 PRD 对应章节，不重复复制完整需求。

### Scope
- 本次包含的内容。
- 明确不包含的内容。

### Acceptance Criteria
- [ ] 验收条件 1
- [ ] 验收条件 2

### Test Strategy
说明适用的 BDD、TDD、单元、集成、API 或 E2E 测试。

### Definition of Done
- [ ] 功能实现完成。
- [ ] 相关测试通过。
- [ ] 必要文档已更新。
- [ ] 验收标准全部满足。

### Blocked by
- Blocked by #<issue-number>

无依赖时填写：None - can start immediately

### User Stories Addressed
- User Story 1
- User Story 3

### BDD Scenarios
引用 PRD 中相关场景，必要时补充任务级测试场景，不改变原始业务规则。

## 7. 核验创建结果

- 确认预期 Issue 均创建成功。
- 检查标题、内容、标签和依赖关系。
- 确认依赖图无循环。
- 检查需求追溯关系是否完整。
- 确认 QA Issue（如有）及其验收范围。
- 确认原始 PRD 未被修改或关闭。
- 输出 Issue 编号、标题、类型及依赖关系。

## 执行约束

- 不擅自修改或关闭父 PRD。
- 不在用户批准前创建 Issues。
- 不重复拆分已经实现的功能。
- 不制造不必要的技术任务或依赖。
- 不把多个独立业务能力塞入同一个 Issue。
- 所有 Issue 必须有明确的交付目标和可验证的验收标准。