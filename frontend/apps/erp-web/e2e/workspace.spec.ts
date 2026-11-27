import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  // 工作区用例预置租户会话；未登录与会话过期由 tenant-auth.spec.ts 单独验证。
  await page.route('**/api/v1/tenant/auth/me', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: 'tenant-user-1', username: 'tenant.admin', tenantId: 'tenant-1', tenantCode: 'demo', role: 'TENANT_ADMIN' }),
  }))
})

test('工作台展示指标、趋势、待办和最近业务记录区域', async ({ page }) => {
  // Given 用户进入工作台首页
  await page.goto('/workspace')

  // Then 工作台按仪表盘布局呈现 KPI、业务趋势、待办和最近业务记录
  await expect(page.getByTestId('workspace-kpi')).toHaveCount(4)
  await expect(page.getByTestId('business-trend')).toBeVisible()
  await expect(page.getByTestId('pending-items')).toBeVisible()
  await expect(page.getByTestId('recent-business')).toBeVisible()
  await expect(page.getByText('工作台示例数据仅用于界面预览')).toBeVisible()
})

test('工作区首屏与页面截图', async ({ page }, testInfo) => {
  // Given 用户打开 ZTX Web 工作区
  await page.goto('/workspace')

  // When 页面完成加载
  await expect(page.getByTestId('workspace-home')).toBeVisible()

  // Then 展示工作区标签、一级菜单和主内容区，并保存可复现截图
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toBeVisible()
  await expect(page.getByRole('complementary', { name: '一级菜单' })).toBeVisible()
  // Then 一级导航以 ERP 业务模块为主，管理功能集中收纳
  for (const module of ['采购管理', '销售管理', '库存管理', '生产管理', '财务管理', '质量管理', '报表分析', '管理中心']) {
    await expect(page.getByRole('button', { name: module, exact: true })).toBeVisible()
  }
  await expect(page.getByRole('button', { name: '平台管理', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '企业管理', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '开发中心', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '系统设置', exact: true })).toHaveCount(0)

  // Then 品牌区与左侧导航同宽对齐，顶部品牌区和标签区保持紧凑高度
  const headerLayout = await page.evaluate(() => {
    const header = document.querySelector('.workspace-header')!.getBoundingClientRect()
    const brand = document.querySelector('.workspace-header .brand-lockup')!.getBoundingClientRect()
    const sidebar = document.querySelector('.ztx-sidebar')!.getBoundingClientRect()
    return { headerHeight: header.height, brandWidth: brand.width, sidebarWidth: sidebar.width, brandBottom: brand.bottom, sidebarTop: sidebar.top }
  })
  expect(headerLayout.headerHeight).toBe(44)
  expect(headerLayout.brandWidth).toBe(headerLayout.sidebarWidth)
  expect(Math.abs(headerLayout.brandBottom - headerLayout.sidebarTop)).toBeLessThan(1)
  if (testInfo.project.name === 'chromium') await page.screenshot({ path: 'artifacts/frontend-visual-captures/workspace-desktop.png', fullPage: true })

  // And 窄屏下品牌区仍与导航区同宽对齐，收起侧栏后保留可用的内容宽度
  await page.setViewportSize({ width: 390, height: 844 })
  const mobileLayout = await page.evaluate(() => ({
    headerHeight: document.querySelector('.workspace-header')!.getBoundingClientRect().height,
    brandWidth: document.querySelector('.workspace-header .brand-lockup')!.getBoundingClientRect().width,
    sidebarWidth: document.querySelector('.ztx-sidebar')!.getBoundingClientRect().width,
  }))
  expect(mobileLayout.headerHeight).toBe(44)
  expect(mobileLayout.brandWidth).toBe(mobileLayout.sidebarWidth)
  await page.getByRole('button', { name: '折叠导航栏' }).click()
  await expect(page.getByRole('button', { name: '展开导航栏' })).toBeVisible()
  await expect.poll(async () => page.evaluate(() => Math.abs(
    document.querySelector('.workspace-header .brand-lockup')!.getBoundingClientRect().width -
    document.querySelector('.ztx-sidebar')!.getBoundingClientRect().width
  ))).toBeLessThan(1)
  await expect.poll(async () => page.getByTestId('workspace-hero-copy').evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(160)
  await page.evaluate(() => window.scrollTo(0, 0))
  if (testInfo.project.name === 'chromium') await page.screenshot({ path: 'artifacts/frontend-visual-captures/workspace-mobile.png', fullPage: true })
})

test('一级点击、二级悬停后指针可移入三级浮层', async ({ page }, testInfo) => {
  // Given 该场景验证桌面端 hover；触屏端由点击交互用例覆盖
  test.skip(testInfo.project.name === 'mobile-chrome', '触屏端不依赖 hover，使用点击交互')

  // Given 用户位于工作台，并为新接入的元数据设计器提供空定义列表
  await page.route('**/api/v1/tenant/metadata/doctypes', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ items: [] }),
  }))
  await page.goto('/workspace')

  // When 点击一级菜单“管理中心”，悬停二级菜单“低代码开发”
  await page.getByRole('button', { name: '管理中心', exact: true }).click()
  const category = page.getByRole('button', { name: '低代码开发', exact: true })
  await category.hover()

  // Then 三级功能菜单出现
  const feature = page.getByRole('menuitem', { name: /DocType 管理/ })
  await expect(feature).toBeVisible()

  // When 鼠标从二级菜单移动到右侧三级浮层
  await feature.hover()

  // Then 浮层不会因指针经过菜单间隙而关闭
  await expect(feature).toBeVisible()

  // When 点击三级功能
  await feature.click()

  // Then 对应页面进入顶部工作区标签
  await expect(page.getByRole('heading', { name: 'DocType 元数据设计器' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '工作区标签' }).getByText('DocType 管理')).toBeVisible()
})

test('二级菜单可点击展开并通过 Escape 关闭', async ({ page }) => {
  // Given 用户打开企业管理一级菜单
  await page.goto('/workspace')
  await page.getByRole('button', { name: '管理中心', exact: true }).click()

  // When 点击二级菜单“组织基础”
  await page.getByRole('button', { name: '企业与组织', exact: true }).click()

  // Then 三级功能可见
  await expect(page.getByRole('menuitem', { name: /企业信息/ })).toBeVisible()

  // When 按下 Escape
  await page.keyboard.press('Escape')

  // Then 三级浮层关闭
  await expect(page.getByRole('menu', { name: '企业与组织功能菜单' })).toBeHidden()
})

test('键盘方向键可以展开三级菜单并移动焦点', async ({ page }) => {
  // Given 用户使用键盘聚焦二级菜单
  await page.goto('/workspace')
  await page.getByRole('button', { name: '管理中心', exact: true }).click()
  const category = page.getByRole('button', { name: '低代码开发', exact: true })
  await category.focus()

  // When 按下右方向键
  await page.keyboard.press('ArrowRight')

  // Then 三级菜单打开且焦点进入首个功能
  await expect(page.getByRole('menuitem', { name: /DocType 管理/ })).toBeFocused()
})

test('触屏尺寸下可点击展开三级菜单', async ({ page }) => {
  // Given 用户使用触屏尺寸
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/workspace')

  // When 点击一级与二级菜单
  await page.getByRole('button', { name: '管理中心', exact: true }).click()
  await page.getByRole('button', { name: '低代码开发', exact: true }).click()

  // Then 三级功能可见且可点击
  await expect(page.getByRole('menuitem', { name: /表单设计器/ })).toBeVisible()
  await page.getByRole('menuitem', { name: /表单设计器/ }).click()
  await expect(page.getByTestId('placeholder-state')).toHaveText('该功能尚未开放')
})


test('左侧导航可整体折叠、展开并记住状态', async ({ page }) => {
  // Given 用户打开工作台，导航栏默认展开
  await page.goto('/workspace')
  const expandedMainWidth = await page.locator('.workspace-main').evaluate(element => element.getBoundingClientRect().width)
  const collapse = page.getByRole('button', { name: '折叠导航栏' })
  await expect(collapse).toBeVisible()
  await expect(page.locator('.inline-category-list')).toBeVisible()

  // When 用户折叠整个导航栏
  await collapse.click()

  // Then 品牌区与导航同步收缩，内容区获得更多宽度
  await expect(page.getByRole('button', { name: '展开导航栏' })).toBeVisible()
  await expect(page.locator('.inline-category-list')).toBeHidden()
  await expect(page.locator('.workspace-body')).toHaveClass(/is-nav-collapsed/)
  await expect.poll(async () => page.evaluate(() => Math.abs(
    document.querySelector('.workspace-header .brand-lockup')!.getBoundingClientRect().width -
    document.querySelector('.ztx-sidebar')!.getBoundingClientRect().width
  ))).toBeLessThan(1)
  const collapsedLayout = await page.evaluate(() => ({
    brand: document.querySelector('.workspace-header .brand-lockup')!.getBoundingClientRect().width,
    sidebar: document.querySelector('.ztx-sidebar')!.getBoundingClientRect().width,
    main: document.querySelector('.workspace-main')!.getBoundingClientRect().width,
  }))
  expect(Math.abs(collapsedLayout.brand - collapsedLayout.sidebar)).toBeLessThan(1)
  expect(collapsedLayout.main).toBeGreaterThan(expandedMainWidth)

  // When 鼠标悬停一级模块图标（触屏端改用点击）
  const management = page.getByRole('button', { name: '管理中心', exact: true })
  if (await page.evaluate(() => window.matchMedia('(pointer: coarse)').matches)) await management.click()
  else await management.hover()

  // Then 折叠模式弹出带模块标题的二级菜单，并可访问三级功能
  await expect(page.locator('.collapsed-category-popover')).toBeVisible()
  await expect(page.locator('.collapsed-menu-heading')).toHaveText('管理中心')
  const lowCode = page.getByRole('button', { name: '低代码开发', exact: true })
  if (await page.evaluate(() => window.matchMedia('(pointer: coarse)').matches)) await lowCode.click()
  else await lowCode.hover()
  await expect(page.getByRole('menuitem', { name: /DocType 管理/ })).toBeVisible()

  // When 用户重新展开导航栏并刷新页面
  await page.getByRole('button', { name: '展开导航栏' }).click()
  await expect(page.locator('.inline-category-list')).toBeVisible()
  await page.reload()

  // Then 展开状态被持久化
  await expect(page.getByRole('button', { name: '折叠导航栏' })).toBeVisible()
})



test('折叠导航下悬停菜单跟随一级图标，鼠标离开后关闭', async ({ page }, testInfo) => {
  // Given 桌面端用户已折叠导航栏
  test.skip(testInfo.project.name === 'mobile-chrome', '触屏端使用点击展开，不验证悬停行为')
  await page.goto('/workspace')
  await page.getByRole('button', { name: '折叠导航栏' }).click()

  // When 鼠标悬停管理中心图标
  const module = page.getByRole('button', { name: '管理中心', exact: true })
  await module.hover()
  const popover = page.locator('.collapsed-category-popover')

  // Then 二级菜单显示模块标题，且顶部跟随当前图标垂直定位
  await expect(popover).toBeVisible()
  await expect(popover.locator('.collapsed-menu-heading')).toHaveText('管理中心')
  const alignment = await page.evaluate(() => {
    const trigger = document.querySelector('.module-entry[aria-label="管理中心"]')!.getBoundingClientRect()
    const menu = document.querySelector('.collapsed-category-popover')!.getBoundingClientRect()
    const heading = document.querySelector('.collapsed-menu-heading')!.getBoundingClientRect()
    const firstItem = document.querySelector('.collapsed-category-popover .category-entry')!.getBoundingClientRect()
    return { triggerTop: trigger.top, menuTop: menu.top, headingBottom: heading.bottom, firstItemTop: firstItem.top, menuLeft: menu.left, triggerRight: trigger.right }
  })
  // Then 弹窗标题位于上方，第一个菜单项与触发图标对齐，鼠标右移可直接进入菜单项。
  expect(alignment.menuTop).toBeLessThan(alignment.triggerTop)
  expect(alignment.headingBottom).toBeLessThanOrEqual(alignment.firstItemTop)
  expect(Math.abs(alignment.triggerTop - alignment.firstItemTop)).toBeLessThan(2)
  expect(alignment.menuLeft).toBeGreaterThanOrEqual(alignment.triggerRight - 1)

  // When 悬停二级分类，再移动到三级功能菜单
  const category = page.getByRole('button', { name: '低代码开发', exact: true })
  await category.hover()
  const featureMenu = page.locator('.feature-flyout')
  await expect(featureMenu).toBeVisible()
  await expect(featureMenu.locator('.flyout-heading')).toHaveCount(0)
  await page.getByRole('menuitem', { name: 'DocType 管理' }).hover()

  // Then 点击事件不会反向关闭已由悬停打开的三级菜单
  await category.click()
  await expect(featureMenu).toBeVisible()

  // When 鼠标离开模块图标和二级菜单区域，移入侧栏底部空白区
  await page.locator('.rail-footer').hover()
  // Then 二级与三级菜单一起关闭，即使指针仍在侧栏范围内
  await expect(popover).toBeHidden()
  await expect(featureMenu).toBeHidden()
})

test('平台管理员工作区复用统一外壳并提供平台账户安全', async ({ page }) => {
  // Given：当前浏览器持有有效的平台管理员会话
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: 'admin-1', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.goto('/platform/admin')

  // Then：平台管理员进入独立平台工作区，账户安全内容直接可见
  await expect(page.locator('.workspace-header')).toBeVisible()
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toBeVisible()
  await expect(page.getByRole('complementary', { name: '一级菜单' })).toBeVisible()
  await expect(page.getByRole('button', { name: '平台管理', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '全屏' })).toBeVisible()
  await expect(page.getByRole('button', { name: '通知' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '平台概览' })).toBeVisible()
  await expect(page.locator('#password-settings')).toBeVisible()
  await expect(page.getByRole('button', { name: '更新密码' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '工作区标签' }).getByText('平台概览')).toBeVisible()
  await expect(page.getByText('采购管理')).toHaveCount(0)

  // Then 平台概览标题使用页面标题令牌
  const adminTypography = await page.evaluate(() => ({
    pageTitle: getComputedStyle(document.querySelector('[data-testid="platform-admin-heading"] h1')!).fontSize,
  }))
  expect(adminTypography.pageTitle).toBe('18px')
})

test('平台管理员可从独立平台工作区安全退出', async ({ page }) => {
  // Given：用户已登录平台控制面
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: 'admin-1', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/auth/logout', route => route.fulfill({ status: 204 }))
  await page.goto('/platform/admin')

  // When：打开统一账户菜单并点击退出登录
  await page.getByRole('button', { name: '用户账户菜单' }).click()
  await page.getByRole('menuitem', { name: '退出登录' }).click()

  // Then：调用注销接口后返回平台登录页
  await expect(page).toHaveURL(/\/platform\/login$/)
})


test('界面字号、三级菜单和页面标题保持精简', async ({ page }) => {
  // Given 用户进入工作区
  await page.goto('/workspace')

  // Then 常规字号为 13px，一级模块名称比二级菜单更醒目
  const typography = await page.evaluate(() => ({
    body: getComputedStyle(document.body).fontSize,
    module: getComputedStyle(document.querySelector('.module-entry')!).fontSize,
    category: getComputedStyle(document.querySelector('.category-entry')!).fontSize,
  }))
  expect(typography.body).toBe('13px')
  expect(typography.module).toBe('15px')
  expect(typography.category).toBe('14px')

  // When 展开一级和二级菜单
  await page.getByRole('button', { name: '管理中心', exact: true }).click()
  const category = page.getByRole('button', { name: '低代码开发', exact: true })
  if (await page.evaluate(() => window.matchMedia('(pointer: coarse)').matches)) await category.click()
  else await category.hover()

  // Then 三级菜单不显示标题、说明文字和四级菜单箭头
  const flyout = page.locator('.feature-flyout')
  await expect(flyout.locator('.flyout-heading')).toHaveCount(0)
  await expect(flyout.locator('.feature-entry small')).toHaveCount(0)
  await expect(flyout.locator('.feature-entry svg')).toHaveCount(0)
  await expect(flyout.getByRole('menuitem', { name: 'DocType 管理' })).toBeVisible()
  await expect(page.locator('.home-page .eyebrow')).toHaveCount(0)
})

test('折叠导航下二级与三级弹窗左右分离', async ({ page }) => {
  // Given 用户折叠导航栏并打开一个一级菜单
  await page.goto('/workspace')
  await page.getByRole('button', { name: '折叠导航栏' }).click()
  await page.getByRole('button', { name: '管理中心', exact: true }).click()

  // When 打开二级菜单“低代码开发”
  await page.getByRole('button', { name: '低代码开发', exact: true }).click()
  const feature = page.locator('.feature-flyout')
  await expect(feature).toBeVisible()

  // Then 三级弹窗位于二级弹窗右侧，两个弹窗保持独立可操作
  await expect.poll(async () => {
    const current = await page.evaluate(() => {
      const category = document.querySelector('.collapsed-category-popover')!.getBoundingClientRect()
      const flyout = document.querySelector('.feature-flyout')!.getBoundingClientRect()
      return flyout.left - category.right
    })
    return current
  }).toBeGreaterThanOrEqual(-1)
  await expect(feature.getByRole('menuitem', { name: 'DocType 管理' })).toBeVisible()
})


test('工作区内容区按统一字号令牌呈现层级', async ({ page }) => {
  // Given 用户进入工作台内容区
  await page.goto('/workspace')
  await expect(page.getByTestId('quick-panel')).toBeVisible()

  // Then 根字号令牌与正文、辅助说明、区块标题和页面标题保持一致
  const contentTypography = await page.evaluate(() => ({
    bodyToken: getComputedStyle(document.documentElement).getPropertyValue('--ztx-font-size-body').trim(),
    captionToken: getComputedStyle(document.documentElement).getPropertyValue('--ztx-font-size-caption').trim(),
    sectionToken: getComputedStyle(document.documentElement).getPropertyValue('--ztx-font-size-section-title').trim(),
    pageToken: getComputedStyle(document.documentElement).getPropertyValue('--ztx-font-size-page-title').trim(),
    quickTitle: getComputedStyle(document.querySelector('[data-testid="quick-link-title"]')!).fontSize,
    quickDescription: getComputedStyle(document.querySelector('[data-testid="quick-link-description"]')!).fontSize,
    sectionTitle: getComputedStyle(document.querySelector('[data-testid="section-title"]')!).fontSize,
    recentTitle: getComputedStyle(document.querySelector('[data-testid="recent-title"]')!).fontSize,
  }))
  expect(contentTypography).toEqual({
    bodyToken: '13px',
    captionToken: '12px',
    sectionToken: '15px',
    pageToken: '18px',
    quickTitle: '13px',
    quickDescription: '12px',
    sectionTitle: '15px',
    recentTitle: '13px',
  })
})


test('精简后的平台管理员登录页使用统一字号令牌', async ({ page }) => {
  // Given 用户打开平台管理员登录页，平台尚未初始化且请求来自本机
  await page.route('**/api/v1/platform/auth/status', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ initialized: false, canInitialize: true, mfaEnabled: false }),
  }))
  await page.goto('/platform/login')
  await expect(page.getByTestId('platform-auth-form')).toBeVisible()

  // Then 页面标题、表单标签、输入框和按钮使用统一字号
  const typography = await page.evaluate(() => ({
    title: getComputedStyle(document.querySelector('[data-testid="platform-auth-heading"] h1')!).fontSize,
    label: getComputedStyle(document.querySelector('[data-testid="platform-auth-form"] label')!).fontSize,
    input: getComputedStyle(document.querySelector('[data-testid="platform-auth-form"] input')!).fontSize,
    submit: getComputedStyle(document.querySelector('[data-testid="platform-auth-submit"]')!).fontSize,
  }))
  expect(typography).toEqual({ title: '18px', label: '13px', input: '13px', submit: '13px' })
})


test('表单未保存保护与工作区视图实例恢复', async ({ page }) => {
  // Given 用户打开已有单据，后端返回当前 Document 版本
  const documentId = '7b9f5b9e-10c8-4f10-9e42-2c2f8a1c91d2'
  await page.route('**/api/v1/tenant/runtime/doctypes/PurchaseOrder', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ doctype: 'PurchaseOrder', definition: {
      doctype: 'PurchaseOrder',
      fields: [{ name: 'code', label: '采购单号', type: 'string', required: true }],
    } }),
  }))
  await page.route(`**/api/v1/tenant/documents/PurchaseOrder/${documentId}`, route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: documentId, version: 1, docstatus: 0, code: 'PO-001' }),
  }))
  await page.goto(`/developer/documents/PurchaseOrder/${documentId}`)
  await expect(page.locator('#doc-code')).toHaveValue('PO-001')

  // When 修改单据后尝试切换到工作台，并在未保存提示中选择取消
  await page.locator('#doc-code').fill('PO-EDITED')
  const tabs = page.getByRole('navigation', { name: '工作区标签' })
  page.once('dialog', dialog => dialog.dismiss())
  await tabs.getByRole('button', { name: /工作台/ }).click()
  await expect(page).toHaveURL(new RegExp(`/developer/documents/PurchaseOrder/${documentId}$`))

  // Then 原表单保持打开且修改内容没有丢失
  await expect(page.locator('#doc-code')).toHaveValue('PO-EDITED')
  await expect(tabs.getByLabel('未保存修改')).toBeVisible()

  // When 用户再次切换并明确确认放弃修改
  page.once('dialog', dialog => dialog.accept())
  await tabs.getByRole('button', { name: /工作台/ }).click()
  await expect(page).toHaveURL(/\/workspace$/)
  await expect(tabs.getByLabel('未保存修改')).toHaveCount(0)

  // Then 用户已确认放弃修改，重新激活表单时显示数据库中的原值
  await tabs.locator('button.workspace-tab').filter({ hasText: '元数据单据' }).click()
  await expect(page.locator('#doc-code')).toHaveValue('PO-001')
})

test('报表使用独立的参数与结果视图', async ({ page }, testInfo) => {
  // Given 用户打开一个报表 Tab
  await page.goto('/reports/inventory-summary')

  // When 报表视图渲染
  const report = page.getByTestId('report-view')

  // Then 参数区和结果区独立存在，不复用 Document Form 或 List View
  await expect(report).toBeVisible()
  await expect(report.getByRole('region', { name: '报表参数' })).toBeVisible()
  await expect(report.getByRole('region', { name: '报表结果' })).toBeVisible()
  await expect(report.getByText('当前报表尚未配置数据集或查询定义。')).toBeVisible()
  if (testInfo.project.name === 'chromium') await page.screenshot({ path: 'artifacts/frontend-visual-captures/report-view-desktop.png', fullPage: true })
})
