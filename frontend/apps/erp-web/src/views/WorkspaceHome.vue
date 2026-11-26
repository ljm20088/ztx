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
<section data-testid="workspace-home" class="mx-auto w-full max-w-[1440px]">
 <section class="mb-4 flex min-h-[70px] items-center gap-3 rounded-ztx-md border border-ztx-border-accent bg-gradient-to-r from-ztx-welcome-start via-ztx-welcome-middle to-ztx-welcome-end p-3.5 max-[720px]:flex-wrap max-[720px]:items-start">
  <div class="grid size-[38px] shrink-0 place-items-center rounded-ztx-md bg-ztx-primary text-ztx-on-primary"><ZCommand :size="22"/></div>
  <div class="flex min-w-0 flex-1 flex-col gap-1"><strong class="text-ztx-body font-bold">让业务与平台协同工作</strong><span class="text-ztx-caption text-ztx-muted">从工作区进入平台管理、企业基础设置和低代码开发工具。</span></div>
  <ZButton class="max-[720px]:ml-[51px]" size="sm" variant="secondary" @click="router.push('/developer/doctypes')">进入开发中心 <ZArrowUpRight :size="14"/></ZButton>
 </section>
 <div class="mb-4 grid grid-cols-[minmax(0,1.35fr)_minmax(300px,.85fr)] gap-[15px] max-[1050px]:grid-cols-1">
  <section data-testid="quick-panel" class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
   <div class="mb-[13px] flex items-center justify-between gap-3"><h2 data-testid="section-title" class="m-0 text-ztx-section-title font-bold">常用功能</h2></div>
   <div class="grid grid-cols-2 gap-2 max-[720px]:grid-cols-1">
    <button v-for="item in quickLinks" :key="item.path" class="grid min-h-[82px] grid-cols-[34px_minmax(0,1fr)_14px] grid-rows-[1fr_auto] items-center gap-x-[9px] rounded-[6px] border border-ztx-border bg-ztx-surface-raised p-2.5 text-left hover:border-ztx-border-hover hover:bg-ztx-surface-hover-warm" @click="router.push(item.path)">
     <span class="row-span-1 grid size-8 place-items-center rounded-ztx-md bg-ztx-surface-warm text-ztx-primary"><component :is="item.icon" :size="18"/></span>
     <span class="flex min-w-0 flex-col gap-1"><strong data-testid="quick-link-title" class="text-ztx-body font-semibold">{{item.title}}</strong><small data-testid="quick-link-description" class="overflow-hidden text-ellipsis whitespace-nowrap text-ztx-caption text-ztx-muted">{{item.description}}</small></span>
     <ZArrowUpRight :size="14" class="text-ztx-muted"/>
     <span class="col-start-2 col-end-4 mt-1 text-ztx-caption text-ztx-text-accent-muted">{{item.group}}</span>
    </button>
   </div>
  </section>
  <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
   <div class="mb-[13px] flex items-center justify-between gap-3"><h2 class="m-0 text-ztx-section-title font-bold">最近访问</h2><ZClock3 :size="16" class="text-ztx-muted"/></div>
   <div class="flex flex-col">
    <button v-for="(item,index) in recentItems" :key="item.path" class="flex min-h-[52px] items-center gap-2.5 border-b border-ztx-border px-0.5 py-[7px] text-left last:border-0 hover:[&_.recent-title]:text-ztx-primary" @click="router.push(item.path)">
     <span class="text-ztx-caption tabular-nums text-ztx-number-muted">0{{index+1}}</span>
     <span class="flex min-w-0 flex-1 flex-col gap-1"><strong data-testid="recent-title" class="text-ztx-body font-semibold text-ztx-text">{{item.title}}</strong><small class="text-ztx-caption text-ztx-muted">{{item.area}}</small></span>
     <time class="whitespace-nowrap text-ztx-caption text-ztx-muted">{{item.time}}</time>
    </button>
   </div>
   <div class="pt-2.5 text-ztx-caption text-ztx-muted">最近访问记录仅用于展示工作区交互</div>
  </section>
 </div>
 <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
  <div class="mb-[13px] flex items-center justify-between gap-3"><h2 class="m-0 text-ztx-section-title font-bold">平台能力导航</h2></div>
  <div class="grid grid-cols-4 gap-2 max-[1050px]:grid-cols-2 max-[720px]:grid-cols-1">
   <div class="flex min-h-[72px] items-start gap-2.5 rounded-[5px] border border-ztx-border bg-ztx-surface-raised p-[11px_10px]"><span class="text-ztx-body font-bold tabular-nums text-ztx-number-accent">01</span><div class="flex min-w-0 flex-1 flex-col gap-1"><strong data-testid="foundation-title" class="text-ztx-body font-semibold">工程与工作区</strong><small data-testid="foundation-description" class="text-ztx-caption leading-relaxed text-ztx-muted">统一入口、导航、多标签与组件体系</small></div><span class="shrink-0 rounded-[3px] bg-ztx-success-soft px-1.5 py-0.5 text-ztx-caption text-ztx-success-text">当前建设</span></div>
   <div class="flex min-h-[72px] items-start gap-2.5 rounded-[5px] border border-ztx-border bg-ztx-surface-raised p-[11px_10px]"><span class="text-ztx-body font-bold tabular-nums text-ztx-number-accent">02</span><div class="flex min-w-0 flex-1 flex-col gap-1"><strong class="text-ztx-body font-semibold">身份与组织</strong><small class="text-ztx-caption leading-relaxed text-ztx-muted">租户、企业、账套、员工与权限</small></div><span class="shrink-0 rounded-[3px] bg-ztx-status-soft px-1.5 py-0.5 text-ztx-caption text-ztx-status-muted">规划中</span></div>
   <div class="flex min-h-[72px] items-start gap-2.5 rounded-[5px] border border-ztx-border bg-ztx-surface-raised p-[11px_10px]"><span class="text-ztx-body font-bold tabular-nums text-ztx-number-accent">03</span><div class="flex min-w-0 flex-1 flex-col gap-1"><strong class="text-ztx-body font-semibold">低代码运行时</strong><small class="text-ztx-caption leading-relaxed text-ztx-muted">元数据、Document、动态表单与列表</small></div><span class="shrink-0 rounded-[3px] bg-ztx-status-soft px-1.5 py-0.5 text-ztx-caption text-ztx-status-muted">规划中</span></div>
   <div class="flex min-h-[72px] items-start gap-2.5 rounded-[5px] border border-ztx-border bg-ztx-surface-raised p-[11px_10px]"><span class="text-ztx-body font-bold tabular-nums text-ztx-number-accent">04</span><div class="flex min-w-0 flex-1 flex-col gap-1"><strong class="text-ztx-body font-semibold">扩展与平台服务</strong><small class="text-ztx-caption leading-relaxed text-ztx-muted">Hook、工作流、任务、审计与部署</small></div><span class="shrink-0 rounded-[3px] bg-ztx-status-soft px-1.5 py-0.5 text-ztx-caption text-ztx-status-muted">规划中</span></div>
  </div>
 </section>
 <div class="px-px py-2 text-ztx-caption text-ztx-muted">本工作台为平台基座阶段的交互示例，不代表已交付对应业务能力。</div>
</section>
</template>
