import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { ZAlert, ZButton, ZDataGrid, ZFormField, ZInput, ZTable } from '@ztx/ui'

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
    const wrapper = mount(ZInput, { props: { modelValue: 'ZTX', invalid: true }, attrs: { id: 'account-password', required: true, autocomplete: 'current-password' } })
    // When 用户修改输入内容
    await wrapper.setValue('ZTX ERP')
    // Then 组件发出新值并呈现无效状态
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['ZTX ERP'])
    expect(wrapper.attributes('aria-invalid')).toBe('true')
    expect(wrapper.attributes('id')).toBe('account-password')
    expect(wrapper.attributes('required')).toBeDefined()
    expect(wrapper.attributes('autocomplete')).toBe('current-password')
  })

  // 场景：表单字段应统一提供标签和辅助说明。
  it('表单字段关联标签并展示提示', () => {
    // Given 使用带字段 ID 的表单字段
    const wrapper = mount(ZFormField, { props: { label: '企业编码', fieldId: 'tenant-code', required: true, hint: '请输入企业编码' }, slots: { default: () => h('input', { id: 'tenant-code' }) } })
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
    const wrapper = mount(ZTable, { slots: { default: () => h('tbody', [h('tr', [h('td', '企业')])]) } })
    // When 组件完成渲染
    // Then 输出统一表格结构
    expect(wrapper.find('.ztx-table-wrap').exists()).toBe(true)
    expect(wrapper.find('.ztx-table tbody td').text()).toBe('企业')
  })
})

describe('ZTX 数据表格', () => {
  // 场景：列表搜索应由 TanStack Table 过滤行模型并同步记录数。
  it('使用全局筛选过滤数据并更新记录数', async () => {
    // Given 列表中有 Alpha 与 Beta 两条记录
    const wrapper = mount(ZDataGrid, {
      props: {
        data: [{ name: 'Alpha' }, { name: 'Beta' }],
        columns: [{ accessorKey: 'name', header: '企业名称' }],
      },
    })
    // When 用户搜索 Beta
    await wrapper.find('input[placeholder="搜索当前列表"]').setValue('Beta')
    // Then 仅显示匹配记录，计数同步为一条
    expect(wrapper.find('tbody').text()).toContain('Beta')
    expect(wrapper.find('tbody').text()).not.toContain('Alpha')
    expect(wrapper.text()).toContain('共 1 条')
  })

  // 场景：列表表格应通过 TanStack Table 提供排序、分页和行选择。
  it('使用表格模型处理排序、分页与选择计数', async () => {
    // Given 列表有两条记录且每页只显示一条
    const wrapper = mount(ZDataGrid, {
      props: {
        pageSize: 1,
        data: [{ name: 'Zeta' }, { name: 'Alpha' }],
        columns: [{ accessorKey: 'name', header: '企业名称', enableSorting: true }],
      },
    })
    // When 按名称排序并选择当前行
    await wrapper.find('thead button').trigger('click')
    await wrapper.find('tbody input[type="checkbox"]').setValue(true)
    // Then 第一条为排序后的Alpha，已选数量为一条
    expect(wrapper.find('tbody').text()).toContain('Alpha')
    expect(wrapper.text()).toContain('已选 1 条')
    // When 切换到下一页
    const nextPage = wrapper.findAll('button').find(button => button.text().includes('下一页'))
    expect(nextPage).toBeDefined()
    await nextPage!.trigger('click')
    // Then 下一页显示Zeta
    expect(wrapper.find('tbody').text()).toContain('Zeta')
  })
})
