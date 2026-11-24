import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from './workspace'
describe('工作区标签',()=>{
 beforeEach(()=>setActivePinia(createPinia()))
 it('新增标签、切换标签并关闭当前标签',()=>{
  // Given 工作区仅有不可关闭的首页标签
  const store=useWorkspaceStore()
  expect(store.tabs).toHaveLength(1)
  // When 打开一个功能并再打开另一个功能
  store.openTab({path:'/developer/forms',title:'表单设计器',closable:true})
  store.openTab({path:'/platform/tenants',title:'租户管理',closable:true})
  // Then 两个功能都进入标签栏，当前标签为最后打开项
  expect(store.tabs).toHaveLength(3)
  expect(store.activePath).toBe('/platform/tenants')
  // When 关闭当前标签
  store.closeTab('/platform/tenants')
  // Then 返回相邻的上一个标签，首页标签始终保留
  expect(store.activePath).toBe('/developer/forms')
  expect(store.tabs[0]?.closable).toBe(false)
 })
})