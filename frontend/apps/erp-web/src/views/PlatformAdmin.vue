<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'

const router = useRouter()
const admin = ref<{ id: string; username: string; mfaEnabled: boolean } | null>(null)
const message = ref('')
const error = ref('')
const enrollment = ref<{ secret: string; otpauthUri: string } | null>(null)
const code = ref('')
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')

async function request(path: string, method = 'GET', payload?: unknown) {
  const response = await fetch(path, {
    method,
    credentials: 'include',
    headers: payload ? { 'content-type': 'application/json' } : undefined,
    body: payload ? JSON.stringify(payload) : undefined,
  })
  if (response.status === 401) {
    await router.replace('/platform/login')
    throw new Error('登录会话已失效，请重新登录')
  }
  if (!response.ok) {
    const data = await response.json().catch(() => null)
    throw new Error(data?.error?.message ?? '操作失败')
  }
  return response.status === 204 ? null : response.json()
}

onMounted(async () => {
  try { admin.value = await request('/api/v1/platform/auth/me') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取平台身份' }
})

async function beginMfa() {
  try {
    enrollment.value = await request('/api/v1/platform/auth/mfa/enroll', 'POST')
    message.value = '请使用认证器扫描 URI 或手动录入密钥，再输入动态验证码完成启用。'
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法启用 MFA' }
}

async function enableMfa() {
  try {
    await request('/api/v1/platform/auth/mfa/enable', 'POST', { code: code.value })
    admin.value = await request('/api/v1/platform/auth/me')
    enrollment.value = null
    code.value = ''
    message.value = '多因素认证已启用。'
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'MFA 验证失败' }
}

async function changePassword() {
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  try {
    await request('/api/v1/platform/auth/password', 'POST', { oldPassword: oldPassword.value, newPassword: newPassword.value })
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    message.value = '密码已更新，其他活动会话已撤销。'
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '密码更新失败' }
}
</script>

<template>
  <section class="mx-auto w-full max-w-[1180px]">
    <header data-testid="platform-admin-heading" class="mb-[18px] flex items-end justify-between gap-4 max-[780px]:items-start">
      <div><span class="text-ztx-caption font-bold tracking-[.13em] text-ztx-muted">PLATFORM CONTROL PLANE</span><h1 class="my-1 text-ztx-page-title text-ztx-text">平台概览</h1><p class="text-ztx-caption text-ztx-muted">平台运行入口与平台管理员账户安全。平台身份不属于任何企业。</p></div>
      <div class="flex gap-2"><ZButton variant="secondary" @click="router.push('/platform/operations')">运维管理</ZButton><ZButton variant="secondary" @click="router.push('/platform/tenants')">租户管理</ZButton></div>
    </header>
    <ZAlert v-if="message" class="mb-3" tone="info">{{ message }}</ZAlert>
    <ZAlert v-if="error" class="mb-3" tone="error" role="alert">{{ error }}</ZAlert>
    <div v-if="admin" class="grid grid-cols-2 gap-[14px] max-[780px]:grid-cols-1">
      <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-[17px]">
        <span class="text-ztx-caption font-bold tracking-[.13em] text-ztx-muted">CURRENT IDENTITY</span><h2 class="my-2 text-ztx-section-title font-semibold">管理员身份</h2>
        <dl class="m-0">
          <div class="flex justify-between gap-3 border-b border-ztx-border py-[11px] text-ztx-body last:border-0"><dt class="text-ztx-muted">管理员账号</dt><dd class="m-0 font-semibold text-ztx-text">{{ admin.username }}</dd></div>
          <div class="flex justify-between gap-3 border-b border-ztx-border py-[11px] text-ztx-body last:border-0"><dt class="text-ztx-muted">身份类型</dt><dd class="m-0 font-semibold text-ztx-text">平台管理员</dd></div>
          <div class="flex justify-between gap-3 border-b border-ztx-border py-[11px] text-ztx-body last:border-0"><dt class="text-ztx-muted">多因素认证</dt><dd class="m-0 font-semibold text-ztx-text">{{ admin.mfaEnabled ? '已启用' : '未启用' }}</dd></div>
        </dl>
      </section>
      <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-[17px]">
        <span class="text-ztx-caption font-bold tracking-[.13em] text-ztx-muted">ACCOUNT SECURITY</span><h2 class="my-2 text-ztx-section-title font-semibold">安全设置</h2>
        <div class="flex items-center justify-between gap-3 border-b border-ztx-border py-2 pb-[15px]">
          <div class="flex flex-col gap-1"><strong class="text-ztx-body">多因素认证（TOTP）</strong><small class="text-ztx-caption leading-relaxed text-ztx-muted">使用认证器动态验证码保护平台登录。</small></div>
          <ZButton :disabled="admin.mfaEnabled" @click="beginMfa">{{ admin.mfaEnabled ? '已启用' : '设置 MFA' }}</ZButton>
        </div>
        <div v-if="enrollment" class="flex flex-col gap-2 border-b border-ztx-border py-3">
          <strong class="text-ztx-body text-ztx-text-secondary">手动录入密钥</strong><code class="break-all bg-ztx-surface-warm p-2 text-ztx-caption">{{ enrollment.secret }}</code><small class="break-all text-ztx-caption leading-relaxed text-ztx-muted">认证器 URI：{{ enrollment.otpauthUri }}</small><ZInput v-model="code" inputmode="numeric" maxlength="6" placeholder="输入 6 位动态验证码" /><ZButton @click="enableMfa">验证并启用</ZButton>
        </div>
        <div id="password-settings" class="grid grid-cols-1 gap-[9px] pt-[14px]">
          <ZFormField label="当前密码" field-id="old-password"><ZInput id="old-password" v-model="oldPassword" type="password" autocomplete="current-password" /></ZFormField>
          <ZFormField label="新密码" field-id="new-password"><ZInput id="new-password" v-model="newPassword" type="password" autocomplete="new-password" placeholder="至少 8 位，且同时包含字母和数字" /></ZFormField>
          <ZFormField label="确认新密码" field-id="confirm-password"><ZInput id="confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入新密码" /></ZFormField>
          <ZButton class="justify-self-start" variant="secondary" @click="changePassword">更新密码</ZButton>
        </div>
      </section>
    </div>
  </section>
</template>
