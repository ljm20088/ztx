<script setup lang="ts">
import { ZArrowUpRight, ZClock3, ZCommand, ZLayers3, ZListTodo, ZShieldCheck, ZButton } from '@ztx/ui'
import { useRouter } from 'vue-router'
const router=useRouter()
const quickLinks=[
 {title:'租户管理',description:'管理租户开通与运行状态',icon:ZShieldCheck,path:'/platform/tenants',group:'平台管理'},
 {title:'组织架构',description:'维护企业、账套与组织结构',icon:ZLayers3,path:'/enterprise/departments',group:'企业管理'},
 {title:'DocType 管理',description:'定义低代码业务对象',icon:ZCommand,path:'/developer/doctypes',group:'开发中心'},
 {title:'我的待办',description:'查看需要处理的工作项',icon:ZListTodo,path:'/workspace/todo',group:'工作台'},
]
const recentItems=[
 {title:'工作台首页',area:'个人工作台',time:'刚刚',path:'/workspace'},
 {title:'元数据设计器',area:'开发中心 / 元数据开发',time:'今天 10:42',path:'/developer/forms'},
 {title:'组织架构',area:'企业管理 / 组织基础',time:'昨天 16:18',path:'/enterprise/departments'},
]
</script>
<template>
<section class="home-page">
 <section class="welcome-strip"><div class="welcome-symbol"><ZCommand :size="22"/></div><div class="welcome-copy"><strong>让业务与平台协同工作</strong><span>从工作区进入平台管理、企业基础设置和低代码开发工具。</span></div><ZButton size="sm" variant="secondary" @click="router.push('/developer/doctypes')">进入开发中心 <ZArrowUpRight :size="14"/></ZButton></section>
 <div class="home-grid">
  <section class="panel quick-panel"><div class="panel-heading"><div><h2>常用功能</h2></div></div>
   <div class="quick-grid"><button v-for="item in quickLinks" :key="item.path" class="quick-card" @click="router.push(item.path)"><span class="quick-icon"><component :is="item.icon" :size="18"/></span><span class="quick-text"><strong>{{item.title}}</strong><small>{{item.description}}</small></span><ZArrowUpRight :size="14" class="quick-arrow"/><span class="quick-group">{{item.group}}</span></button></div>
  </section>
  <section class="panel recent-panel"><div class="panel-heading"><div><h2>最近访问</h2></div><ZClock3 :size="16" class="muted-icon"/></div>
   <div class="recent-list"><button v-for="(item,index) in recentItems" :key="item.path" class="recent-row" @click="router.push(item.path)"><span class="recent-index">0{{index+1}}</span><span class="recent-copy"><strong>{{item.title}}</strong><small>{{item.area}}</small></span><time>{{item.time}}</time></button></div><div class="recent-foot">最近访问记录仅用于展示工作区交互</div>
  </section>
 </div>
 <section class="panel foundation-panel"><div class="panel-heading"><div><h2>平台能力导航</h2></div></div>
  <div class="foundation-grid">
   <div class="foundation-item"><span class="foundation-number">01</span><div><strong>工程与工作区</strong><small>统一入口、导航、多标签与组件体系</small></div><span class="foundation-state">当前建设</span></div>
   <div class="foundation-item"><span class="foundation-number">02</span><div><strong>身份与组织</strong><small>租户、企业、账套、员工与权限</small></div><span class="foundation-state is-planned">规划中</span></div>
   <div class="foundation-item"><span class="foundation-number">03</span><div><strong>低代码运行时</strong><small>元数据、Document、动态表单与列表</small></div><span class="foundation-state is-planned">规划中</span></div>
   <div class="foundation-item"><span class="foundation-number">04</span><div><strong>扩展与平台服务</strong><small>Hook、工作流、任务、审计与部署</small></div><span class="foundation-state is-planned">规划中</span></div>
  </div>
 </section>
 <div class="home-footnote">本工作台为平台基座阶段的交互示例，不代表已交付对应业务能力。</div>
</section>
</template>