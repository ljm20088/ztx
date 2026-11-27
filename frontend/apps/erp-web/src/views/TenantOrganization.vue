<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput, ZDataGrid, type ColumnDef } from '@ztx/ui'

type Field = { key:string; label:string; hint?:string }
type RecordRow = Record<string,unknown>
const route=useRoute(),router=useRouter()
const resource=computed(()=>({
  companies:'companies',books:'books',departments:'organizations',employees:'employees',
  users:'users',employments:'employments',positions:'positions',roles:'roles',
  'group-master':'group-master','group-master-usage':'company-group-master','group-master-requests':'group-master-requests',
}[String(route.params.feature)]??''))
const pageTitle=computed(()=>({
  companies:'企业信息',books:'账套管理',departments:'组织架构',employees:'员工档案',
  users:'用户账号',employments:'员工任职',positions:'岗位管理',roles:'模块角色',
  'group-master':'集团基础档案','group-master-usage':'企业集团档案','group-master-requests':'集团档案审批',
}[String(route.params.feature)]??'组织与权限管理'))
const fieldSets:Record<string,Field[]> = {
  companies:[{key:'code',label:'企业编码'},{key:'name',label:'企业名称'},{key:'legalName',label:'法人名称'},{key:'taxNumber',label:'统一社会信用代码'}],
  books:[{key:'companyId',label:'所属企业 ID'},{key:'code',label:'账套编码'},{key:'name',label:'账套名称'},{key:'currency',label:'本位币',hint:'例如 CNY'}],
  organizations:[{key:'companyId',label:'所属企业 ID'},{key:'parentId',label:'上级组织 ID'},{key:'code',label:'组织编码'},{key:'name',label:'组织名称'},{key:'unitType',label:'组织类型',hint:'BRANCH / DIVISION / DEPARTMENT / FACTORY / WORKSHOP / TEAM / OTHER'}],
  employees:[{key:'employeeNo',label:'员工编号'},{key:'name',label:'员工姓名'},{key:'mobile',label:'手机'},{key:'email',label:'邮箱'}],
  users:[{key:'username',label:'登录账号'},{key:'displayName',label:'显示名称'},{key:'password',label:'初始密码',hint:'必填：至少 8 位且包含字母和数字'}],
  employments:[{key:'employeeId',label:'员工 ID'},{key:'userId',label:'关联用户 ID（可空）'},{key:'companyId',label:'所属企业 ID'},{key:'bookId',label:'所属账套 ID（可空）'},{key:'organizationUnitId',label:'所属组织 ID（可空）'},{key:'employmentType',label:'任职类型'},{key:'startsOn',label:'开始日期',hint:'YYYY-MM-DD'},{key:'endsOn',label:'结束日期（可空）'}],
  positions:[{key:'companyId',label:'所属企业 ID（可空表示通用岗位）'},{key:'bookId',label:'所属账套 ID（可空）'},{key:'organizationUnitId',label:'所属组织 ID（可空）'},{key:'code',label:'岗位编码'},{key:'name',label:'岗位名称'},{key:'description',label:'岗位说明'}],
  roles:[{key:'moduleKey',label:'模块标识'},{key:'code',label:'角色编码'},{key:'name',label:'角色名称'},{key:'description',label:'角色说明'}],
  'group-master':[{key:'category',label:'档案分类'},{key:'code',label:'集团编码'},{key:'name',label:'档案名称'},{key:'masterData',label:'集团主数据 JSON',hint:'例如 {"category":"原材料"}'},{key:'protectedFields',label:'集团控制字段',hint:'JSON 数组，例如 ["category","taxCode"]'}],
}
const fields=computed(()=>fieldSets[resource.value]??[])
const rows=ref<RecordRow[]>([])
const form=reactive<Record<string,string>>({})
const loading=ref(false),saving=ref(false),showCreateForm=ref(false),error=ref(''),message=ref('')
const companyId=ref(''),masterId=ref(''),requestId=ref(''),positionId=ref(''),roleIds=ref(''),employmentId=ref(''),positionIds=ref('')
const roleId=ref(''),permissionKey=ref(''),effect=ref('ALLOW'),dataScope=ref('SELF')
const fieldPermissions=ref('{}'),effectiveResult=ref(''),localDataJson=ref('{}')
const columns=computed(()=>{
  if(!rows.value.length)return []
  const preferred:Record<string,string[]> = {
    companies:['code','name','legal_name','tax_number','status'],
    books:['company_name','code','name','currency','status'],
    organizations:['code','name','unit_type','company_id','parent_id','status'],
    employees:['employee_no','name','mobile','email','status'],
    users:['username','display_name','status','last_login_at'],
    employments:['employee_no','employee_name','username','company_id','organization_unit_id','starts_on','ends_on','status'],
    positions:['code','name','company_id','description','status'],
    roles:['module_key','code','name','description','status'],
    'group-master':['category','code','name','master_data','protected_fields','status'],
    'group-master-usage':['id','master_item_id','company_name','category','code','master_name','status','local_data'],
    'group-master-requests':['id','company_name','category','code','master_name','status','requested_at'],
    'position-roles':['position_name','module_key','role_code','role_name'],
    'employment-positions':['employment_id','position_name'],
    'role-permissions':['module_key','role_code','permission_key','effect','data_scope','field_permissions'],
  }
  const names=preferred[resource.value]??Object.keys(rows.value[0]??{})
  return names.filter(key=>key in rows.value[0]!).map(key=>({key,label:({
    company_name:'所属企业',employee_no:'员工编号',employee_name:'员工姓名',username:'用户账号',
    unit_type:'组织类型',id:'记录 ID',master_item_id:'集团档案 ID',requested_at:'申请时间',local_data:'企业补充信息',company_id:'企业 ID',book_id:'账套 ID',parent_id:'上级组织 ID',organization_unit_id:'组织 ID',
    starts_on:'开始日期',ends_on:'结束日期',display_name:'显示名称',last_login_at:'最近登录',
    module_key:'模块',role_code:'角色编码',role_name:'角色名称',permission_key:'权限标识',
    data_scope:'数据范围',field_permissions:'字段权限',master_data:'集团主数据',protected_fields:'保护字段',
    master_name:'集团档案',local_data:'企业补充信息',position_name:'岗位名称',employment_id:'任职 ID',
    tax_number:'统一社会信用代码',legal_name:'法人名称',currency:'本位币',status:'状态',code:'编码',name:'名称',
    mobile:'手机',email:'邮箱',description:'说明',effect:'授权效果',category:'分类',
  } as Record<string,string>)[key]??key}))
})
const gridColumns=computed<ColumnDef<RecordRow, unknown>[]>(()=>columns.value.map(column=>({
  accessorKey:column.key,
  header:column.label,
  cell:info=>{
    const value=info.getValue()
    return typeof value==='object'?JSON.stringify(value):String(value??'—')
  },
})))
function resetForm(){Object.keys(form).forEach(key=>delete form[key]);fields.value.forEach(field=>form[field.key]='')}
async function api<T>(path:string,method='GET',payload?:unknown):Promise<T>{
  const response=await fetch(path,{method,credentials:'include',headers:payload?{'content-type':'application/json'}:undefined,body:payload?JSON.stringify(payload):undefined})
  if(response.status===401){await router.replace('/tenant/login');throw new Error('登录会话已失效')}
  if(response.status===204)return undefined as T
  const data=await response.json().catch(()=>null)
  if(!response.ok)throw new Error(data?.error?.message??'请求失败')
  return data as T
}
async function load(){
  if(!resource.value)return
  loading.value=true;error.value=''
  try{const data=await api<{items:RecordRow[]}>(`/api/v1/tenant/organization/${resource.value}`);rows.value=data.items}
  catch(cause){error.value=cause instanceof Error?cause.message:'读取数据失败'}
  finally{loading.value=false}
}
async function create(){
  saving.value=true;error.value='';message.value=''
  try{
    const payload:Record<string,unknown>={...form}
    if(payload.masterData)payload.masterData=JSON.parse(String(payload.masterData))
    if(payload.protectedFields)payload.protectedFields=JSON.parse(String(payload.protectedFields))
    await api(`/api/v1/tenant/organization/${resource.value}`,'POST',payload)
    message.value='保存成功';resetForm();showCreateForm.value=false;await load()
  }catch(cause){error.value=cause instanceof Error?cause.message:'保存失败'}
  finally{saving.value=false}
}
async function assignPositionRoles(){
  try{await api('/api/v1/tenant/organization/position-roles','POST',{positionId:positionId.value,roleIds:roleIds.value.split(',').map(x=>x.trim()).filter(Boolean)});message.value='岗位角色组合已保存';await load()}
  catch(cause){error.value=cause instanceof Error?cause.message:'岗位角色分配失败'}
}
async function assignEmploymentPositions(){
  try{await api('/api/v1/tenant/organization/employment-positions','POST',{employmentId:employmentId.value,positionIds:positionIds.value.split(',').map(x=>x.trim()).filter(Boolean)});message.value='任职岗位已保存';await load()}
  catch(cause){error.value=cause instanceof Error?cause.message:'任职岗位分配失败'}
}
async function savePermission(){
  try{await api('/api/v1/tenant/organization/role-permissions','POST',{roleId:roleId.value,permissionKey:permissionKey.value,effect:effect.value,dataScope:dataScope.value,fieldPermissions:JSON.parse(fieldPermissions.value)});message.value='角色权限已保存';await load()}
  catch(cause){error.value=cause instanceof Error?cause.message:'角色权限保存失败'}
}
async function inspectEffective(){
  try{const data=await api<Record<string,unknown>>(`/api/v1/tenant/organization/employments/${employmentId.value}/effective-permissions`);effectiveResult.value=JSON.stringify(data,null,2)}
  catch(cause){error.value=cause instanceof Error?cause.message:'读取有效权限失败'}
}
async function saveLocalData(id:string){
  try{await api('/api/v1/tenant/organization/company-group-master/'+id,'PUT',{companyId:companyId.value,localData:JSON.parse(localDataJson.value)});message.value='企业补充信息已保存';await load()}
  catch(cause){error.value=cause instanceof Error?cause.message:'企业补充信息保存失败'}
}
async function requestGroupMaster(id:string){
  try{await api(`/api/v1/tenant/organization/group-master/${id}/request`,'POST',{companyId:companyId.value});message.value='企业启用申请已提交'}
  catch(cause){error.value=cause instanceof Error?cause.message:'申请失败'}
}
async function approveGroupMaster(id:string){
  try{await api('/api/v1/tenant/organization/group-master-requests/'+id+'/approve','POST',{});message.value='集团档案申请已审批';await load()}
  catch(cause){error.value=cause instanceof Error?cause.message:'集团档案审批失败'}
}
async function enableGroupMaster(id:string){
  try{await api(`/api/v1/tenant/organization/group-master/${id}/enable`,'POST',{companyId:companyId.value});message.value='集团档案已在企业启用';await load()}
  catch(cause){error.value=cause instanceof Error?cause.message:'启用失败'}
}
watch(resource,()=>{showCreateForm.value=false;resetForm();void load()})
onMounted(()=>{resetForm();void load()})
</script>

<template>
  <section class="mx-auto w-full max-w-[1280px] text-ztx-text">
    <div class="mb-[14px] flex items-center justify-end gap-2">
      <ZButton v-if="fields.length" variant="secondary" @click="showCreateForm=!showCreateForm">{{showCreateForm?'收起新增':'新增'}}</ZButton>
      <ZButton variant="secondary" :disabled="loading" @click="load">刷新</ZButton>
    </div>
    <ZAlert v-if="message" class="mb-3" tone="success">{{message}}</ZAlert>
    <ZAlert v-if="error" class="mb-3" tone="error" role="alert">{{error}}</ZAlert>

    <section v-if="fields.length && showCreateForm" class="mb-[14px rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <h2 class="mb-3 text-ztx-section-title font-semibold">新增{{pageTitle}}</h2>
      <form class="grid grid-cols-3 items-end gap-3 max-[900px]:grid-cols-2 max-[640px]:grid-cols-1" @submit.prevent="create">
        <ZFormField v-for="field in fields" :key="field.key" :label="field.label" :field-id="'org-'+field.key">
          <ZInput :id="'org-'+field.key" v-model="form[field.key]" :placeholder="field.hint??field.label" :type="field.key==='password'?'password':'text'" :required="field.key==='password'"/>
        </ZFormField>
        <div class="flex justify-end"><ZButton type="submit" :disabled="saving">{{saving?'保存中':'保存'}}</ZButton></div>
      </form>
    </section>

    <section v-if="resource==='group-master'||resource==='group-master-usage'" class="mb-[14px] rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <h2 class="mb-2 text-ztx-section-title font-semibold">企业启用范围</h2>
      <div class="flex flex-wrap items-end gap-2">
        <ZFormField label="企业 ID" field-id="group-company-id"><ZInput id="group-company-id" v-model="companyId" placeholder="输入企业 UUID"/></ZFormField>
        <ZFormField label="集团档案 ID" field-id="group-master-id"><ZInput id="group-master-id" v-model="masterId" placeholder="输入集团档案 UUID"/></ZFormField>
      </div>
      <div v-if="resource==='group-master-usage'" class="mt-2 flex flex-wrap items-end gap-2">
        <ZFormField label="企业补充信息 JSON" field-id="company-local-data"><ZInput id="company-local-data" v-model="localDataJson" placeholder="填写本地补充字段 JSON"/></ZFormField>
        <ZButton @click="saveLocalData(masterId)">保存企业补充</ZButton>
      </div>
      <div v-if="resource==='group-master'" class="mt-2 flex flex-wrap gap-2">
        <ZButton variant="secondary" @click="requestGroupMaster(masterId)">申请企业启用</ZButton>
        <ZButton variant="secondary" @click="enableGroupMaster(masterId)">集团管理员直接启用</ZButton>
      </div>
      <p class="mt-1 text-ztx-caption text-ztx-muted">企业补充信息与集团控制字段分开保存，集团控制字段不可被企业覆盖。</p>
    </section>

    <section v-if="resource==='group-master-requests'" class="mb-[14px] rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <h2 class="mb-2 text-ztx-section-title font-semibold">审批集团档案申请</h2>
      <div class="flex flex-wrap items-end gap-2">
        <ZFormField label="申请 ID" field-id="group-request-id"><ZInput id="group-request-id" v-model="requestId" placeholder="输入待审批申请 UUID"/></ZFormField>
        <ZButton @click="approveGroupMaster(requestId)">批准申请</ZButton>
      </div>
    </section>

    <section v-if="resource==='positions'" class="mb-[14px] rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <h2 class="mb-3 text-ztx-section-title font-semibold">岗位组合模块角色</h2>
      <div class="grid grid-cols-[1fr_2fr_auto] items-end gap-2 max-[700px]:grid-cols-1">
        <ZFormField label="岗位 ID" field-id="position-id"><ZInput id="position-id" v-model="positionId"/></ZFormField>
        <ZFormField label="模块角色 ID（多个用逗号分隔）" field-id="role-ids"><ZInput id="role-ids" v-model="roleIds"/></ZFormField>
        <ZButton @click="assignPositionRoles">保存岗位组合</ZButton>
      </div>
    </section>

    <section v-if="resource==='employments'" class="mb-[14px] rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <h2 class="mb-3 text-ztx-section-title font-semibold">任职岗位与有效权限</h2>
      <div class="grid grid-cols-[1fr_2fr_auto] items-end gap-2 max-[700px]:grid-cols-1">
        <ZFormField label="任职 ID" field-id="employment-id"><ZInput id="employment-id" v-model="employmentId"/></ZFormField>
        <ZFormField label="岗位 ID（多个用逗号分隔）" field-id="employment-position-ids"><ZInput id="employment-position-ids" v-model="positionIds"/></ZFormField>
        <ZButton @click="assignEmploymentPositions">分配岗位</ZButton>
      </div>
      <div class="mt-3 flex flex-wrap items-center gap-2"><ZButton variant="secondary" @click="inspectEffective">查看合并权限</ZButton><pre v-if="effectiveResult" class="max-h-64 w-full overflow-auto rounded bg-ztx-page p-3 text-xs">{{effectiveResult}}</pre></div>
    </section>

    <section v-if="resource==='roles'" class="mb-[14px] rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <h2 class="mb-3 text-ztx-section-title font-semibold">角色功能、数据和字段权限</h2>
      <div class="grid grid-cols-3 items-end gap-2 max-[800px]:grid-cols-2 max-[600px]:grid-cols-1">
        <ZFormField label="角色 ID" field-id="permission-role-id"><ZInput id="permission-role-id" v-model="roleId"/></ZFormField>
        <ZFormField label="权限标识" field-id="permission-key"><ZInput id="permission-key" v-model="permissionKey" placeholder="例如 purchase.order.approve"/></ZFormField>
        <ZFormField label="授权效果" field-id="permission-effect"><ZInput id="permission-effect" v-model="effect" placeholder="ALLOW / DENY"/></ZFormField>
        <ZFormField label="数据范围" field-id="permission-scope"><ZInput id="permission-scope" v-model="dataScope" placeholder="SELF / UNIT / SUBTREE / BOOK / COMPANY / TENANT"/></ZFormField>
        <ZFormField label="字段权限 JSON" field-id="permission-fields"><ZInput id="permission-fields" v-model="fieldPermissions" placeholder='{"amount":"READ_ONLY","supplier":"MASKED"}'/></ZFormField>
        <ZButton @click="savePermission">保存角色权限</ZButton>
      </div>
    </section>

    <section class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
      <div class="mb-2 flex items-center justify-between"><h2 class="m-0 text-ztx-section-title font-semibold">{{pageTitle}}</h2><span class="text-ztx-caption text-ztx-muted">共 {{rows.length}} 条</span></div>
      <ZDataGrid :data="rows" :columns="gridColumns" :searchable="true" />
    </section>
  </section>
</template>
