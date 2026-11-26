import { expect, test } from '@playwright/test'

test('平台管理员可以登录独立平台工作区', async ({ page }) => {
  // Given 平台已经初始化，登录接口返回有效的平台会话
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ initialized: true }),
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

  // Then 登录后进入平台控制台
  await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
  await expect(page.getByText('platform.admin')).toBeVisible()
  await expect(page.getByText('未启用')).toBeVisible()
})

test('首次启动自动显示初始化表单并创建管理员后直接登录', async ({ page }) => {
  // Given 平台尚未初始化，当前请求来自服务器本机或 SSH 本地端口转发
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ initialized: false, canInitialize: true }),
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

  // Then 无需重新登录，直接进入平台控制台
  await expect(page.getByRole('heading', { name: '平台控制台' })).toBeVisible()
  await expect(page.getByText('platform.admin')).toBeVisible()
})
