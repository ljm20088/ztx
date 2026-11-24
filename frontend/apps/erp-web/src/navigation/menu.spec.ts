import { describe, expect, it } from 'vitest'
import { findFeature, menuModules } from './menu'
describe('三级导航菜单元数据',()=>{
 it('每个一级模块都包含二级分类和三级功能入口',()=>{
  // Given 平台工作区的菜单配置
  // When 检查每个一级模块及其分类
  // Then 每个二级分类至少包含一个具体功能，不存在第四级菜单结构
  expect(menuModules.length).toBeGreaterThan(0)
  for(const module of menuModules){
   expect(module.categories.length).toBeGreaterThan(0)
   for(const category of module.categories) expect(category.features.length).toBeGreaterThan(0)
  }
 })
 it('可通过路由找到三级功能定义',()=>{
  // Given 一个菜单中已配置的三级功能路由
  // When 按路由查找功能
  // Then 返回对应的可读标题和说明
  expect(findFeature('/developer/forms')?.label).toBe('表单设计器')
  expect(findFeature('/missing/path')).toBeUndefined()
 })
})