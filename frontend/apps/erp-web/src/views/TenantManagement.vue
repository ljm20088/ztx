<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput, ZTable } from '@ztx/ui'

type Tenant = { id: string; code: string; name: string; status: 'PENDING'|'PROVISIONING'|'ACTIVE'|'INACTIVE'|'FAILED'; databaseName: string; lastError: string|null }
const router=useRouter()
const tenants=ref<Tenant[]>([])
const code=ref('')
const name=ref('')
const setupToken=ref('')
const message=ref('')
const error=ref('')
const busy=ref(false)

async function copySetupToken(){
 try{await navigator.clipboard.writeText(setupToken.value);message.value='初始化凭据已复制'}
 catch{error.value='无法访问剪贴板，请手动复制凭据'}
}

async function request(path:string,method='GET',payload?:unknown){
 const response=await fetch(path,{method,credentials:'include',headers:payload?{'content-type':'application/json'}:undefined,body:payload?JSON.stringify(payload):undefined})
 if(response.status===401){await router.replace('/platform/login');throw new Error('登录会话已失效')}
 if(!response.ok){const data=await response.json().catch(()=>null);throw new Error(data?.error?.message??'操作失败')}
 return response.status===204?null:response.json()
}
async function load(){const data=await request('/api/v1/platform/tenants');tenants.value=data.items}
async function createTenant(){
 busy.value=true;error.value='';message.value='';setupToken.value=''
 try{const result=await request('/api/v1/platform/tenants','POST',{code:code.value.trim(),name:name.value.trim()});setupToken.value=result.setupToken??'';message.value=result.tenant.status==='ACTIVE'?'租户开通成功':'租户已存在，当前状态：'+result.tenant.status;code.value='';name.value='';await load()}
 catch(cause){error.value=cause instanceof Error?cause.message:'租户开通失败';await load().catch(()=>undefined)}
 finally{busy.value=false}
}
async function act(tenant:Tenant,action:'enable'|'disable'|'retry'){
 busy.value=true;error.value='';message.value='';setupToken.value=''
 try{const result=await request('/api/v1/platform/tenants/'+tenant.id+'/'+action,'POST');setupToken.value=result.setupToken??'';message.value='操作成功';await load()}
 catch(cause){error.value=cause instanceof Error?cause.message:'操作失败'}
 finally{busy.value=false}
}
function statusLabel(status:Tenant['status']){return ({PENDING:'待开通',PROVISIONING:'开通中',ACTIVE:'已启用',INACTIVE:'已停用',FAILED:'开通失败'})[status]}
onMounted(()=>{void load().catch(cause=>{error.value=cause instanceof Error?cause.message:'无法读取租户列表'})})
</script>

<template>
 <section class="tenant-page">
  <header class="tenant-heading"><div><h1>租户管理</h1><p>由平台管理员创建和管理租户；每个租户使用独立 PostgreSQL 数据库。</p></div><ZButton variant="secondary" @click="router.push('/platform/admin')">返回平台控制台</ZButton></header>
  <ZAlert v-if="message" tone="success">{{message}}</ZAlert><ZAlert v-if="error" tone="error" role="alert">{{error}}</ZAlert>
  <section class="panel">
   <h2>开通租户</h2>
   <form class="create-form" @submit.prevent="createTenant">
    <ZFormField label="租户编码" field-id="tenant-code" required><ZInput id="tenant-code" v-model="code" required minlength="2" maxlength="40" pattern="[a-z][a-z0-9_-]+" placeholder="例如：acme"/></ZFormField>
    <ZFormField label="租户名称" field-id="tenant-name" required><ZInput id="tenant-name" v-model="name" required minlength="2" maxlength="160" placeholder="企业或客户名称"/></ZFormField>
    <ZButton type="submit" :disabled="busy">创建并开通</ZButton>
   </form>
   <p class="hint">开通会创建独立数据库并执行初始化迁移。开通成功后会显示一次性租户管理员初始化凭据，请及时交付给租户管理员。</p>
   <div v-if="setupToken" class="token-box"><strong>租户管理员一次性初始化凭据（24 小时有效）</strong><code>{{setupToken}}</code><ZButton variant="secondary" @click="copySetupToken">复制凭据</ZButton></div>
  </section>
  <section class="panel">
   <div class="list-heading"><h2>租户列表</h2><ZButton variant="secondary" :disabled="busy" @click="load">刷新</ZButton></div>
   <ZTable><thead><tr><th>租户编码</th><th>租户名称</th><th>状态</th><th>数据库</th><th>操作</th></tr></thead>
    <tbody><tr v-for="tenant in tenants" :key="tenant.id"><td>{{tenant.code}}</td><td>{{tenant.name}}</td><td><span :class="['status','status-'+tenant.status.toLowerCase()]">{{statusLabel(tenant.status)}}</span><small v-if="tenant.lastError" class="failure">{{tenant.lastError}}</small></td><td><code>{{tenant.databaseName}}</code></td><td class="actions">
     <ZButton v-if="tenant.status==='ACTIVE'" variant="ghost" size="sm" :disabled="busy" @click="act(tenant,'disable')">停用</ZButton>
     <ZButton v-if="tenant.status==='INACTIVE'" variant="ghost" size="sm" :disabled="busy" @click="act(tenant,'enable')">启用</ZButton>
     <ZButton v-if="tenant.status==='FAILED'||tenant.status==='PROVISIONING'" variant="ghost" size="sm" :disabled="busy" @click="act(tenant,'retry')">重试开通</ZButton>
    </td></tr><tr v-if="tenants.length===0"><td colspan="5" class="empty">暂无租户</td></tr></tbody>
   </ZTable>
  </section>
 </section>
</template>

<style scoped>
.tenant-page{width:min(100%,1180px);margin:0 auto;color:var(--ztx-text)}.tenant-heading,.list-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}.tenant-heading h1{margin:0;font-size:var(--ztx-font-size-page-title)}.tenant-heading p,.hint{margin:6px 0 0;color:var(--ztx-muted);font-size:var(--ztx-font-size-caption)}.panel{margin-bottom:14px;padding:16px;border:1px solid var(--ztx-border);border-radius:7px;background:var(--ztx-surface)}h2{margin:0 0 13px;font-size:var(--ztx-font-size-section-title)}.create-form{display:grid;grid-template-columns:1fr 1.5fr auto;align-items:end;gap:10px}.create-form>.ztx-form-field{min-width:0}.hint{line-height:1.6}.token-box{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:12px;padding:10px;background:var(--ztx-surface-warm);font-size:var(--ztx-font-size-caption)}.token-box code{overflow-wrap:anywhere}.list-heading{margin-bottom:8px}.list-heading h2{margin:0}.status{display:inline-block;padding:2px 7px;border-radius:4px;background:var(--ztx-surface-warm)}.status-active{color:#2d7250}.status-failed{color:#a3473c}.status-inactive{color:var(--ztx-muted)}.failure{display:block;max-width:250px;margin-top:4px;color:#a3473c;font-size:11px}.actions{white-space:nowrap}.empty{text-align:center;color:var(--ztx-muted)}.tenant-page> .ztx-alert{margin-bottom:12px}@media(max-width:760px){.create-form{grid-template-columns:1fr}.tenant-heading{align-items:flex-start}}
</style>
