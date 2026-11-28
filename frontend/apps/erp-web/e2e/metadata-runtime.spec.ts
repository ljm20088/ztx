import { expect, test } from '@playwright/test'

test('元数据驱动列表可分页并打开动态单据表单', async ({ page }, testInfo) => {
  let documentCreated = false
  // Given 用户已登录，系统存在已校验的采购订单元数据
  const definition = {
    doctype: 'PurchaseOrder',
    scope: 'TENANT' as const,
    table: { schema: 'pur', name: 'purchase_order' },
    fields: [
      { name: 'supplier_name', label: '供应商', type: 'string', required: true },
      { name: 'order_date', label: '订单日期', type: 'date', required: true },
      { name: 'start_time', label: '开始时间', type: 'time', required: true },
      { name: 'payload', label: '附件内容', type: 'json', loadOnDemand: true },
      { name: 'lines', label: '订单明细', type: 'table', childFields: [
        { name: 'item_code', label: '物料编码', type: 'string', required: true },
        { name: 'quantity', label: '数量', type: 'decimal', required: true },
      ] },
    ],
    form: { fields: ['supplier_name', 'order_date', 'start_time', 'payload', 'lines'] },
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
    body: JSON.stringify({ items: [{ id: '00000000-0000-0000-0000-000000000099', supplier_name: '华东轴承', order_date: '2026-11-27' }], total: documentCreated ? 22 : 21, page: 1, pageSize: 20 }),
  }))
  await page.route('**/api/v1/tenant/documents/PurchaseOrder', route => {
    documentCreated = true
    return route.fulfill({
      status: 201, contentType: 'application/json',
      body: JSON.stringify({ id: '00000000-0000-0000-0000-000000000099', version: 1, supplier_name: '华东轴承', order_date: '2026-11-27', start_time: '08:30:00', lines: [], docstatus: 0 }),
    })
  })
  await page.route('**/api/v1/tenant/documents/PurchaseOrder/00000000-0000-0000-0000-000000000099/fields/payload', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ field: 'payload', value: { content: 'large-document-body' } }),
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
  if (testInfo.project.name === 'chromium') await page.screenshot({ path: 'artifacts/frontend-visual-captures/metadata-list-desktop.png', fullPage: true })

  // Given 用户调整排序并切换到第二页，When 打开记录再返回，Then 排序和分页状态保持。
  await page.getByRole('button', { name: '排序' }).click()
  await page.getByLabel('顺序').selectOption('desc')
  await page.getByRole('button', { name: '应用排序' }).click()
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByText(/第 2 页/)).toBeVisible()

  // When 用户双击列表记录或用键盘 Enter 激活行，Then 打开独立编辑表单 Tab。
  await page.getByRole('row').filter({ hasText: '华东轴承' }).dblclick()
  await expect(page.getByLabel('供应商')).toHaveValue('华东轴承')
  // Given 表单响应未携带延迟字段，When 用户显式请求加载，Then 只读取该字段且不会被误判为未保存修改。
  await expect(page.getByRole('button', { name: '按需加载' })).toBeVisible()
  await page.getByRole('button', { name: '按需加载' }).click()
  await expect(page.locator('#doc-payload')).toHaveValue(/large-document-body/)
  await expect(page.getByLabel('未保存修改')).toHaveCount(0)
  const workspaceTabs = page.getByRole('navigation', { name: '工作区标签' })
  await workspaceTabs.locator('button.workspace-tab').filter({ hasText: '元数据列表' }).click()
  await expect(page.getByText('共 21 条')).toBeVisible()
  await expect(page.getByText(/第 2 页/)).toBeVisible()
  await expect(page.getByLabel('顺序')).toHaveValue('desc')

  await page.getByRole('button', { name: '筛选' }).click()
  await page.getByPlaceholder('搜索列表字段').fill('华东轴承')
  await page.getByPlaceholder('搜索列表字段').press('Enter')
  await page.getByRole('button', { name: '新建' }).click()
  await expect(page.getByRole('heading', { name: '基本信息' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'PurchaseOrder' })).toHaveCount(0)
  await page.getByLabel('供应商').fill('华东轴承')
  await page.getByLabel('订单日期').fill('2026-11-27')
  await page.getByLabel('开始时间').fill('08:30')
  await page.getByRole('button', { name: '添加明细' }).click()
  await page.locator('tbody input').nth(0).fill('BRG-001')
  await page.locator('tbody input').nth(1).fill('2.5')
  if (testInfo.project.name === 'chromium') await page.screenshot({ path: 'artifacts/frontend-visual-captures/document-form-desktop.png', fullPage: true })
  const formTabs = page.getByRole('navigation', { name: '工作区标签' })
  const formTabCountBeforeSave = await formTabs.locator('button.workspace-tab').filter({ hasText: '元数据单据' }).count()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(formTabs.locator('button.workspace-tab').filter({ hasText: '元数据单据' })).toHaveCount(formTabCountBeforeSave)

  // Then 单据保存成功，且可以执行提交生命周期动作
  await expect(page.getByText('单据已保存')).toBeVisible()
  await page.getByRole('button', { name: '提交' }).click()
  await expect(page.getByText('单据已提交')).toBeVisible()

  // And 从工作区标签返回列表时，筛选面板与搜索条件仍保留
  const tabs = page.getByRole('navigation', { name: '工作区标签' })
  await tabs.locator('button.workspace-tab').filter({ hasText: '元数据列表' }).click()
  await expect(page.getByPlaceholder('搜索列表字段')).toHaveValue('华东轴承')
  await expect(page.getByText('共 22 条')).toBeVisible()
})

test('无 DocType 读取权限时列表展示服务端拒绝结果且不泄露数据', async ({ page }) => {
  // Given 当前业务用户没有该 DocType 的读取权限
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: 'tenant-user-2', username: 'operator', role: 'TENANT_USER' }),
  }))
  await page.route('**/api/v1/tenant/runtime/doctypes/RestrictedRecord', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      doctype: 'RestrictedRecord',
      definition: {
        doctype: 'RestrictedRecord',
        fields: [{ name: 'secret', label: '机密字段', type: 'string', readRoles: ['TENANT_ADMIN'] }],
        list: { columns: ['secret'], filters: [], sort: [], search: [] },
      },
    }),
  }))
  await page.route('**/api/v1/tenant/query/RestrictedRecord', route => route.fulfill({
    status: 403, contentType: 'application/json',
    body: JSON.stringify({ error: { message: '当前用户没有可读取的列表字段' } }),
  }))

  // When 用户打开受限列表，Then 前端呈现服务端拒绝结果且不渲染受保护字段数据。
  await page.goto('/developer/lists/RestrictedRecord')
  await expect(page.getByRole('alert')).toContainText('当前用户没有可读取的列表字段')
  await expect(page.getByText('SENSITIVE-VALUE')).toHaveCount(0)
})
