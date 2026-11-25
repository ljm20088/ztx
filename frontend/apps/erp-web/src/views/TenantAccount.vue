<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
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
 <section class="tenant-account-page">
  <section class="account-panel"><h2>修改密码</h2><form @submit.prevent="updatePassword">
   <label>当前密码<input v-model="oldPassword" type="password" autocomplete="current-password" required/></label>
   <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" minlength="8" required placeholder="至少 8 位，且同时包含字母和数字"/><small>至少 8 位，且同时包含字母和数字</small></label>
   <label>确认新密码<input v-model="confirmPassword" type="password" autocomplete="new-password" minlength="8" required placeholder="再次输入新密码"/></label>
   <p v-if="message" class="message" role="status">{{message}}</p><p v-if="error" class="error" role="alert">{{error}}</p>
   <button type="submit" :disabled="busy">{{busy?'正在保存…':'更新密码'}}</button>
  </form></section>
 </section>
</template>
<style scoped>
.tenant-account-page{width:min(100%,480px);margin:0 auto;color:var(--ztx-text)}h2{font-size:var(--ztx-font-size-section-title);margin:0 0 14px}.account-panel{padding:18px;border:1px solid var(--ztx-border);border-radius:7px;background:var(--ztx-surface)}form{display:flex;flex-direction:column;gap:12px}label{display:flex;flex-direction:column;gap:6px;font-size:var(--ztx-font-size-body)}label small{color:var(--ztx-muted);font-size:var(--ztx-font-size-caption)}input{height:35px;padding:0 9px;border:1px solid var(--ztx-border-strong);border-radius:4px;background:#fffefa;font-size:var(--ztx-font-size-body)}button{height:34px;padding:0 12px;border:1px solid var(--ztx-primary);border-radius:5px;background:var(--ztx-primary);color:white;font-size:var(--ztx-font-size-body);cursor:pointer;align-self:flex-start}form>p{margin:0}.message,.error{font-size:var(--ztx-font-size-caption)}.message{color:#2d7250}.error{color:#a3473c}
</style>
