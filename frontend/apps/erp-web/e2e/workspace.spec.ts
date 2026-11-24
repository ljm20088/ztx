import { expect, test } from '@playwright/test'

test('工作区首屏与页面截图', async ({ page }) => {
  // Given 用户打开 ZTX Web 工作区
  await page.goto('/workspace')

  // When 页面完成加载
  await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible()

  // Then 展示工作区标签、一级菜单和主内容区，并保存可复现截图
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toBeVisible()
  await expect(page.getByRole('complementary', { name: '一级菜单' })).toBeVisible()

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

  // And 窄屏下品牌区仍与导航区同宽对齐，顶部高度保持一致
  await page.setViewportSize({ width: 390, height: 844 })
  const mobileLayout = await page.evaluate(() => ({
    headerHeight: document.querySelector('.workspace-header')!.getBoundingClientRect().height,
    brandWidth: document.querySelector('.workspace-header .brand-lockup')!.getBoundingClientRect().width,
    sidebarWidth: document.querySelector('.ztx-sidebar')!.getBoundingClientRect().width,
  }))
  expect(mobileLayout.headerHeight).toBe(44)
  expect(mobileLayout.brandWidth).toBe(mobileLayout.sidebarWidth)
  await page.screenshot({ path: 'artifacts/frontend-visual-captures/workspace.png', fullPage: true })
})

test('一级点击、二级悬停后指针可移入三级浮层', async ({ page }, testInfo) => {
  // Given 该场景验证桌面端 hover；触屏端由点击交互用例覆盖
  test.skip(testInfo.project.name === 'mobile-chrome', '触屏端不依赖 hover，使用点击交互')

  // Given 用户位于工作台
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
  await expect(page.getByRole('heading', { name: 'DocType 管理' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '工作区标签' }).getByText('DocType 管理')).toBeVisible()
})

test('二级菜单可点击展开并通过 Escape 关闭', async ({ page }) => {
  // Given 用户打开企业管理一级菜单
  await page.goto('/workspace')
  await page.getByRole('button', { name: '企业管理', exact: true }).click()

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
  await expect(page.getByRole('heading', { name: '表单设计器' })).toBeVisible()
})


test('左侧导航可整体折叠、展开并记住状态', async ({ page }) => {
  // Given 用户打开工作台，导航栏默认展开
  await page.goto('/workspace')
  const collapse = page.getByRole('button', { name: '折叠导航栏' })
  await expect(collapse).toBeVisible()
  await expect(page.locator('.inline-category-list')).toBeVisible()

  // When 用户折叠整个导航栏
  await collapse.click()

  // Then 左侧只保留图标入口，二级菜单收起，工作区获得更多宽度
  await expect(page.getByRole('button', { name: '展开导航栏' })).toBeVisible()
  await expect(page.locator('.inline-category-list')).toBeHidden()
  await expect(page.locator('.workspace-body')).toHaveClass(/is-nav-collapsed/)

  // When 用户点击一个一级菜单图标
  await page.getByRole('button', { name: '管理中心', exact: true }).click()

  // Then 折叠模式仍可打开二级菜单并访问三级功能
  await expect(page.locator('.collapsed-category-popover')).toBeVisible()
  await page.getByRole('button', { name: '低代码开发', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: /DocType 管理/ })).toBeVisible()

  // When 用户重新展开导航栏并刷新页面
  await page.getByRole('button', { name: '展开导航栏' }).click()
  await expect(page.locator('.inline-category-list')).toBeVisible()
  await page.reload()

  // Then 展开状态被持久化
  await expect(page.getByRole('button', { name: '折叠导航栏' })).toBeVisible()
})


test('平台管理员可从右上角账户菜单进入控制台和账户安全', async ({ page }) => {
  // Given：当前浏览器持有有效的平台管理员会话
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: 'admin-1', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.goto('/workspace')

  // When：打开右上角用户菜单
  await page.getByRole('button', { name: '用户账户菜单' }).click()

  // Then：菜单显示当前身份、平台控制台和账户安全入口
  await expect(page.getByText('platform.admin')).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '平台控制台' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '账户与安全（修改密码）' })).toBeVisible()

  // When：进入账户安全
  await page.getByRole('menuitem', { name: '账户与安全（修改密码）' }).click()

  // Then：跳转到密码设置所在的控制台区域
  await expect(page).toHaveURL(/\/platform\/admin#password-settings$/)
  await expect(page.locator('#password-settings')).toBeVisible()
})

test('平台管理员可从右上角用户菜单安全退出', async ({ page }) => {
  // Given：用户已登录平台控制面
  await page.route('**/api/v1/platform/auth/me', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: 'admin-1', username: 'platform.admin', mfaEnabled: false }),
  }))
  await page.route('**/api/v1/platform/auth/logout', route => route.fulfill({ status: 204 }))
  await page.goto('/workspace')

  // When：打开用户菜单并退出登录
  await page.getByRole('button', { name: '用户账户菜单' }).click()
  await page.getByRole('menuitem', { name: '退出登录' }).click()

  // Then：调用注销接口后返回平台登录页
  await expect(page).toHaveURL(/\/platform\/login$/)
})
