import { expect, test } from '@playwright/test'

test('租户管理员可以在 DocType 设计器创建并校验元数据草稿', async ({ page }) => {
  // Given 租户管理员已登录，当前租户尚无 DocType 定义
  const definitions: Array<{doctype:string;version:number;status:string}> = []
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'tenant.admin', role: 'TENANT_ADMIN' }),
  }))
  await page.route('**/api/v1/tenant/metadata/doctypes**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname.endsWith('/doctypes')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: definitions }) })
      return
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/doctypes')) {
      const payload = request.postDataJSON() as {doctype:string}
      definitions.push({ doctype: payload.doctype, version: 1, status: 'DRAFT' })
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ doctype: payload.doctype, version: 1, status: 'DRAFT' }) })
      return
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/validate')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ valid: true, status: 'VALIDATED', diagnostics: [] }) })
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: { message: 'Not found' } }) })
  })

  // When 管理员打开设计器并保存一个包含字段的 DocType 草稿
  await page.goto('/developer/doctypes')
  await expect(page.getByRole('heading', { name: 'DocType 元数据设计器' })).toBeVisible()
  await page.getByLabel('DocType 标识').fill('PlaywrightSample')
  await page.getByLabel('数据库 Schema').fill('org')
  await page.getByLabel('物理表名').fill('playwright_sample')
  await page.getByRole('button', { name: '添加字段' }).click()
  await page.locator('tbody input').nth(0).fill('code')
  await page.locator('tbody input').nth(1).fill('编码')
  await page.getByLabel('按需加载').check()
  await page.getByRole('button', { name: '保存草稿' }).click()
  expect(savedDefinition?.fields[0]?.loadOnDemand).toBe(true)

  // Then 草稿出现在定义列表中，并可以执行校验
  await expect(page.getByText('草稿已保存')).toBeVisible()
  await expect(page.getByRole('button', { name: /PlaywrightSample v1/ })).toBeVisible()
  await page.getByRole('button', { name: '校验' }).click()
  await expect(page.getByText('元数据校验通过')).toBeVisible()
})
