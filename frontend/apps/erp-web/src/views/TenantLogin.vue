<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route=useRoute(),router=useRouter()
const mode=ref<'login'|'setup'>('login')
const tenantCode=ref(''),username=ref(''),password=ref(''),confirmPassword=ref(''),setupToken=ref('')
const busy=ref(false),error=ref('')

async function submit(){
 error.value=''
 if(mode.value==='setup'&&password.value!==confirmPassword.value){error.value='两次输入的密码不一致。';return}
 busy.value=true
 try{
  const endpoint=mode.value==='setup'?'/api/v1/tenant/auth/setup':'/api/v1/tenant/auth/login'
  const payload=mode.value==='setup'
   ?{tenantCode:tenantCode.value.trim(),token:setupToken.value.trim(),username:username.value.trim(),password:password.value}
   :{tenantCode:tenantCode.value.trim(),username:username.value.trim(),password:password.value}
  const response=await fetch(endpoint,{method:'POST',credentials:'include',headers:{'content-type':'application/json'},body:JSON.stringify(payload)})
  const data=await response.json().catch(()=>null)
  if(!response.ok)throw new Error(data?.error?.message??'登录失败，请检查企业编码和账号密码。')
  const target=typeof route.query.redirect==='string'&&route.query.redirect.startsWith('/')&&!route.query.redirect.startsWith('//')&&!route.query.redirect.startsWith('/platform')?route.query.redirect:'/workspace'
  await router.replace(target)
 }catch(cause){error.value=cause instanceof Error?cause.message:'认证服务暂不可用'}
 finally{busy.value=false}
}
</script>

<template>
 <main class="tenant-auth-page">
  <section class="tenant-auth-card">
   <div class="tenant-auth-brand"><span>Z</span><div><strong>ZTX</strong><small>ENTERPRISE WORKSPACE</small></div></div>
   <div class="tenant-auth-heading"><h1>{{mode==='login'?'企业登录':'设置企业管理员账号'}}</h1><p>请输入所属企业的企业编码及个人账号信息。</p></div>
   <form class="tenant-auth-form" @submit.prevent="submit">
    <label>企业编码<input v-model.trim="tenantCode" required minlength="2" maxlength="40" autocomplete="organization" placeholder="请输入企业编码"/></label>
    <label v-if="mode==='setup'">一次性初始化凭据<input v-model.trim="setupToken" required autocomplete="off" placeholder="由平台管理员提供"/></label>
    <label>用户名<input v-model.trim="username" required minlength="3" maxlength="80" autocomplete="username" placeholder="请输入用户名"/></label>
    <label>密码<input v-model="password" required minlength="8" type="password" :autocomplete="mode==='login'?'current-password':'new-password'" placeholder="至少 8 位，包含字母和数字"/></label>
    <label v-if="mode==='setup'">确认密码<input v-model="confirmPassword" required minlength="8" type="password" autocomplete="new-password"/></label>
    <p v-if="error" class="tenant-auth-error" role="alert">{{error}}</p>
    <button class="tenant-auth-submit" type="submit" :disabled="busy">{{busy?'正在处理…':mode==='login'?'安全登录':'完成账号设置'}}</button>
   </form>
   <button class="tenant-auth-switch" type="button" @click="mode=mode==='login'?'setup':'login';error=''">{{mode==='login'?'首次使用？设置企业管理员账号':'返回登录'}}</button>
   <div class="tenant-auth-foot">企业身份独立验证 · 企业数据由服务端安全隔离</div>
   <div class="tenant-auth-platform-link"><span>平台运营人员？</span><RouterLink to="/platform/login">平台管理登录</RouterLink></div>
  </section>
 </main>
</template>

<style scoped>
.tenant-auth-page{min-height:100vh;display:grid;place-items:center;padding:32px 16px;background:var(--ztx-page)}
.tenant-auth-card{width:min(100%,440px);padding:30px 32px;border:1px solid var(--ztx-border);border-radius:9px;background:var(--ztx-surface);box-shadow:var(--ztx-shadow-popover)}
.tenant-auth-brand{display:flex;align-items:center;gap:11px;margin-bottom:28px}.tenant-auth-brand>span{width:38px;height:38px;display:grid;place-items:center;border-radius:8px;background:var(--ztx-primary);color:#fff;font-weight:800;font-size:19px}.tenant-auth-brand>div{display:flex;flex-direction:column;gap:3px}.tenant-auth-brand strong{font-size:17px;letter-spacing:.12em}.tenant-auth-brand small{font-size:9px;letter-spacing:.13em;color:var(--ztx-muted)}
.tenant-auth-heading{margin-bottom:22px}.tenant-auth-heading h1{margin:0 0 8px;font-size:var(--ztx-font-size-page-title);color:var(--ztx-text)}.tenant-auth-heading p{margin:0;color:var(--ztx-muted);font-size:var(--ztx-font-size-caption);line-height:1.7}
.tenant-auth-form{display:flex;flex-direction:column;gap:13px}.tenant-auth-form label{display:flex;flex-direction:column;gap:6px;color:var(--ztx-text-secondary);font-size:var(--ztx-font-size-body);font-weight:650}.tenant-auth-form input{height:38px;padding:0 10px;border:1px solid var(--ztx-border-strong);border-radius:5px;background:#fffefa;color:var(--ztx-text);font:inherit;font-size:var(--ztx-font-size-body)}.tenant-auth-form input:focus{outline:2px solid color-mix(in srgb,var(--ztx-primary) 20%,transparent);border-color:var(--ztx-primary)}
.tenant-auth-error{margin:0;padding:9px 10px;border-radius:5px;background:#f8e9e5;color:#934b3e;font-size:var(--ztx-font-size-caption)}
.tenant-auth-submit{height:40px;border:0;border-radius:5px;background:var(--ztx-primary);color:white;font-size:var(--ztx-font-size-body);font-weight:700;cursor:pointer}.tenant-auth-submit:disabled{opacity:.6;cursor:wait}
.tenant-auth-switch{margin-top:14px;padding:0;border:0;background:transparent;color:var(--ztx-primary-strong);font-size:var(--ztx-font-size-caption);cursor:pointer}
.tenant-auth-foot{margin-top:22px;padding-top:14px;border-top:1px solid var(--ztx-border);color:var(--ztx-muted);font-size:var(--ztx-font-size-caption);text-align:center}
.tenant-auth-platform-link{display:flex;justify-content:center;gap:6px;margin-top:14px;color:var(--ztx-muted);font-size:var(--ztx-font-size-caption)}
.tenant-auth-platform-link a{color:var(--ztx-primary-strong);text-decoration:none}.tenant-auth-platform-link a:hover{text-decoration:underline}
@media(max-width:520px){.tenant-auth-card{padding:24px 20px}}
</style>
