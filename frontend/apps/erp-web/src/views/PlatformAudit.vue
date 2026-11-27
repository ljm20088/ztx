<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ZAlert, ZButton } from '@ztx/ui'

type AuditEvent = {
  id: string
  eventType: string
  outcome: 'SUCCESS' | 'FAILURE' | 'DENIED'
  sourceIp?: string | null
  adminUsername?: string | null
  occurredAt: string
}
const events = ref<AuditEvent[]>([])
const loading = ref(false)
const error = ref('')
async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch('/api/v1/platform/audit', { credentials: 'include', cache: 'no-store' })
    if (!response.ok) throw new Error('安全审计记录读取失败')
    events.value = await response.json() as AuditEvent[]
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '安全审计记录读取失败'
  } finally {
    loading.value = false
  }
}
onMounted(() => { void refresh() })
function eventTypeLabel(value: string) {
  const labels: Record<string, string> = {
    ADMIN_INITIALIZED: '平台管理员初始化',
    LOGIN: '登录',
    MFA_LOGIN: '动态验证码登录',
    LOGOUT: '退出登录',
    PASSWORD_CHANGED: '修改密码',
    MFA_ENROLLMENT_STARTED: '开始绑定多重身份验证',
    MFA_ENABLED: '启用多重身份验证',
    ADMIN_PASSWORD_RESET: '重置平台管理员密码',
  }
  return labels[value] ?? '其他操作'
}
function outcomeLabel(value: AuditEvent['outcome']) {
  return value === 'SUCCESS' ? '成功' : value === 'FAILURE' ? '失败' : '拒绝'
}
</script>

<template>
  <section class="mx-auto w-full max-w-[1180px]">
    <header class="mb-4 flex items-center justify-between gap-3">
      <div><h1 class="m-0 text-ztx-page-title">安全审计</h1><p class="mt-1.5 text-ztx-caption text-ztx-muted">查看平台管理员登录和平台管理操作记录。</p></div>
      <ZButton variant="secondary" :disabled="loading" @click="refresh">{{ loading ? '正在刷新…' : '刷新记录' }}</ZButton>
    </header>
    <ZAlert v-if="error" class="mb-3" tone="error" role="alert">{{ error }}</ZAlert>
    <div class="overflow-x-auto rounded-ztx-md border border-ztx-border bg-ztx-surface">
      <table class="w-full border-collapse text-left text-ztx-body">
        <thead class="bg-ztx-surface-soft text-ztx-muted"><tr><th class="px-3 py-2.5 font-medium">时间</th><th class="px-3 py-2.5 font-medium">操作</th><th class="px-3 py-2.5 font-medium">结果</th><th class="px-3 py-2.5 font-medium">管理员</th><th class="px-3 py-2.5 font-medium">来源 IP</th></tr></thead>
        <tbody>
          <tr v-for="event in events" :key="event.id" class="border-t border-ztx-border">
            <td class="whitespace-nowrap px-3 py-2.5">{{ new Date(event.occurredAt).toLocaleString() }}</td>
            <td class="px-3 py-2.5">{{ eventTypeLabel(event.eventType) }}</td>
            <td class="px-3 py-2.5">{{ outcomeLabel(event.outcome) }}</td>
            <td class="px-3 py-2.5">{{ event.adminUsername || '—' }}</td>
            <td class="px-3 py-2.5">{{ event.sourceIp || '—' }}</td>
          </tr>
          <tr v-if="!events.length"><td colspan="5" class="px-3 py-8 text-center text-ztx-muted">暂无审计记录</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
