<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const admin = ref<{ id: string; username: string; mfaEnabled: boolean } | null>(null)
const message = ref('')
const error = ref('')
const enrollment = ref<{ secret: string; otpauthUri: string } | null>(null)
const code = ref('')
const oldPassword = ref('')
const newPassword = ref('')

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
  try {
    await request('/api/v1/platform/auth/password', 'POST', { oldPassword: oldPassword.value, newPassword: newPassword.value })
    oldPassword.value = ''
    newPassword.value = ''
    message.value = '密码已更新，其他活动会话已撤销。'
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '密码更新失败' }
}
</script>

<template>
  <section class="platform-admin-page">
    <header class="platform-admin-heading">
      <div><span class="eyebrow">PLATFORM CONTROL PLANE</span><h1>平台控制台</h1><p>平台级身份与安全设置。此处不管理租户业务用户。</p></div>
      
    </header>
    <p v-if="message" class="admin-message" role="status">{{ message }}</p>
    <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
    <div v-if="admin" class="admin-grid">
      <section class="admin-panel">
        <span class="eyebrow">CURRENT IDENTITY</span><h2>管理员身份</h2>
        <dl><div><dt>管理员账号</dt><dd>{{ admin.username }}</dd></div><div><dt>身份类型</dt><dd>平台管理员</dd></div><div><dt>多因素认证</dt><dd>{{ admin.mfaEnabled ? '已启用' : '未启用' }}</dd></div></dl>
      </section>
      <section class="admin-panel">
        <span class="eyebrow">ACCOUNT SECURITY</span><h2>安全设置</h2>
        <div class="admin-action-row"><div><strong>多因素认证（TOTP）</strong><small>使用认证器动态验证码保护平台登录。</small></div><button class="admin-primary" :disabled="admin.mfaEnabled" @click="beginMfa">{{ admin.mfaEnabled ? '已启用' : '设置 MFA' }}</button></div>
        <div v-if="enrollment" class="mfa-enrollment"><label>手动录入密钥</label><code>{{ enrollment.secret }}</code><small>认证器 URI：{{ enrollment.otpauthUri }}</small><input v-model="code" inputmode="numeric" maxlength="6" placeholder="输入 6 位动态验证码" /><button class="admin-primary" @click="enableMfa">验证并启用</button></div>
        <div id="password-settings" class="password-form"><label>当前密码<input v-model="oldPassword" type="password" autocomplete="current-password" /></label><label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" placeholder="至少 8 位，包含字母和数字" /></label><button class="admin-secondary" @click="changePassword">更新密码</button></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.platform-admin-page{width:min(100%,1180px);margin:0 auto}.platform-admin-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;margin-bottom:18px}.eyebrow{font-size:var(--ztx-font-size-caption);font-weight:700;letter-spacing:.13em;color:var(--ztx-muted)}h1{margin:5px 0;font-size:var(--ztx-font-size-page-title);color:var(--ztx-text)}.platform-admin-heading p{margin:0;color:var(--ztx-muted);font-size:var(--ztx-font-size-caption)}.admin-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.admin-panel{padding:17px;border:1px solid var(--ztx-border);border-radius:7px;background:var(--ztx-surface)}h2{margin:5px 0 15px;font-size:var(--ztx-font-size-section-title)}.admin-panel dl{margin:0}.admin-panel dl div{display:flex;justify-content:space-between;gap:12px;padding:11px 0;border-bottom:1px solid var(--ztx-border);font-size:var(--ztx-font-size-body)}.admin-panel dl div:last-child{border-bottom:0}.admin-panel dt{color:var(--ztx-muted)}.admin-panel dd{margin:0;color:var(--ztx-text);font-weight:650}.admin-action-row{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:8px 0 15px;border-bottom:1px solid var(--ztx-border)}.admin-action-row>div,.password-form label{display:flex;flex-direction:column;gap:5px} .admin-action-row strong{font-size:var(--ztx-font-size-body)}.admin-action-row small,.mfa-enrollment small{color:var(--ztx-muted);font-size:var(--ztx-font-size-caption);line-height:1.5}.admin-primary,.admin-secondary{min-height:32px;padding:0 12px;border:1px solid var(--ztx-border-strong);border-radius:5px;background:var(--ztx-surface);color:var(--ztx-text);font-size:var(--ztx-font-size-body);font-weight:650;cursor:pointer}.admin-primary{border-color:var(--ztx-primary);background:var(--ztx-primary);color:white}.admin-primary:disabled{opacity:.55;cursor:default}.mfa-enrollment{display:flex;flex-direction:column;gap:8px;padding:13px 0;border-bottom:1px solid var(--ztx-border)}.mfa-enrollment label,.password-form label{color:var(--ztx-muted);font-size:var(--ztx-font-size-body)}.mfa-enrollment code{overflow-wrap:anywhere;padding:8px;background:var(--ztx-surface-warm);font-size:var(--ztx-font-size-caption)}.mfa-enrollment input,.password-form input{height:34px;padding:0 9px;border:1px solid var(--ztx-border-strong);border-radius:4px;background:#fffefa;font-size:var(--ztx-font-size-body)}.password-form{display:grid;grid-template-columns:1fr 1fr;gap:9px;padding-top:14px}.password-form button{grid-column:1/-1;justify-self:start}.admin-message,.admin-error{padding:9px 11px;border-radius:5px;font-size:var(--ztx-font-size-caption)}.admin-message{background:var(--ztx-surface-warm);color:var(--ztx-primary-strong)}.admin-error{background:#f8e9e5;color:#934b3e}@media(max-width:780px){.admin-grid{grid-template-columns:1fr}.platform-admin-heading{align-items:flex-start}}
</style>
