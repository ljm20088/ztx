<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ZAlert, ZButton } from '@ztx/ui'

const route = useRoute()
const reportId = computed(() => String(route.params.reportId ?? ''))
const period = ref('month')
const fromDate = ref('')
const toDate = ref('')
const message = ref('当前报表尚未配置数据集或查询定义。')
</script>

<template>
  <section class="mx-auto flex w-full max-w-[1440px] flex-col gap-3 text-ztx-text" data-testid="report-view">
    <div class="flex items-center justify-between gap-3 border-b border-ztx-border pb-3">
      <div>
        <h1 class="m-0 text-ztx-page-title">{{ reportId }}</h1>
        <p class="mt-1 text-ztx-caption text-ztx-muted">独立报表视图 · 参数与结果区域</p>
      </div>
      <ZButton variant="secondary" disabled>导出</ZButton>
    </div>
    <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3" aria-label="报表参数">
      <h2 class="mb-3 text-ztx-section-title font-semibold">查询参数</h2>
      <div class="flex flex-wrap items-end gap-3">
        <label class="flex min-w-36 flex-col gap-1 text-ztx-caption text-ztx-muted">
          统计周期
          <select v-model="period" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
            <option value="day">按日</option><option value="month">按月</option><option value="year">按年</option>
          </select>
        </label>
        <label class="flex min-w-40 flex-col gap-1 text-ztx-caption text-ztx-muted">
          开始日期
          <input v-model="fromDate" type="date" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
        </label>
        <label class="flex min-w-40 flex-col gap-1 text-ztx-caption text-ztx-muted">
          结束日期
          <input v-model="toDate" type="date" class="rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
        </label>
        <ZButton disabled>运行报表</ZButton>
      </div>
    </section>
    <section class="min-h-64 rounded-ztx-md border border-ztx-border bg-ztx-surface p-4" aria-label="报表结果">
      <h2 class="mb-3 text-ztx-section-title font-semibold">报表结果</h2>
      <ZAlert tone="info">{{ message }}</ZAlert>
    </section>
  </section>
</template>
