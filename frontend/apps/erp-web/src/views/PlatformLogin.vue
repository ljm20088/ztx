<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'

const route = useRoute()
const router = useRouter()
const mode = ref<'login' | 'initialize'>('login')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const totp = ref('')
const setupToken = ref('')
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    const statusResponse = await fetch('/api/v1/platform/auth/status', { cache: 'no-store' })
    if (!statusResponse.ok) throw new Error('无法读取平台初始化状态，请检查数据库连接和服务状态。')
    const status = await statusResponse.json() as { initialized: boolean }
    mode.value = status.initialized ? 'login' : 'initialize'
    if (!status.initialized) {
      // 本机或 SSH 本地端口转发访问时，自动从 loopback 安装管理入口获取口令。
      const tokenResponse = await fetch('/api/v1/platform/auth/setup-token', { cache: 'no-store' })
      if (tokenResponse.ok) {
        const token = await tokenResponse.json() as { token: string }
        setupToken.value = token.token
      }
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '认证服务暂不可用'
  }
})

async function submit() {
  error.value = ''
  if (mode.value === 'initialize' && password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致。'
    return
  }
  busy.value = true
  try {
    const endpoint = mode.value === 'initialize'
      ? '/api/v1/platform/auth/initialize'
      : '/api/v1/platform/auth/login'
    const payload = mode.value === 'initialize'
      ? { token: setupToken.value.trim(), username: username.value.trim(), password: password.value }
      : { username: username.value.trim(), password: password.value, totp: totp.value || undefined }
    const response = await fetch(endpoint, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const data = response.status === 204 ? null : await response.json()
    if (!response.ok) throw new Error(data?.error?.message ?? '请求失败，请稍后重试')
    const target = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/platform/') ? route.query.redirect : '/platform/admin'
    await router.replace(target)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '认证服务暂不可用'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-ztx-page px-4 py-8">
    <section class="w-full max-w-[440px] rounded-[9px] border border-ztx-border bg-ztx-surface px-8 py-[30px] shadow-[var(--ztx-shadow-popover)] max-[520px]:px-5 max-[520px]:py-6">
      <div class="mb-8 flex items-center gap-3">
        <span class="grid size-[38px] place-items-center rounded-ztx-md bg-ztx-primary text-[19px] font-extrabold text-white">Z</span>
        <div class="flex flex-col gap-[3px]"><strong class="text-[17px] tracking-[.12em]">ZTX</strong><small class="text-[8px] tracking-[.13em] text-ztx-muted">PLATFORM CONTROL PLANE</small></div>
      </div>
      <div data-testid="platform-auth-heading" class="mb-6">
        <span class="text-ztx-caption font-bold tracking-[.14em] text-ztx-muted">SECURE ACCESS</span>
        <h1 class="my-2 text-ztx-page-title text-ztx-text">{{ mode === 'login' ? '平台管理员登录' : '欢迎使用 ZTX，创建平台管理员' }}</h1>
        <p class="text-ztx-caption leading-[1.7] text-ztx-muted">{{ mode === 'login' ? '使用平台控制面账号登录。此账号与企业用户账号相互独立。' : '首次初始化只需设置用户名和密码。创建成功后将自动进入平台控制台。' }}</p>
      </div>
      <form data-testid="platform-auth-form" class="flex flex-col gap-[15px]" @submit.prevent="submit">
        <ZFormField v-if="mode === 'initialize'" label="一次性初始化口令" field-id="setup-token" required>
          <ZInput id="setup-token" v-model="setupToken" autocomplete="off" required placeholder="输入部署人员提供的一次性口令" />
        </ZFormField>
        <ZFormField label="用户名" field-id="platform-username" required>
          <ZInput id="platform-username" v-model="username" autocomplete="username" required minlength="3" maxlength="80" placeholder="请输入平台管理员用户名" />
        </ZFormField>
        <ZFormField label="密码" field-id="platform-password" required>
          <ZInput id="platform-password" v-model="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" type="password" required minlength="8" placeholder="至少 8 位，包含字母和数字" />
        </ZFormField>
        <ZFormField v-if="mode === 'initialize'" label="确认密码" field-id="confirm-password" required>
          <ZInput id="confirm-password" v-model="confirmPassword" autocomplete="new-password" type="password" required minlength="8" placeholder="请再次输入密码" />
        </ZFormField>
        <ZFormField v-if="mode === 'login'" label="动态验证码（启用 MFA 后填写）" field-id="totp">
          <ZInput id="totp" v-model="totp" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6 位验证码" />
        </ZFormField>
        <ZAlert v-if="error" tone="error" role="alert">{{ error }}</ZAlert>
        <ZButton data-testid="platform-auth-submit" class="w-full" type="submit" size="lg" :disabled="busy">
          {{ busy ? '正在处理…' : mode === 'login' ? '安全登录' : '创建平台管理员' }}
        </ZButton>
      </form>
      <ZAlert v-if="mode === 'initialize'" class="mt-3" tone="info">
        正式部署可通过服务器本机安装管理入口（或 SSH 本地端口转发）获取口令；服务器终端也可作为备用方式。
      </ZAlert>
      <div data-testid="platform-auth-foot" class="mt-6 border-t border-ztx-border pt-4 text-center text-ztx-caption text-ztx-muted">平台身份独立存储 · 会话加密保护 · 安全操作留痕</div>
      <div class="mt-3 flex justify-center gap-1.5 text-ztx-caption text-ztx-muted"><span>企业用户？</span><RouterLink class="text-ztx-primary-strong no-underline hover:underline" to="/tenant/login">返回企业登录</RouterLink></div>
    </section>
  </main>
</template>
