import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from './workspace'

describe('工作区标签', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('分别维护平台和企业标签，不混合两种身份的工作区', () => {
    // Given 企业工作区默认只有不可关闭的首页标签
    const store = useWorkspaceStore()
    expect(store.tabs).toHaveLength(1)

    // When 企业用户打开功能，再切换到平台工作区
    store.openTab({ path: '/developer/forms', title: '表单设计器', closable: true })
    store.setMode('platform')

    // Then 平台工作区显示自己的固定首页标签
    expect(store.tabs).toEqual([{ path: '/platform/admin', title: '平台概览', closable: false }])

    // When 平台管理员打开租户管理，再切回企业工作区
    store.openTab({ path: '/platform/tenants', title: '租户管理', closable: true })
    store.setMode('tenant')

    // Then 企业标签仍然独立保留，平台标签不会混入企业工作区
    expect(store.tabs.map(tab => tab.path)).toEqual(['/workspace', '/developer/forms'])
    expect(store.activePath).toBe('/developer/forms')

    // When 返回平台工作区并关闭当前标签
    store.setMode('platform')
    store.closeTab('/platform/tenants')

    // Then 平台工作区回到不可关闭的概览标签
    expect(store.activePath).toBe('/platform/admin')
    expect(store.tabs[0]?.closable).toBe(false)
  })
})
