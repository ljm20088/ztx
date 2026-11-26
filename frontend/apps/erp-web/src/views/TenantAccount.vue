<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'
const router=useRouter()
const oldPassword=ref(''),newPassword=ref(''),confirmPassword=ref(''),message=ref(''),error=ref(''),busy=ref(false)
async function updatePassword(){
 message.value='';error.value='';busy.value=true
 if(newPassword.value!==confirmPassword.value){error.value='两次输入的新密码不一致';busy.value=false;return}
 try{
  const response=await fetch('/api/v1/tenant/auth/password',{method:'POST',credentials:'include',headers:{'content-type':'application/json'},body:JSON.stringify({oldPassword:oldPassword.value,newPassword:newPassword.value})})
  if(response.status===401){await router.replace('/tenant/login');throw new Error('登录会话已失效')}
  if(!response.ok){const data=await response.json().catch(()=>null);throw new Error(data?.error?.message??'密码更新失败')}
  oldPassword.value='';newPassword.value='';confirmPassword.value='';message.value='密码已更新，其他活动会话已撤销。'
 }catch(cause){error.value=cause instanceof Error?cause.message:'密码更新失败'}
 finally{busy.value=false}
}
</script>
<template>
 <section class="mx-auto w-full max-w-[480px] text-ztx-text">
  <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-[18px]">
   <h2 class="mb-[14px] text-ztx-section-title font-semibold">修改密码</h2>
   <form class="flex flex-col gap-3" @submit.prevent="updatePassword">
    <ZFormField label="当前密码" field-id="old-password" required><ZInput id="old-password" v-model="oldPassword" type="password" autocomplete="current-password" required/></ZFormField>
    <ZFormField label="新密码" field-id="new-password" required hint="至少 8 位，且同时包含字母和数字"><ZInput id="new-password" v-model="newPassword" type="password" autocomplete="new-password" minlength="8" required placeholder="至少 8 位，且同时包含字母和数字"/></ZFormField>
    <ZFormField label="确认新密码" field-id="confirm-password" required><ZInput id="confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" minlength="8" required placeholder="再次输入新密码"/></ZFormField>
    <ZAlert v-if="message" tone="success">{{message}}</ZAlert><ZAlert v-if="error" tone="error" role="alert">{{error}}</ZAlert>
    <ZButton class="self-start" type="submit" :disabled="busy">{{busy?'正在保存…':'更新密码'}}</ZButton>
   </form>
  </section>
 </section>
</template>
