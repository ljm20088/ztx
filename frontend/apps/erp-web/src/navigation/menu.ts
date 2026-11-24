import type { Component } from 'vue'
import { ZBuilding2, ZCode2, ZHouse, ZSettings2, ZShieldCheck } from '@ztx/ui'
export interface MenuFeature { id:string; label:string; path:string; description:string }
export interface MenuCategory { id:string; label:string; features:MenuFeature[] }
export interface MenuModule { id:string; label:string; icon:Component; categories:MenuCategory[] }
export const menuModules:MenuModule[]=[
 {id:'workspace',label:'工作台',icon:ZHouse,categories:[
  {id:'personal',label:'个人工作台',features:[
   {id:'home',label:'工作台首页',path:'/workspace',description:'查看平台工作概况与常用入口'},
   {id:'todo',label:'我的待办',path:'/workspace/todo',description:'集中查看需要处理的工作项'},
   {id:'recent',label:'最近访问',path:'/workspace/recent',description:'快速返回最近使用的功能'}]},
  {id:'preferences',label:'个人设置',features:[
   {id:'profile',label:'个人资料',path:'/workspace/profile',description:'维护个人资料与显示偏好'},
   {id:'security',label:'安全设置',path:'/workspace/security',description:'管理登录与安全选项'}]}]},
 {id:'platform',label:'平台管理',icon:ZShieldCheck,categories:[
  {id:'platform-ops',label:'平台运营',features:[
   {id:'tenants',label:'租户管理',path:'/platform/tenants',description:'管理租户开通、启用与停用'},
   {id:'platform-users',label:'平台用户',path:'/platform/users',description:'管理平台控制面用户'},
   {id:'monitoring',label:'运行状态',path:'/platform/monitoring',description:'查看服务运行与健康状态'}]},
  {id:'platform-config',label:'平台配置',features:[
   {id:'global-settings',label:'全局设置',path:'/platform/settings',description:'维护平台级基础配置'},
   {id:'audit',label:'平台审计',path:'/platform/audit',description:'查询平台管理操作记录'}]}]},
 {id:'enterprise',label:'企业管理',icon:ZBuilding2,categories:[
  {id:'org',label:'组织基础',features:[
   {id:'companies',label:'企业管理',path:'/enterprise/companies',description:'维护租户内企业信息'},
   {id:'books',label:'账套管理',path:'/enterprise/books',description:'维护企业账套与启用范围'},
   {id:'departments',label:'组织架构',path:'/enterprise/departments',description:'维护部门、工厂与组织层级'}]},
  {id:'people',label:'人员与权限',features:[
   {id:'employees',label:'员工档案',path:'/enterprise/employees',description:'维护员工与任职关系'},
   {id:'positions',label:'岗位管理',path:'/enterprise/positions',description:'维护岗位与模块角色组合'},
   {id:'roles',label:'角色权限',path:'/enterprise/roles',description:'维护功能、数据和字段权限'}]}]},
 {id:'developer',label:'开发中心',icon:ZCode2,categories:[
  {id:'metadata',label:'元数据开发',features:[
   {id:'doctypes',label:'DocType 管理',path:'/developer/doctypes',description:'定义低代码业务对象'},
   {id:'forms',label:'表单设计器',path:'/developer/forms',description:'配置元数据表单布局'},
   {id:'lists',label:'列表设计器',path:'/developer/lists',description:'配置元数据列表视图'}]},
  {id:'extensions',label:'扩展治理',features:[
   {id:'hooks',label:'Hook 管理',path:'/developer/hooks',description:'管理租户受控扩展脚本'},
   {id:'releases',label:'发布记录',path:'/developer/releases',description:'查看元数据发布与版本'}]},
  {id:'tools',label:'开发工具',features:[
   {id:'api-docs',label:'API 文档',path:'/developer/api-docs',description:'查看平台 API 契约'},
   {id:'diagnostics',label:'诊断工具',path:'/developer/diagnostics',description:'辅助定位开发环境问题'}]}]},
 {id:'system',label:'系统设置',icon:ZSettings2,categories:[
  {id:'system-preferences',label:'系统偏好',features:[
   {id:'appearance',label:'外观设置',path:'/system/appearance',description:'调整主题与界面密度'},
   {id:'notifications',label:'通知偏好',path:'/system/notifications',description:'设置站内通知偏好'}]}]}
]
export function findFeature(path:string):MenuFeature|undefined {
 return menuModules.flatMap(item=>item.categories).flatMap(item=>item.features).find(item=>item.path===path)
}