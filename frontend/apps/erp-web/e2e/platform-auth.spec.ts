import { expect, test } from '@playwright/test'

test('平台管理员可以登录控制台', async ({ page }) => {
  // Given 平台登录接口接受凭据，控制台会话查询返回已认证的平台管理员
  await page.route('**/api/v1/platform/auth/login', route => route.fulfill({
    status: 200,
    headers: { 'content-type': 'application/json', 'set-cookie': 'ztx_platform_session=e2e-session; Path=/api/v1/platform; HttpOnly; SameSite=Strict' },
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))

  // When 管理员输入账号密码并提交登录
  await page.goto('/platform/login')
  await page.getByLabel('管理员账号').fill('platform.admin')
  await page.getByLabel('密码').fill('ZtxAdmin123')
  await page.getByRole('button', { name: '安全登录' }).click()

  // Then 进入平台控制台并显示平台身份和 MFA 状态
  await expect(page.getByRole('heading', { name: '平台控制台' })).toBeVisible()
  await expect(page.getByText('platform.admin')).toBeVisible()
  await expect(page.getByText('未启用')).toBeVisible()
})

test('部署人员可使用一次性凭据创建首个平台管理员', async ({ page }) => {
  // Given 初始化接口验证一次性凭据并成功创建管理员
  await page.route('**/api/v1/platform/auth/initialize', route => route.fulfill({
    status: 201,
    contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin' }),
  }))

  // When 部署人员切换初始化模式并提交凭据
  await page.goto('/platform/login')
  await page.getByRole('button', { name: '首次部署？使用初始化凭据创建管理员' }).click()
  await page.getByLabel('一次性初始化凭据').fill('one-time-setup-credential')
  await page.getByLabel('管理员账号').fill('platform.admin')
  await page.getByLabel('密码').fill('ZtxAdmin123')
  await page.getByRole('button', { name: '创建平台管理员' }).click()

  // Then 页面提示管理员已创建并返回登录模式
  await expect(page.getByText('平台管理员已创建，请使用刚设置的账号密码登录。')).toBeVisible()
  await expect(page.getByRole('button', { name: '安全登录' })).toBeVisible()
})
