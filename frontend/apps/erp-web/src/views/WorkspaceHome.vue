<script setup lang="ts">
import { ZArrowUpRight, ZClock3, ZCommand, ZLayers3, ZListTodo, ZShieldCheck, ZButton } from '@ztx/ui'
import { useRouter } from 'vue-router'

const router = useRouter()
const metrics = [
  { title: '采购订单', value: '1,280', change: '↑ 12.5%', tone: 'text-ztx-primary', points: '0,18 12,15 24,17 36,8 48,11 60,3' },
  { title: '销售订单', value: '980', change: '↑ 8.2%', tone: 'text-ztx-success-text', points: '0,17 12,12 24,14 36,6 48,9 60,2' },
  { title: '库存数量', value: '286,000', change: '↑ 6.1%', tone: 'text-ztx-primary', points: '0,16 12,13 24,15 36,7 48,10 60,4' },
  { title: '销售金额', value: '1,200 万', change: '↑ 10.8%', tone: 'text-ztx-success-text', points: '0,19 12,15 24,17 36,8 48,10 60,2' },
]
const pendingItems = [
  { title: '采购订单待审核', count: 12, tone: 'bg-ztx-warning' },
  { title: '销售订单待审核', count: 8, tone: 'bg-ztx-primary' },
  { title: '生产工单待确认', count: 6, tone: 'bg-ztx-primary' },
  { title: '库存盘点待执行', count: 3, tone: 'bg-ztx-success' },
]
const businessRecords = [
  { type: '采购订单', code: 'PO20261110001', date: '11-10', partner: '上海XX公司', amount: '128,000.00', status: '已审核', tone: 'bg-ztx-success-soft text-ztx-success-text' },
  { type: '销售订单', code: 'SO20261110001', date: '11-10', partner: '深圳XX公司', amount: '96,000.00', status: '待审核', tone: 'bg-ztx-status-soft text-ztx-status-muted' },
  { type: '入库单', code: 'IC20261110001', date: '11-10', partner: '华东仓库', amount: '64,800.00', status: '已审核', tone: 'bg-ztx-success-soft text-ztx-success-text' },
]
const quickLinks = [
  { title: '租户管理', description: '管理租户开通与运行状态', icon: ZShieldCheck, path: '/platform/tenants', group: '平台管理' },
  { title: '组织架构', description: '维护企业、账套与组织结构', icon: ZLayers3, path: '/enterprise/departments', group: '企业管理' },
  { title: 'DocType 管理', description: '定义低代码业务对象', icon: ZCommand, path: '/developer/doctypes', group: '开发中心' },
  { title: '我的待办', description: '查看需要处理的工作项', icon: ZListTodo, path: '/workspace/todo', group: '工作台' },
]
const recentItems = [
  { title: '工作台首页', area: '个人工作台', time: '刚刚', path: '/workspace' },
  { title: '元数据设计器', area: '开发中心 / 元数据开发', time: '今天 10:42', path: '/developer/forms' },
  { title: '组织架构', area: '企业管理 / 组织基础', time: '昨天 16:18', path: '/enterprise/departments' },
]
</script>

<template>
  <section data-testid="workspace-home" class="mx-auto flex w-full max-w-[1440px] flex-col gap-3 text-ztx-text">
    <div class="flex items-center gap-3 rounded-ztx-md border border-ztx-border-accent bg-ztx-welcome-middle px-3 py-2.5">
      <span class="grid size-8 shrink-0 place-items-center rounded-ztx-md bg-ztx-primary text-ztx-on-primary"><ZCommand :size="17" /></span>
      <div data-testid="workspace-hero-copy" class="min-w-0 flex-1">
        <strong class="block text-ztx-body font-bold">欢迎进入 ZTX 工作台</strong>
        <span class="text-ztx-caption text-ztx-muted">业务指标、待办事项与最近单据集中呈现。</span>
      </div>
      <span class="text-ztx-caption text-ztx-muted">示例工作区</span>
    </div>
    <div class="grid grid-cols-4 gap-3 max-[1050px]:grid-cols-2 max-[560px]:grid-cols-1">
      <article v-for="metric in metrics" :key="metric.title" data-testid="workspace-kpi" class="flex min-h-[94px] items-center gap-3 rounded-ztx-md border border-ztx-border bg-ztx-surface p-3">
        <span class="grid size-9 shrink-0 place-items-center rounded-ztx-md bg-ztx-surface-warm text-ztx-primary">
          <ZCommand :size="18" />
        </span>
        <div class="min-w-0 flex-1">
          <div class="text-ztx-caption text-ztx-muted">{{ metric.title }}</div>
          <div class="mt-1 text-[21px] font-bold leading-tight tabular-nums text-ztx-text">{{ metric.value }}</div>
          <div class="mt-1 text-ztx-caption font-semibold" :class="metric.tone">{{ metric.change }}</div>
        </div>
        <svg viewBox="0 0 60 22" class="h-7 w-[68px] shrink-0" :class="metric.tone" role="img" :aria-label="metric.title + '趋势示意'">
          <polyline :points="metric.points" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </article>
    </div>

    <div class="grid grid-cols-[minmax(0,1.45fr)_minmax(260px,.8fr)] gap-3 max-[900px]:grid-cols-1">
      <section data-testid="business-trend" class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <div class="mb-2 flex items-center justify-between gap-3">
          <h2 class="m-0 text-ztx-section-title font-bold">业务趋势</h2>
          <span class="text-ztx-caption text-ztx-muted">近 30 天 · 演示数据</span>
        </div>
        <div class="mb-2 flex items-center gap-4 text-ztx-caption text-ztx-muted">
          <span class="inline-flex items-center gap-1.5"><i class="size-2 rounded-full bg-ztx-primary" />采购金额</span>
          <span class="inline-flex items-center gap-1.5"><i class="size-2 rounded-full bg-ztx-success" />销售金额</span>
        </div>
        <svg viewBox="0 0 640 190" class="h-[190px] w-full" role="img" aria-label="采购金额与销售金额趋势示意图">
          <g stroke="var(--ztx-border)" stroke-width="1">
            <path d="M48 22H625 M48 62H625 M48 102H625 M48 142H625 M48 174H625" />
          </g>
          <g fill="var(--ztx-text-muted)" font-size="11">
            <text x="8" y="26">150万</text><text x="8" y="66">100万</text><text x="18" y="106">50万</text><text x="25" y="178">0</text>
            <text x="48" y="188">10-01</text><text x="180" y="188">10-08</text><text x="320" y="188">10-15</text><text x="455" y="188">10-22</text><text x="585" y="188">10-31</text>
          </g>
          <polyline points="48,112 95,93 140,101 188,56 235,83 282,72 330,91 377,55 425,76 472,39 520,65 570,30 625,58" fill="none" stroke="var(--ztx-primary)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          <polyline points="48,132 95,118 140,129 188,93 235,111 282,86 330,123 377,83 425,108 472,67 520,118 570,84 625,102" fill="none" stroke="var(--ztx-success)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </section>

      <section data-testid="pending-items" class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <div class="mb-2 flex items-center justify-between">
          <h2 class="m-0 text-ztx-section-title font-bold">待办事项</h2>
          <button class="text-ztx-caption text-ztx-primary" @click="router.push('/workspace/todo')">更多 <ZArrowUpRight :size="13" /></button>
        </div>
        <button v-for="item in pendingItems" :key="item.title" class="flex min-h-[49px] w-full items-center gap-2.5 border-b border-ztx-border px-1 text-left last:border-0 hover:bg-ztx-surface-hover-warm" @click="router.push('/workspace/todo')">
          <i class="size-2 shrink-0 rounded-full" :class="item.tone" />
          <span class="min-w-0 flex-1 text-ztx-body">{{ item.title }}</span>
          <strong class="text-ztx-body tabular-nums">{{ item.count }}</strong>
        </button>
        <div class="mt-3 flex items-center gap-2 rounded-ztx-sm bg-ztx-surface-raised px-2.5 py-2 text-ztx-caption text-ztx-muted">
          <ZClock3 :size="14" /> 待办数量为界面示例，尚未接入业务任务
        </div>
      </section>
    </div>

    <div class="grid grid-cols-[minmax(0,1.4fr)_minmax(240px,.75fr)] gap-3 max-[900px]:grid-cols-1">
      <section data-testid="recent-business" class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <div class="mb-2 flex items-center justify-between">
          <h2 class="m-0 text-ztx-section-title font-bold">最近业务记录</h2>
          <span class="text-ztx-caption text-ztx-muted">最近 3 条 · 演示数据</span>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full min-w-[580px] border-collapse text-left text-ztx-caption">
            <thead><tr class="border-b border-ztx-border text-ztx-muted">
              <th class="px-2 py-2 font-medium">单据类型</th><th class="px-2 py-2 font-medium">单据编号</th><th class="px-2 py-2 font-medium">日期</th><th class="px-2 py-2 font-medium">客户/供应商</th><th class="px-2 py-2 text-right font-medium">金额</th><th class="px-2 py-2 font-medium">状态</th>
            </tr></thead>
            <tbody>
              <tr v-for="record in businessRecords" :key="record.code" class="border-b border-ztx-border last:border-0">
                <td class="px-2 py-2.5 text-ztx-text">{{ record.type }}</td><td class="px-2 py-2.5 font-medium tabular-nums">{{ record.code }}</td><td class="px-2 py-2.5">{{ record.date }}</td><td class="px-2 py-2.5">{{ record.partner }}</td><td class="px-2 py-2.5 text-right tabular-nums">{{ record.amount }}</td>
                <td class="px-2 py-2.5"><span class="rounded px-1.5 py-0.5" :class="record.tone">{{ record.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <aside class="flex min-h-[180px] flex-col justify-between overflow-hidden rounded-ztx-md border border-ztx-border bg-gradient-to-br from-ztx-primary to-ztx-primary-hover p-4 text-ztx-on-primary">
        <div class="flex items-center justify-between"><span class="text-ztx-caption opacity-85">ZTX ERP</span><ZArrowUpRight :size="17" /></div>
        <div><strong class="block text-[19px] font-bold">让协同更高效</strong><p class="mt-1 text-ztx-caption leading-relaxed opacity-85">让业务、数据与组织在同一工作区协同运行。</p></div>
        <ZButton size="sm" variant="secondary" class="w-fit" @click="router.push('/developer/doctypes')">了解更多 <ZArrowUpRight :size="13" /></ZButton>
      </aside>
    </div>

    <div class="grid grid-cols-[minmax(0,1.2fr)_minmax(280px,.8fr)] gap-3 max-[900px]:grid-cols-1">
      <section data-testid="quick-panel" class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <div class="mb-3 flex items-center justify-between"><h2 data-testid="section-title" class="m-0 text-ztx-section-title font-bold">常用功能</h2></div>
        <div class="grid grid-cols-2 gap-2 max-[720px]:grid-cols-1">
          <button v-for="item in quickLinks" :key="item.path" class="grid min-h-[72px] grid-cols-[34px_minmax(0,1fr)_14px] grid-rows-[1fr_auto] items-center gap-x-2.5 rounded-[6px] border border-ztx-border bg-ztx-surface-raised p-2.5 text-left hover:border-ztx-border-hover hover:bg-ztx-surface-hover-warm" @click="router.push(item.path)">
            <span class="row-span-1 grid size-8 place-items-center rounded-ztx-md bg-ztx-surface-warm text-ztx-primary"><component :is="item.icon" :size="18" /></span>
            <span class="flex min-w-0 flex-col gap-1"><strong data-testid="quick-link-title" class="text-ztx-body font-semibold">{{ item.title }}</strong><small data-testid="quick-link-description" class="overflow-hidden text-ellipsis whitespace-nowrap text-ztx-caption text-ztx-muted">{{ item.description }}</small></span>
            <ZArrowUpRight :size="14" class="text-ztx-muted" />
            <span class="col-start-2 col-end-4 mt-1 text-ztx-caption text-ztx-text-accent-muted">{{ item.group }}</span>
          </button>
        </div>
      </section>
      <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <div class="mb-2 flex items-center justify-between"><h2 class="m-0 text-ztx-section-title font-bold">最近访问</h2><ZClock3 :size="16" class="text-ztx-muted" /></div>
        <div class="flex flex-col">
          <button v-for="(item, index) in recentItems" :key="item.path" class="flex min-h-[48px] items-center gap-2.5 border-b border-ztx-border px-0.5 py-1.5 text-left last:border-0 hover:[&_.recent-title]:text-ztx-primary" @click="router.push(item.path)">
            <span class="text-ztx-caption tabular-nums text-ztx-number-muted">0{{ index + 1 }}</span>
            <span class="flex min-w-0 flex-1 flex-col gap-1"><strong data-testid="recent-title" class="text-ztx-body font-semibold text-ztx-text">{{ item.title }}</strong><small class="text-ztx-caption text-ztx-muted">{{ item.area }}</small></span>
            <time class="whitespace-nowrap text-ztx-caption text-ztx-muted">{{ item.time }}</time>
          </button>
        </div>
      </section>
    </div>
    <div class="px-px py-1 text-ztx-caption text-ztx-muted">工作台示例数据仅用于界面预览，接入业务模块后由查询服务提供真实指标、待办和业务记录。</div>
  </section>
</template>
