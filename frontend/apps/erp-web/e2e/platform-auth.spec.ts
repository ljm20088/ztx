import { expect, test } from '@playwright/test'

test('平台管理员可以登录独立平台工作区', async ({ page }) => {
  // Given 平台已经初始化，登录接口返回有效的平台会话
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ initialized: true, canInitialize: false, mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/auth/login', route => route.fulfill({
    status: 200,
    headers: { 'content-type': 'application/json', 'set-cookie': 'ztx_platform_session=e2e-session; Path=/api/v1/platform; HttpOnly; SameSite=Strict' },
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))

  // When 管理员从系统入口访问并输入账号密码
  await page.goto('/platform/login')
  await expect(page).toHaveURL(/platform\/login/)
  await page.getByLabel('用户名').fill('platform.admin')
  await page.getByLabel('密码', { exact: true }).fill('ZtxAdmin123')
  await page.getByRole('button', { name: '安全登录' }).click()

  // Then 登录后进入平台概览
  await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
  await expect(page.getByText('platform.admin').first().first()).toBeVisible()
  await expect(page.getByText('未启用')).toBeVisible()
})

test('首次启动自动显示初始化表单并创建管理员后直接登录', async ({ page }) => {
  // Given 平台尚未初始化，当前请求来自服务器本机或 SSH 本地端口转发
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ initialized: false, canInitialize: true, mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/auth/initialize', route => route.fulfill({
    status: 201,
    headers: { 'content-type': 'application/json', 'set-cookie': 'ztx_platform_session=initialized-session; Path=/api/v1/platform; HttpOnly; SameSite=Strict' },
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))

  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))

  // When 首次访问系统入口
  await page.goto('/platform/login')
  await expect(page.getByRole('heading', { name: '创建平台管理员' })).toBeVisible()
  await page.getByLabel('用户名').fill('platform.admin')
  await page.getByLabel('密码', { exact: true }).fill('ZtxAdmin123')
  await page.getByLabel('确认密码').fill('ZtxAdmin123')
  await page.getByRole('button', { name: '创建平台管理员' }).click()

  // Then 无需重新登录，直接进入平台概览
  await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
  await expect(page.getByText('platform.admin').first()).toBeVisible()
})

test('平台管理员使用统一工作台外壳和独立平台菜单', async ({ page }) => {
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/health/live', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ status: 'UP', service: 'ztx-server', version: 'test', startedAt: '2026-11-27T00:00:00Z' }),
  }))
  await page.route('**/health/ready', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ status: 'READY', components: { database: 'UP' } }),
  }))
  await page.route('**/api/v1/platform/operations/status', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ workers: [{ workerId: 'worker-e2e-1', startedAt: '2026-11-27T00:00:00Z', lastSeenAt: '2026-11-27T00:00:00Z', status: 'UP' }] }),
  }))

  // When 平台管理员进入平台管理工作区
  await page.goto('/platform/operations')

  // Then 页面使用统一工作台外壳，但只显示平台管理菜单
  await expect(page.locator('.workspace-header')).toBeVisible()
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toBeVisible()
  await expect(page.getByRole('complementary', { name: '一级菜单' })).toBeVisible()
  await expect(page.getByRole('button', { name: '全屏' })).toBeVisible()
  await expect(page.getByRole('button', { name: '通知' })).toBeVisible()
  await expect(page.getByRole('button', { name: '平台管理', exact: true })).toBeVisible()
  await expect(page.getByText('运维管理', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('采购管理')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '运维管理' })).toBeVisible()
  await expect(page.getByText('API Server')).toBeVisible()
  await expect(page.getByText('PostgreSQL')).toBeVisible()
  await expect(page.getByText('1 个运行中')).toBeVisible()
  await expect(page.getByText('运行中', { exact: true })).toBeVisible()
})

test('平台管理员可以查看平台安全审计记录', async ({ page }) => {
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/audit', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([
      { id: 'event-1', eventType: 'LOGIN', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-2', eventType: 'ADMIN_INITIALIZED', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-3', eventType: 'MFA_LOGIN', outcome: 'FAILURE', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-4', eventType: 'LOGOUT', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-5', eventType: 'PASSWORD_CHANGED', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-6', eventType: 'MFA_ENROLLMENT_STARTED', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-7', eventType: 'MFA_ENABLED', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-8', eventType: 'ADMIN_PASSWORD_RESET', outcome: 'SUCCESS', sourceIp: '127.0.0.1', adminUsername: 'platform.admin', occurredAt: '2026-11-27T00:00:00Z' },
      { id: 'event-9', eventType: 'TENANT_PROVISIONED', outcome: 'SUCCESS', sourceIp: null, adminUsername: 'platform.admin', tenantCode: 'demo', tenantName: '演示企业', occurredAt: '2026-11-27T00:00:00Z' },
    ]),
  }))

  // When 平台管理员打开安全审计
  await page.goto('/platform/audit')

  // Then 可以查看平台安全事件及其操作结果
  await expect(page.getByRole('heading', { name: '安全审计' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '登录', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '平台管理员初始化', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '动态验证码登录', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '退出登录', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '修改密码', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '开始绑定多重身份验证', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '启用多重身份验证', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '重置平台管理员密码', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '租户开通成功', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '演示企业（demo）', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'LOGIN', exact: true })).toHaveCount(0)
  await expect(page.getByRole('cell', { name: '成功', exact: true })).toHaveCount(8)
  await expect(page.getByRole('cell', { name: '失败', exact: true })).toHaveCount(1)
})
