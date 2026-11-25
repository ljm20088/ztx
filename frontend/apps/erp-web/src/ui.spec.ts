import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ZAlert, ZButton, ZFormField, ZInput, ZTable } from '@ztx/ui'

describe('ZTX UI 基础组件', () => {
  // 场景：按钮应由组件 API 统一控制外观和禁用状态。
  it('按钮呈现 ZTX 样式 API 并传递禁用状态', () => {
    // Given 使用 ZTX 自有 Button 组件
    const wrapper = mount(ZButton, { props: { variant: 'secondary', size: 'sm', disabled: true }, slots: { default: '保存' } })
    // When 组件完成渲染
    // Then 文本、尺寸、外观和禁用状态均由 ZTX API 控制
    expect(wrapper.text()).toBe('保存')
    expect(wrapper.classes()).toContain('is-secondary')
    expect(wrapper.classes()).toContain('size-sm')
    expect(wrapper.attributes('disabled')).toBeDefined()
  })

  // 场景：输入控件应双向同步值，并统一呈现无效状态。
  it('输入控件同步 v-model 并支持无效状态', async () => {
    // Given 使用 ZInput 并设置初始值
    const wrapper = mount(ZInput, { props: { modelValue: 'ZTX', invalid: true } })
    // When 用户修改输入内容
    await wrapper.setValue('ZTX ERP')
    // Then 组件发出新值并呈现无效状态
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['ZTX ERP'])
    expect(wrapper.attributes('aria-invalid')).toBe('true')
  })

  // 场景：表单字段应统一提供标签和辅助说明。
  it('表单字段关联标签并展示提示', () => {
    // Given 使用带字段 ID 的表单字段
    const wrapper = mount(ZFormField, { props: { label: '企业编码', fieldId: 'tenant-code', required: true, hint: '请输入企业编码' }, slots: { default: '<input id="tenant-code" />' } })
    // When 组件完成渲染
    // Then 标签关联控件且提示文本可见
    expect(wrapper.find('label').attributes('for')).toBe('tenant-code')
    expect(wrapper.text()).toContain('企业编码')
    expect(wrapper.text()).toContain('请输入企业编码')
  })

  // 场景：反馈组件应按语义输出状态或错误提示。
  it('反馈组件输出正确语义角色', () => {
    // Given 使用错误反馈组件
    const wrapper = mount(ZAlert, { props: { tone: 'error', role: 'alert' }, slots: { default: '保存失败' } })
    // When 组件完成渲染
    // Then 提示具有错误样式和 alert 语义
    expect(wrapper.classes()).toContain('is-error')
    expect(wrapper.attributes('role')).toBe('alert')
  })

  // 场景：表格组件应提供统一的滚动容器和表格样式。
  it('表格组件统一包裹数据表格', () => {
    // Given 使用 ZTable 包裹表格内容
    const wrapper = mount(ZTable, { slots: { default: '<tbody><tr><td>企业</td></tr></tbody>' } })
    // When 组件完成渲染
    // Then 输出统一表格结构
    expect(wrapper.find('.ztx-table-wrap').exists()).toBe(true)
    expect(wrapper.find('.ztx-table tbody td').text()).toBe('企业')
  })
})
