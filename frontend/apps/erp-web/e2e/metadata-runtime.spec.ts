import { expect, test } from '@playwright/test'

test('元数据驱动列表可分页并打开动态单据表单', async ({ page }) => {
  // Given 用户已登录，系统存在已校验的采购订单元数据
  const definition = {
    doctype: 'PurchaseOrder',
    scope: 'TENANT' as const,
    table: { schema: 'pur', name: 'purchase_order' },
    fields: [
      { name: 'supplier_name', label: '供应商', type: 'string', required: true },
      { name: 'order_date', label: '订单日期', type: 'date', required: true },
      { name: 'start_time', label: '开始时间', type: 'time', required: true },
      { name: 'lines', label: '订单明细', type: 'table', childFields: [
        { name: 'item_code', label: '物料编码', type: 'string', required: true },
        { name: 'quantity', label: '数量', type: 'decimal', required: true },
      ] },
    ],
    form: { fields: ['supplier_name', 'order_date', 'start_time', 'lines'] },
    list: { columns: ['supplier_name', 'order_date'], filters: ['supplier_name'], sort: ['order_date'], search: ['supplier_name'] },
    permissions: { read: ['TENANT_ADMIN'], create: ['TENANT_ADMIN'], update: ['TENANT_ADMIN'], submit: ['TENANT_ADMIN'] },
  }
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000001', username: 'admin', role: 'TENANT_ADMIN' }),
  }))
  await page.route('**/api/v1/tenant/runtime/doctypes/PurchaseOrder', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ doctype: 'PurchaseOrder', definition }),
  }))
  await page.route('**/api/v1/tenant/query/PurchaseOrder', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ items: [{ supplier_name: '华东轴承', order_date: '2026-11-27' }], total: 21, page: 1, pageSize: 20 }),
  }))
  await page.route('**/api/v1/tenant/documents/PurchaseOrder', route => route.fulfill({
    status: 201, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000099', version: 1, supplier_name: '华东轴承', order_date: '2026-11-27', start_time: '08:30:00', lines: [], docstatus: 0 }),
  }))
  await page.route('**/api/v1/tenant/documents/PurchaseOrder/00000000-0000-0000-0000-000000000099', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000099', version: 1, supplier_name: '华东轴承', order_date: '2026-11-27', lines: [], docstatus: 0 }),
  }))
  await page.route('**/api/v1/tenant/documents/PurchaseOrder/00000000-0000-0000-0000-000000000099/actions/submit', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000099', version: 2, supplier_name: '华东轴承', order_date: '2026-11-27', lines: [], docstatus: 1 }),
  }))

  // When 用户打开元数据列表并创建采购订单
  await page.goto('/developer/lists/PurchaseOrder')
  await expect(page.getByText('华东轴承')).toBeVisible()
  await expect(page.getByText('共 21 条')).toBeVisible()
  await page.getByRole('button', { name: '新建' }).click()
  await expect(page.getByRole('heading', { name: 'PurchaseOrder' })).toBeVisible()
  await page.getByLabel('供应商').fill('华东轴承')
  await page.getByLabel('订单日期').fill('2026-11-27')
  await page.getByLabel('开始时间').fill('08:30')
  await page.getByRole('button', { name: '添加明细' }).click()
  await page.locator('tbody input').nth(0).fill('BRG-001')
  await page.locator('tbody input').nth(1).fill('2.5')
  await page.getByRole('button', { name: '保存' }).click()

  // Then 单据保存成功，且可以执行提交生命周期动作
  await expect(page.getByText('单据已保存')).toBeVisible()
  await page.getByRole('button', { name: '提交' }).click()
  await expect(page.getByText('单据已提交')).toBeVisible()
})
