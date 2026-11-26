<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ZAlert, ZButton } from '@ztx/ui'

type LiveStatus = { status: string; service: string; version: string; startedAt: string }
type ReadyStatus = { status: string; components: { database: string } }

const live = ref<LiveStatus | null>(null)
const ready = ref<ReadyStatus | null>(null)
const loading = ref(false)
const error = ref('')

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [liveResponse, readyResponse] = await Promise.all([
      fetch('/health/live', { cache: 'no-store' }),
      fetch('/health/ready', { cache: 'no-store' }),
    ])
    if (!liveResponse.ok) throw new Error('无法读取 Server 运行状态')
    live.value = await liveResponse.json() as LiveStatus
    if (readyResponse.ok) ready.value = await readyResponse.json() as ReadyStatus
    else ready.value = { status: 'NOT_READY', components: { database: 'DOWN' } }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '运维状态读取失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void refresh() })
function stateLabel(value?: string) {
  return value === 'UP' || value === 'READY' ? '正常' : value === 'DOWN' || value === 'NOT_READY' ? '异常' : '未知'
}
</script>

<template>
  <section class="mx-auto w-full max-w-[1180px]">
    <header class="mb-4 flex items-center justify-between gap-3">
      <div><h1 class="m-0 text-ztx-page-title">运维管理</h1><p class="mt-1.5 text-ztx-caption text-ztx-muted">查看平台服务和数据库的基础运行状态。</p></div>
      <ZButton variant="secondary" :disabled="loading" @click="refresh">{{ loading ? '正在刷新…' : '刷新状态' }}</ZButton>
    </header>
    <ZAlert v-if="error" class="mb-3" tone="error" role="alert">{{ error }}</ZAlert>
    <div class="grid grid-cols-2 gap-3 max-[760px]:grid-cols-1">
      <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
        <div class="text-ztx-caption text-ztx-muted">API Server</div>
        <div class="mt-2 text-ztx-section-title font-semibold">{{ stateLabel(live?.status) }}</div>
        <dl class="mt-3 grid grid-cols-[90px_1fr] gap-y-2 text-ztx-body">
          <dt class="text-ztx-muted">版本</dt><dd class="m-0">{{ live?.version ?? '—' }}</dd>
          <dt class="text-ztx-muted">启动时间</dt><dd class="m-0">{{ live?.startedAt ? new Date(live.startedAt).toLocaleString() : '—' }}</dd>
        </dl>
      </section>
      <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
        <div class="text-ztx-caption text-ztx-muted">PostgreSQL</div>
        <div class="mt-2 text-ztx-section-title font-semibold">{{ stateLabel(ready?.components.database) }}</div>
        <p class="mt-3 text-ztx-caption text-ztx-muted">通过就绪检查确认数据库连接状态。</p>
      </section>
      <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
        <div class="text-ztx-caption text-ztx-muted">Worker</div>
        <div class="mt-2 text-ztx-section-title font-semibold">状态未接入</div>
        <p class="mt-3 text-ztx-caption text-ztx-muted">Worker 独立运行，后续接入心跳后显示实时状态。</p>
      </section>
    </div>
  </section>
</template>
