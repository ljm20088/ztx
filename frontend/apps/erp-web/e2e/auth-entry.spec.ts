import { expect, test } from '@playwright/test'

// 场景：未登录用户访问系统时，只能看到独立的企业登录页面
test('企业登录页不显示工作区框架并提供平台管理入口', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveURL(/tenant\/login/)
  await expect(page.getByRole('heading', { name: '企业登录' })).toBeVisible()
  await expect(page.getByLabel('企业编码')).toBeVisible()
  await expect(page.getByLabel('用户名')).toBeVisible()
  await expect(page.getByLabel('密码')).toBeVisible()
  await expect(page.getByRole('link', { name: '平台管理登录' })).toHaveAttribute('href', '/platform/login')

  await expect(page.getByRole('navigation', { name: '工作区标签' })).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: '一级菜单' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '用户账户菜单' })).toHaveCount(0)
  await expect(page.locator('.workspace-footer')).toHaveCount(0)
})

// 场景：平台管理员从企业登录页进入独立平台登录页，且可返回企业登录
test('平台管理登录页独立显示并提供企业登录返回入口', async ({ page }) => {
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ initialized: true }),
  }))
  await page.goto('/tenant/login')
  await page.getByRole('link', { name: '平台管理登录' }).click()

  await expect(page).toHaveURL(/platform\/login/)
  await expect(page.getByRole('heading', { name: '平台管理员登录' })).toBeVisible()
  await expect(page.getByRole('link', { name: '返回企业登录' })).toHaveAttribute('href', '/tenant/login')
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: '一级菜单' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '用户账户菜单' })).toHaveCount(0)
})

// 场景：未登录访问平台管理受保护页面时，必须进入平台管理登录而非企业登录
test('未登录访问平台控制台跳转平台管理登录', async ({ page }) => {
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({ error: { code: 'PLATFORM_AUTH_REQUIRED', message: '平台登录已失效' } }),
  }))
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ initialized: true }),
  }))
  await page.goto('/platform/admin')

  await expect(page).toHaveURL(/platform\/login/)
  await expect(page.getByRole('heading', { name: '平台管理员登录' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toHaveCount(0)
})
