<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'

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
 <main class="grid min-h-screen place-items-center bg-ztx-page px-4 py-8">
  <section class="w-full max-w-[440px] rounded-[9px] border border-ztx-border bg-ztx-surface px-8 py-[30px] shadow-[var(--ztx-shadow-popover)] max-[520px]:px-5 max-[520px]:py-6">
   <div class="mb-7 flex items-center gap-3">
    <span class="grid size-[38px] place-items-center rounded-ztx-md bg-ztx-primary text-[19px] font-extrabold text-white">Z</span>
    <strong class="text-[17px] tracking-[.12em]">ZTX</strong>
   </div>
   <div class="mb-6"><h1 class="text-ztx-page-title text-ztx-text">{{mode==='login'?'企业登录':'设置企业管理员账号'}}</h1></div>
   <form class="flex flex-col gap-[13px]" @submit.prevent="submit">
    <ZFormField label="企业编码" field-id="tenant-code" required><ZInput id="tenant-code" v-model="tenantCode" required minlength="2" maxlength="40" autocomplete="organization" placeholder="请输入企业编码"/></ZFormField>
    <ZFormField v-if="mode==='setup'" label="一次性初始化凭据" field-id="setup-token" required><ZInput id="setup-token" v-model="setupToken" required autocomplete="off" placeholder="由平台管理员提供"/></ZFormField>
    <ZFormField label="用户名" field-id="tenant-username" required><ZInput id="tenant-username" v-model="username" required minlength="3" maxlength="80" autocomplete="username" placeholder="请输入用户名"/></ZFormField>
    <ZFormField label="密码" field-id="tenant-password" required><ZInput id="tenant-password" v-model="password" required minlength="8" type="password" :autocomplete="mode==='login'?'current-password':'new-password'" placeholder="至少 8 位，包含字母和数字"/></ZFormField>
    <ZFormField v-if="mode==='setup'" label="确认密码" field-id="confirm-password" required><ZInput id="confirm-password" v-model="confirmPassword" required minlength="8" type="password" autocomplete="new-password"/></ZFormField>
    <ZAlert v-if="error" tone="error" role="alert">{{error}}</ZAlert>
    <ZButton class="mt-0.5 w-full" type="submit" size="lg" :disabled="busy">{{busy?'正在处理…':mode==='login'?'安全登录':'完成账号设置'}}</ZButton>
   </form>
   <ZButton class="mt-3 px-0" variant="ghost" size="sm" @click="mode=mode==='login'?'setup':'login';error=''">{{mode==='login'?'首次使用？设置企业管理员账号':'返回登录'}}</ZButton>
   <div class="mt-3.5 flex justify-center gap-1.5 text-ztx-caption text-ztx-muted"><span>平台运营人员？</span><RouterLink class="text-ztx-primary-strong no-underline hover:underline" to="/platform/login">平台管理登录</RouterLink></div>
  </section>
 </main>
</template>
