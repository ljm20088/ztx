import { expect, test } from '@playwright/test'

// 场景：平台管理员创建租户、查看隔离数据库信息并停用租户
test('平台管理员可以创建、查看和停用租户', async ({ page }) => {
  // Given 平台管理员已登录，且控制面中尚无租户
  let tenants: Array<Record<string, unknown>> = []
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/tenants', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: tenants }) })
      return
    }
    tenants = [{
      id: '00000000-0000-0000-0000-000000000006', code: 'acme', name: 'Acme 制造',
      status: 'ACTIVE', databaseName: 'ztx_t_acme_12345678', lastError: null,
    }]
    await route.fulfill({
      status: 201, contentType: 'application/json',
      body: JSON.stringify({ tenant: tenants[0], setupToken: 'tenant-one-time-token' }),
    })
  })
  await page.route('**/api/v1/platform/tenants/*/disable', route => {
    tenants = tenants.map(tenant => ({ ...tenant, status: 'INACTIVE' }))
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(tenants[0]) })
  })

  // When 平台管理员打开租户管理并开通租户
  await page.goto('/platform/tenants')
  await expect(page.getByRole('heading', { name: '租户管理' })).toBeVisible()
  await page.getByLabel('租户编码').fill('acme')
  await page.getByLabel('租户名称').fill('Acme 制造')
  await page.getByRole('button', { name: '创建并开通' }).click()

  // Then 页面显示新租户、独立数据库标识和一次性初始化凭据
  await expect(page.getByText('acme', { exact: true })).toBeVisible()
  await expect(page.getByText('ztx_t_acme_12345678')).toBeVisible()
  await expect(page.getByText('tenant-one-time-token')).toBeVisible()

  // When 平台管理员停用租户
  await page.getByRole('button', { name: '停用' }).click()

  // Then 租户状态更新为已停用
  await expect(page.getByText('已停用')).toBeVisible()
})
