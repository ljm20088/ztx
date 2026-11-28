import { expect, test } from '@playwright/test'

test('租户管理员可以在 DocType 设计器创建并校验元数据草稿', async ({ page }) => {
  // Given 租户管理员已登录，当前租户尚无 DocType 定义
  const definitions: Array<{doctype:string;version:number;status:string}> = []
  let savedDefinition: { fields: Array<{ loadOnDemand?: boolean; defaultValue?: unknown; type?: string; target?: string }>; form?: { fields: string[] }; list?: { columns: string[]; filters: string[]; sort: string[]; search: string[] } } | null = null
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
      const payload = request.postDataJSON() as {doctype:string;fields:Array<{loadOnDemand?:boolean;defaultValue?:unknown;type?:string;target?:string}>;form?:{fields:string[]};list?:{columns:string[];filters:string[];sort:string[];search:string[]}}
      savedDefinition = payload
      definitions.push({ doctype: payload.doctype, version: 1, status: 'DRAFT' })
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ doctype: payload.doctype, version: 1, status: 'DRAFT' }) })
      return
    }
    if (request.method() === 'GET' && url.pathname.endsWith('/PlaywrightSample')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        version: 1, status: 'DRAFT', definition: savedDefinition ? { doctype: 'PlaywrightSample', scope: 'TENANT',
          table: { schema: 'org', name: 'playwright_sample' }, fields: savedDefinition.fields,
          form: savedDefinition.form, list: savedDefinition.list,
          permissions: { read: [], create: [], update: [], delete: [] } } : null,
      }) })
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
  await page.getByLabel('字段标识').nth(0).fill('code')
  await page.getByLabel('显示名称').nth(0).fill('编码')
  await page.getByLabel('默认值').nth(0).fill('NEW')
  await page.getByLabel('按需加载').check()
  await page.getByRole('button', { name: '添加字段' }).click()
  await page.getByLabel('字段标识').nth(1).fill('supplier')
  await page.getByLabel('显示名称').nth(1).fill('供应商')
  await page.getByLabel('字段类型').nth(1).selectOption('reference')
  await page.getByLabel('引用目标').fill('Supplier')
  await page.getByLabel('表单字段与顺序').fill('supplier, code')
  await page.getByLabel('列表显示列').fill('code')
  await page.getByLabel('可筛选字段').fill('supplier')
  await page.getByLabel('可排序字段').fill('code')
  await page.getByLabel('可搜索字段').fill('code, supplier')
  await page.getByRole('button', { name: '保存草稿' }).click()
  expect(savedDefinition?.fields[0]?.loadOnDemand).toBe(true)
  expect(savedDefinition?.fields[0]?.defaultValue).toBe('NEW')
  expect(savedDefinition?.fields[1]?.type).toBe('reference')
  expect(savedDefinition?.fields[1]?.target).toBe('Supplier')
  expect(savedDefinition?.form?.fields).toEqual(['supplier', 'code'])
  expect(savedDefinition?.list?.columns).toEqual(['code'])
  expect(savedDefinition?.list?.filters).toEqual(['supplier'])
  expect(savedDefinition?.list?.sort).toEqual(['code'])
  expect(savedDefinition?.list?.search).toEqual(['code', 'supplier'])

  // Then 草稿出现在定义列表中，并可以执行校验
  await expect(page.getByText('草稿已保存')).toBeVisible()
  await page.getByRole('button', { name: /PlaywrightSample v1/ }).click()
  await expect(page.getByLabel('默认值').nth(0)).toHaveValue('NEW')
  await expect(page.getByLabel('引用目标')).toHaveValue('Supplier')
  await expect(page.getByLabel('表单字段与顺序')).toHaveValue('supplier, code')
  await expect(page.getByLabel('列表显示列')).toHaveValue('code')
  await expect(page.getByLabel('可筛选字段')).toHaveValue('supplier')
  await page.getByRole('button', { name: '校验并发布' }).click()
  await expect(page.getByText('元数据校验通过并已发布')).toBeVisible()
})
