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
  <main class="platform-auth-page">
    <section class="platform-auth-card">
      <div class="platform-auth-brand"><span>Z</span><div><strong>ZTX</strong><small>PLATFORM CONTROL PLANE</small></div></div>
      <div class="platform-auth-heading">
        <span class="eyebrow">SECURE ACCESS</span>
        <h1>{{ mode === 'login' ? '平台管理员登录' : '欢迎使用 ZTX，创建平台管理员' }}</h1>
        <p>{{ mode === 'login' ? '使用平台控制面账号登录。此账号与企业用户账号相互独立。' : '首次初始化只需设置用户名和密码。创建成功后将自动进入平台控制台。' }}</p>
      </div>
      <form class="platform-auth-form" @submit.prevent="submit">
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
        <ZButton class="platform-auth-submit" type="submit" size="lg" :disabled="busy">
          {{ busy ? '正在处理…' : mode === 'login' ? '安全登录' : '创建平台管理员' }}
        </ZButton>
      </form>
      <ZAlert v-if="mode === 'initialize'" class="platform-auth-hint" tone="info">
        正式部署可通过服务器本机安装管理入口（或 SSH 本地端口转发）获取口令；服务器终端也可作为备用方式。
      </ZAlert>
      <div class="platform-auth-foot">平台身份独立存储 · 会话加密保护 · 安全操作留痕</div>
      <div class="platform-auth-enterprise-link"><span>企业用户？</span><RouterLink to="/tenant/login">返回企业登录</RouterLink></div>
    </section>
  </main>
</template>

<style scoped>
.platform-auth-page{min-height:100vh;display:grid;place-items:center;padding:32px 16px;background:var(--ztx-page)}
.platform-auth-card{width:min(100%,440px);padding:30px 32px;border:1px solid var(--ztx-border);border-radius:9px;background:var(--ztx-surface);box-shadow:var(--ztx-shadow-popover)}
.platform-auth-brand{display:flex;align-items:center;gap:11px;margin-bottom:32px}.platform-auth-brand>span{width:38px;height:38px;display:grid;place-items:center;border-radius:8px;background:var(--ztx-primary);color:#fff;font-weight:800;font-size:19px}.platform-auth-brand>div{display:flex;flex-direction:column;gap:3px}.platform-auth-brand strong{font-size:17px;letter-spacing:.12em}.platform-auth-brand small{font-size:8px;letter-spacing:.13em;color:var(--ztx-muted)}
.platform-auth-heading{margin-bottom:24px}.eyebrow{font-size:var(--ztx-font-size-caption);font-weight:700;letter-spacing:.14em;color:var(--ztx-muted)}h1{margin:7px 0 8px;font-size:var(--ztx-font-size-page-title);color:var(--ztx-text)}.platform-auth-heading p{margin:0;color:var(--ztx-muted);font-size:12px;line-height:1.7}
.platform-auth-form{display:flex;flex-direction:column;gap:15px}.platform-auth-submit{width:100%}.platform-auth-hint{margin:14px 0 0}
.platform-auth-foot{margin-top:25px;padding-top:15px;border-top:1px solid var(--ztx-border);color:var(--ztx-muted);font-size:var(--ztx-font-size-caption);text-align:center}
@media(max-width:520px){.platform-auth-card{padding:24px 20px}}
.platform-auth-enterprise-link{display:flex;justify-content:center;gap:6px;margin-top:14px;color:var(--ztx-muted);font-size:var(--ztx-font-size-caption)}
.platform-auth-enterprise-link a{color:var(--ztx-primary-strong);text-decoration:none}.platform-auth-enterprise-link a:hover{text-decoration:underline}
</style>
