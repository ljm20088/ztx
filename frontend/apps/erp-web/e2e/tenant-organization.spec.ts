import { expect, test } from '@playwright/test'

// 场景：租户管理员分别维护企业与账套，两个模型通过企业 ID 关联
test('企业管理员可以创建企业并为企业建立账套', async ({ page }) => {
  // Given 租户管理员已登录，企业列表和账套列表为空
  const companies: Array<Record<string, unknown>> = []
  const books: Array<Record<string, unknown>> = []
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'tenant.admin', role: 'TENANT_ADMIN' }),
  }))
  await page.route('**/api/v1/tenant/organization/**', async route => {
    const url = new URL(route.request().url())
    const resource = url.pathname.split('/').at(-1)
    if (route.request().method() === 'GET') {
      const items = resource === 'companies' ? companies : resource === 'books' ? books : []
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items }) })
      return
    }
    const payload = route.request().postDataJSON() as Record<string, unknown>
    if (resource === 'companies') companies.push({ id: 'company-1', code: payload.code, name: payload.name, status: 'ACTIVE' })
    if (resource === 'books') books.push({ id: 'book-1', company_name: 'Acme 制造', code: payload.code, name: payload.name, currency: payload.currency, status: 'ACTIVE' })
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: resource === 'companies' ? 'company-1' : 'book-1' }) })
  })

  // When 租户管理员新增企业
  await page.goto('/enterprise/companies')
  await expect(page.getByRole('heading', { name: '企业信息' })).toBeVisible()
  await page.getByLabel('企业编码').fill('acme')
  await page.getByLabel('企业名称').fill('Acme 制造')
  await page.getByRole('button', { name: '保存' }).click()

  // Then 企业出现在独立企业列表中
  await expect(page.getByText('Acme 制造')).toBeVisible()

  // When 管理员进入账套管理并为企业创建账套
  await page.goto('/enterprise/books')
  await page.getByLabel('所属企业 ID').fill('company-1')
  await page.getByLabel('账套编码').fill('MAIN')
  await page.getByLabel('账套名称').fill('主账套')
  await page.getByLabel('本位币').fill('CNY')
  await page.getByRole('button', { name: '保存' }).click()

  // Then 账套作为独立模型保存，并保留企业关联
  await expect(page.getByText('主账套')).toBeVisible()
  await expect(page.getByText('Acme 制造')).toBeVisible()
})

// 场景：多个岗位的授权合并时显式拒绝优先，并展示任职范围裁剪后的数据权限
test('任职管理页面可以查看岗位权限合并结果', async ({ page }) => {
  // Given 租户管理员已登录，任职拥有多个岗位
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'tenant.admin', role: 'TENANT_ADMIN' }),
  }))
  await page.route('**/api/v1/tenant/organization/employments', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ items: [] }),
  }))
  await page.route('**/api/v1/tenant/organization/employments/*/effective-permissions', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      employmentId: 'employment-1',
      permissions: {
        'purchase.order.approve': { allowed: false, effect: 'DENY', dataScope: 'SUBTREE', fieldPermissions: { amount: 'READ_ONLY', supplier: 'MASKED' } },
      },
    }),
  }))

  // When 管理员查询任职的最终权限
  await page.goto('/enterprise/employments')
  await page.getByLabel('任职 ID').fill('employment-1')
  await page.getByRole('button', { name: '查看合并权限' }).click()

  // Then 页面明确展示拒绝优先和字段级权限
  await expect(page.getByText(/"effect": "DENY"/)).toBeVisible()
  await expect(page.getByText(/"supplier": "MASKED"/)).toBeVisible()
})
