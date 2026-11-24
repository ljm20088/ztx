# ZTX Frontend

ZTX Web 前端工作区，采用 pnpm Workspace + Vue 3 + TypeScript + Vite。

## 环境要求

- Node.js 26.13.1
- pnpm 12.18.0

## 启动

```bash
corepack enable
pnpm install
pnpm dev
```

默认地址：`http://localhost:5173`。

## 质量检查

```bash
pnpm typecheck
pnpm test
pnpm build
pnpm test:e2e
pnpm lint
```

Playwright 首次运行前安装浏览器：

```bash
pnpm --filter @ztx/erp-web exec playwright install chromium
```

E2E 截图输出到 `apps/erp-web/artifacts/frontend-visual-captures/`，仅保存在本地，不纳入 Git。

## Workspace 包

- `apps/erp-web`：工作区应用、路由和导航。
- `packages/design-tokens`：语义化 CSS Variables。
- `packages/ui`：ZTX 自主基础组件 API（Button、IconButton、Icon、DropdownMenu、Tabs、Tooltip、Stack）。
- `packages/business`：ERP 业务组件边界，后续渐进建设。
- `packages/metadata-renderer`：受控元数据渲染边界。
- `packages/api-client`：统一 API 请求出口。
- `packages/test-utils`：测试辅助出口。

业务页面通过 ZTX 自有组件 API 使用基础控件；Reka UI 和 Lucide 位于基础组件适配层。shadcn-vue 不作为运行时依赖。
