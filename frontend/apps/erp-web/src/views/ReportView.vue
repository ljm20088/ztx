<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ZAlert, ZButton } from '@ztx/ui'

const route = useRoute()
const reportId = computed(() => String(route.params.reportId ?? ''))
const title = computed(() => reportId.value.includes('inventory') ? '库存报表' : reportId.value)
const warehouse = ref('')
const material = ref('')
const fromDate = ref('2026-10-01')
const toDate = ref('2026-10-31')
const message = ref('当前报表尚未配置数据集或查询定义。配置完成后将在此显示真实指标和明细。')
const metrics = ['库存总量', '库存金额', '周转天数', '安全库存率']
</script>

<template>
  <section data-testid="report-view" class="mx-auto flex w-full max-w-[1440px] flex-col gap-3 text-ztx-text">
    <header class="flex items-center justify-between gap-3">
      <div><h1 class="m-0 text-ztx-page-title">{{ title }}</h1><p class="mt-1 text-ztx-caption text-ztx-muted">独立报表视图 · 查询参数、指标、图表与结果明细</p></div>
      <ZButton variant="secondary" disabled>导出</ZButton>
    </header>

    <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3" aria-label="报表参数">
      <div class="flex flex-wrap items-end gap-2.5">
        <label class="flex min-w-36 flex-1 flex-col gap-1 text-ztx-caption text-ztx-muted">仓库
          <select v-model="warehouse" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text"><option value="">全部仓库</option></select>
        </label>
        <label class="flex min-w-36 flex-1 flex-col gap-1 text-ztx-caption text-ztx-muted">物料
          <select v-model="material" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text"><option value="">全部物料</option></select>
        </label>
        <label class="flex min-w-36 flex-1 flex-col gap-1 text-ztx-caption text-ztx-muted">开始日期
          <input v-model="fromDate" type="date" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
        </label>
        <label class="flex min-w-36 flex-1 flex-col gap-1 text-ztx-caption text-ztx-muted">结束日期
          <input v-model="toDate" type="date" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
        </label>
        <ZButton disabled>查询</ZButton>
        <ZButton variant="secondary" disabled>导出</ZButton>
      </div>
    </section>

    <section class="grid grid-cols-4 gap-3 max-[900px]:grid-cols-2 max-[560px]:grid-cols-1" aria-label="关键指标">
      <article v-for="metric in metrics" :key="metric" class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3">
        <div class="text-ztx-caption text-ztx-muted">{{ metric }}</div>
        <div class="mt-2 text-[22px] font-bold tabular-nums text-ztx-text">—</div>
        <div class="mt-1 text-ztx-caption text-ztx-muted">等待报表数据集</div>
      </article>
    </section>

    <section class="grid grid-cols-[minmax(0,1.45fr)_minmax(260px,.8fr)] gap-3 max-[900px]:grid-cols-1" aria-label="图表结果">
      <article class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <div class="mb-3 flex items-center justify-between"><h2 class="m-0 text-ztx-section-title font-semibold">库存趋势分析</h2><span class="text-ztx-caption text-ztx-muted">数量 / 金额</span></div>
        <svg viewBox="0 0 640 210" class="h-[210px] w-full" role="img" aria-label="库存趋势图表占位">
          <g stroke="var(--ztx-border)" stroke-width="1"><path d="M42 25H625 M42 70H625 M42 115H625 M42 160H625 M42 190H625" /></g>
          <g fill="var(--ztx-muted)" font-size="11"><text x="4" y="29">30万</text><text x="4" y="74">20万</text><text x="4" y="119">10万</text><text x="15" y="194">0</text><text x="42" y="207">10-01</text><text x="180" y="207">10-08</text><text x="320" y="207">10-15</text><text x="460" y="207">10-22</text><text x="585" y="207">10-31</text></g>
          <path d="M42 160L100 138L150 145L205 112L260 123L315 105L370 125L425 85L480 102L535 72L585 88L625 62" fill="none" stroke="var(--ztx-primary)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M42 178L100 158L150 165L205 143L260 153L315 135L370 150L425 120L480 134L535 110L585 118L625 95" fill="none" stroke="var(--ztx-success)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </article>
      <article class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5">
        <h2 class="mb-3 text-ztx-section-title font-semibold">库存结构</h2>
        <div class="flex min-h-[185px] items-center justify-center gap-5">
          <svg viewBox="0 0 140 140" class="size-36" role="img" aria-label="库存结构图表占位">
            <circle cx="70" cy="70" r="48" fill="none" stroke="var(--ztx-border)" stroke-width="18" />
            <circle cx="70" cy="70" r="48" fill="none" stroke="var(--ztx-primary)" stroke-width="18" stroke-dasharray="105 302" transform="rotate(-90 70 70)" />
            <circle cx="70" cy="70" r="48" fill="none" stroke="var(--ztx-success)" stroke-width="18" stroke-dasharray="72 302" stroke-dashoffset="-105" transform="rotate(-90 70 70)" />
            <circle cx="70" cy="70" r="48" fill="none" stroke="var(--ztx-number-accent)" stroke-width="18" stroke-dasharray="125 302" stroke-dashoffset="-177" transform="rotate(-90 70 70)" />
            <text x="70" y="74" text-anchor="middle" fill="var(--ztx-text)" font-size="15" font-weight="700">—</text>
          </svg>
          <div class="flex flex-col gap-3 text-ztx-caption text-ztx-muted">
            <span class="inline-flex items-center gap-2"><i class="size-2 rounded-full bg-ztx-primary" />原材料</span>
            <span class="inline-flex items-center gap-2"><i class="size-2 rounded-full bg-ztx-success" />在制品</span>
            <span class="inline-flex items-center gap-2"><i class="size-2 rounded-full bg-ztx-number-accent" />产成品</span>
          </div>
        </div>
      </article>
    </section>

    <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3.5" aria-label="报表结果">
      <div class="mb-3 flex items-center justify-between"><h2 class="m-0 text-ztx-section-title font-semibold">库存明细</h2><span class="text-ztx-caption text-ztx-muted">结果表格区域</span></div>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[700px] border-collapse text-left text-ztx-caption">
          <thead><tr class="border-b border-ztx-border text-ztx-muted"><th class="px-2 py-2 font-medium">物料编码</th><th class="px-2 py-2 font-medium">物料名称</th><th class="px-2 py-2 font-medium">规格型号</th><th class="px-2 py-2 font-medium">单位</th><th class="px-2 py-2 text-right font-medium">期初数量</th><th class="px-2 py-2 text-right font-medium">入库数量</th><th class="px-2 py-2 text-right font-medium">出库数量</th><th class="px-2 py-2 text-right font-medium">期末数量</th><th class="px-2 py-2 text-right font-medium">金额</th></tr></thead>
          <tbody><tr><td colspan="9" class="px-3 py-12 text-center text-ztx-muted">暂无报表数据。请先配置报表定义、数据集与查询参数。</td></tr></tbody>
        </table>
      </div>
      <ZAlert tone="info" class="mt-3">{{ message }}</ZAlert>
    </section>
  </section>
</template>
