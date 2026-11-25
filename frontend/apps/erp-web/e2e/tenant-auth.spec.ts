import { expect, test } from '@playwright/test'

// 场景：租户用户登录后进入工作区，退出后受保护页面要求重新登录
test('租户用户登录和会话失效后返回登录页', async ({ page }) => {
  let authenticated = false
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: authenticated ? 200 : 401,
    contentType: 'application/json',
    body: JSON.stringify(authenticated
      ? { id: '00000000-0000-0000-0000-000000000007', username: 'tenant.admin', tenantId: '00000000-0000-0000-0000-000000000006', tenantCode: 'acme', role: 'TENANT_ADMIN' }
      : { error: { code: 'TENANT_AUTH_REQUIRED', message: '租户登录已失效' } }),
  }))
  await page.route('**/api/v1/tenant/auth/login', async route => {
    const body = route.request().postDataJSON()
    expect(body.tenantCode).toBe('acme')
    authenticated = true
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json', 'set-cookie': 'ztx_tenant_session=acme.e2e-session-token-1234567890123456789012345678901234567890; Path=/api/v1/tenant; HttpOnly; SameSite=Strict' },
      body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000007', username: 'tenant.admin', tenantId: '00000000-0000-0000-0000-000000000006', tenantCode: 'acme', role: 'TENANT_ADMIN' }),
    })
  })
  await page.route('**/api/v1/tenant/auth/logout', async route => {
    authenticated = false
    await route.fulfill({ status: 204, headers: { 'set-cookie': 'ztx_tenant_session=; Path=/api/v1/tenant; Max-Age=0; HttpOnly; SameSite=Strict' } })
  })

  // Given 用户尚未登录，访问租户工作区
  await page.goto('/workspace')
  await expect(page).toHaveURL(/tenant\/login/)
  await page.getByLabel('租户编码').fill('acme')
  await page.getByLabel('用户名').fill('tenant.admin')
  await page.getByLabel('密码').fill('TenantPass123')

  // When 用户提交正确的租户账号密码
  await page.getByRole('button', { name: '安全登录' }).click()

  // Then 用户进入工作台，账户菜单显示租户身份
  await expect(page).toHaveURL(/workspace/)
  await page.getByRole('button', { name: '用户账户菜单' }).click()
  await expect(page.getByText('tenant.admin')).toBeVisible()
  await expect(page.getByText('租户用户')).toBeVisible()

  // When 用户退出登录并重新访问工作区
  await page.getByRole('menuitem', { name: '退出登录' }).click()
  await page.goto('/workspace')

  // Then 会话失效后重新进入租户登录页
  await expect(page).toHaveURL(/tenant\/login/)
})
