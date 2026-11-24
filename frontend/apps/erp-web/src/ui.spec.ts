import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ZButton } from '@ztx/ui'
describe('ZTX UI 基础组件',()=>{
 it('按钮呈现 ZTX 样式 API 并传递禁用状态',()=>{
  // Given 使用 ZTX 自有 Button 组件
  const wrapper=mount(ZButton,{props:{variant:'secondary',size:'sm',disabled:true},slots:{default:'保存'}})
  // When 组件完成渲染
  // Then 文本、尺寸、外观和禁用状态均由 ZTX API 控制
  expect(wrapper.text()).toBe('保存')
  expect(wrapper.classes()).toContain('is-secondary')
  expect(wrapper.classes()).toContain('size-sm')
  expect(wrapper.attributes('disabled')).toBeDefined()
 })
})