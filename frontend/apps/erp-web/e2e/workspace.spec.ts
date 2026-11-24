import { expect, test } from '@playwright/test'

test('工作区首屏与页面截图', async ({ page }) => {
  // Given 用户打开 ZTX Web 工作区
  await page.goto('/')

  // When 页面完成加载
  await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible()

  // Then 展示工作区标签、一级菜单和主内容区，并保存可复现截图
  await expect(page.getByRole('navigation', { name: '工作区标签' })).toBeVisible()
  await expect(page.getByRole('complementary', { name: '一级菜单' })).toBeVisible()
  await page.screenshot({ path: 'artifacts/frontend-visual-captures/workspace.png', fullPage: true })
})

test('一级点击、二级悬停后指针可移入三级浮层', async ({ page }) => {
  // Given 用户位于工作台
  await page.goto('/')

  // When 点击一级菜单“开发中心”，悬停二级菜单“元数据开发”
  await page.getByRole('button', { name: '开发中心', exact: true }).click()
  const category = page.getByRole('button', { name: '元数据开发', exact: true })
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
  await page.goto('/')
  await page.getByRole('button', { name: '企业管理', exact: true }).click()

  // When 点击二级菜单“组织基础”
  await page.getByRole('button', { name: '组织基础', exact: true }).click()

  // Then 三级功能可见
  await expect(page.getByRole('menuitem', { name: /企业管理/ })).toBeVisible()

  // When 按下 Escape
  await page.keyboard.press('Escape')

  // Then 三级浮层关闭
  await expect(page.getByRole('menu', { name: '组织基础功能菜单' })).toBeHidden()
})

test('键盘方向键可以展开三级菜单并移动焦点', async ({ page }) => {
  // Given 用户使用键盘聚焦二级菜单
  await page.goto('/')
  await page.getByRole('button', { name: '开发中心', exact: true }).click()
  const category = page.getByRole('button', { name: '元数据开发', exact: true })
  await category.focus()

  // When 按下右方向键
  await page.keyboard.press('ArrowRight')

  // Then 三级菜单打开且焦点进入首个功能
  await expect(page.getByRole('menuitem', { name: /DocType 管理/ })).toBeFocused()
})

test('触屏尺寸下可点击展开三级菜单', async ({ page }) => {
  // Given 用户使用触屏尺寸
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  // When 点击一级与二级菜单
  await page.getByRole('button', { name: '开发中心', exact: true }).click()
  await page.getByRole('button', { name: '元数据开发', exact: true }).click()

  // Then 三级功能可见且可点击
  await expect(page.getByRole('menuitem', { name: /表单设计器/ })).toBeVisible()
  await page.getByRole('menuitem', { name: /表单设计器/ }).click()
  await expect(page.getByRole('heading', { name: '表单设计器' })).toBeVisible()
})
