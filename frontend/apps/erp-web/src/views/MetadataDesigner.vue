<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'

type FieldType = 'string'|'text'|'uuid'|'date'|'datetime'|'time'|'decimal'|'integer'|'boolean'|'reference'|'select'|'money'|'json'|'table'
type MetadataField = {
  name: string
  label: string
  type: FieldType
  required: boolean
  readOnly: boolean
  unique: boolean
  target?: string
  options?: string[]
  childFields?: MetadataField[]
}
type Definition = {
  doctype: string
  scope: 'TENANT'
  table: { schema: string; name: string }
  fields: MetadataField[]
  form: { fields: string[] }
  list: { columns: string[]; filters: string[]; sort: string[]; search: string[] }
  permissions: Record<string, string[]>
}
type Diagnostic = { code: string; path: string; message: string }
type DefinitionRow = { doctype: string; version: number; status: string }

const fieldTypes: FieldType[] = ['string','text','uuid','date','datetime','time','decimal','integer','boolean','reference','select','money','json','table']
const rows = ref<DefinitionRow[]>([])
const selectedDoctype = ref('')
const version = ref<number|null>(null)
const status = ref('DRAFT')
const definition = reactive<Definition>({
  doctype: '',
  scope: 'TENANT',
  table: { schema: 'org', name: '' },
  fields: [],
  form: { fields: [] },
  list: { columns: [], filters: [], sort: [], search: [] },
  permissions: { read: [], create: [], update: [], delete: [] },
})
const diagnostics = ref<Diagnostic[]>([])
const busy = ref(false)
const error = ref('')
const message = ref('')
const canSave = computed(() => definition.doctype.trim().length > 0 && definition.table.name.trim().length > 0)

async function request<T>(path: string, method = 'GET', payload?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: 'include',
    headers: payload ? { 'content-type': 'application/json' } : undefined,
    body: payload ? JSON.stringify(payload) : undefined,
  })
  const data = await response.json().catch(() => null)
  if (!response.ok) throw new Error(data?.error?.message ?? '请求失败')
  return data as T
}

function blankDefinition(): Definition {
  return {
    doctype: '', scope: 'TENANT', table: { schema: 'org', name: '' }, fields: [],
    form: { fields: [] }, list: { columns: [], filters: [], sort: [], search: [] },
    permissions: { read: [], create: [], update: [], delete: [] },
  }
}

async function loadList() {
  const data = await request<{items: DefinitionRow[]}>('/api/v1/tenant/metadata/doctypes')
  rows.value = data.items
}

async function openDefinition(doctype: string) {
  busy.value = true
  error.value = ''
  diagnostics.value = []
  try {
    const data = await request<{version:number;status:string;definition:Definition}>(
      '/api/v1/tenant/metadata/doctypes/' + encodeURIComponent(doctype),
    )
    Object.assign(definition, blankDefinition(), data.definition)
    selectedDoctype.value = doctype
    version.value = data.version
    status.value = data.status
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取元数据失败'
  } finally {
    busy.value = false
  }
}

function createNew() {
  Object.assign(definition, blankDefinition())
  selectedDoctype.value = ''
  version.value = null
  status.value = 'DRAFT'
  diagnostics.value = []
  error.value = ''
  message.value = ''
}

function addField() {
  definition.fields.push({
    name: '', label: '', type: 'string', required: false, readOnly: false, unique: false,
  })
}

function addChildField(field: MetadataField) {
  field.childFields ??= []
  field.childFields.push({ name: '', label: '', type: 'string', required: false, readOnly: false, unique: false })
}

function removeField(index: number) {
  definition.fields.splice(index, 1)
}

function syncLayout() {
  const names = definition.fields.map(field => field.name).filter(Boolean)
  definition.form.fields = names
  definition.list.columns = names
  definition.list.filters = names
  definition.list.sort = names
  definition.list.search = names
}

async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  diagnostics.value = []
  try {
    syncLayout()
    const isNew = version.value === null
    const path = isNew
      ? '/api/v1/tenant/metadata/doctypes'
      : '/api/v1/tenant/metadata/doctypes/' + encodeURIComponent(definition.doctype)
    const data = await request<{doctype:string;version:number;status:string}>(path, isNew ? 'POST' : 'PUT', definition)
    selectedDoctype.value = data.doctype
    version.value = data.version
    status.value = data.status
    message.value = '草稿已保存'
    await loadList()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败'
  } finally {
    busy.value = false
  }
}

async function validate() {
  if (version.value === null) {
    error.value = '请先保存草稿，再执行校验'
    return
  }
  busy.value = true
  error.value = ''
  diagnostics.value = []
  try {
    const data = await request<{valid:boolean;status:string;diagnostics:Diagnostic[]}>(
      '/api/v1/tenant/metadata/doctypes/' + encodeURIComponent(definition.doctype) + '/validate',
      'POST',
      {},
    )
    diagnostics.value = data.diagnostics
    status.value = data.status
    message.value = data.valid ? '元数据校验通过' : '发现需要修正的问题'
    await loadList()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '校验失败'
  } finally {
    busy.value = false
  }
}

async function copyVersion() {
  if (!selectedDoctype.value) return
  busy.value = true
  error.value = ''
  try {
    const data = await request<{version:number;status:string}>(
      '/api/v1/tenant/metadata/doctypes/' + encodeURIComponent(selectedDoctype.value) + '/copy',
      'POST',
      {},
    )
    version.value = data.version
    status.value = data.status
    message.value = '已复制为新草稿版本'
    await loadList()
    await openDefinition(selectedDoctype.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '复制版本失败'
  } finally {
    busy.value = false
  }
}

onMounted(() => { void loadList().catch(cause => { error.value = cause instanceof Error ? cause.message : '读取定义列表失败' }) })
</script>

<template>
  <section class="mx-auto w-full max-w-[1280px] text-ztx-text">
    <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
      <div>
        <h1 class="m-0 text-ztx-page-title">DocType 元数据设计器</h1>
        <p class="mt-1 text-ztx-caption text-ztx-muted">管理租户级业务对象定义。平台核心元数据由平台控制面管理，租户不能修改。</p>
      </div>
      <div class="flex gap-2">
        <ZButton variant="secondary" :disabled="busy" @click="loadList">刷新</ZButton>
        <ZButton :disabled="busy" @click="createNew">新建定义</ZButton>
      </div>
    </div>
    <ZAlert v-if="message" class="mb-3" tone="success">{{ message }}</ZAlert>
    <ZAlert v-if="error" class="mb-3" tone="error" role="alert">{{ error }}</ZAlert>

    <div class="grid grid-cols-[260px_minmax(0,1fr)] gap-3 max-[850px]:grid-cols-1">
      <aside class="rounded-ztx-md border border-ztx-border bg-ztx-surface p-3">
        <h2 class="mb-2 text-ztx-section-title font-semibold">已有 DocType</h2>
        <p v-if="rows.length===0" class="text-ztx-caption text-ztx-muted">暂无定义</p>
        <button
          v-for="row in rows"
          :key="row.doctype"
          type="button"
          class="mb-1 flex w-full items-center justify-between rounded-ztx-sm px-2.5 py-2 text-left text-ztx-body hover:bg-ztx-surface-warm"
          :class="selectedDoctype===row.doctype ? 'bg-ztx-surface-warm text-ztx-primary' : 'text-ztx-text'"
          @click="openDefinition(row.doctype)"
        >
          <span class="min-w-0 truncate">{{ row.doctype }}</span>
          <span class="ml-2 shrink-0 text-ztx-caption text-ztx-muted">v{{ row.version }} · {{ row.status }}</span>
        </button>
      </aside>

      <main class="min-w-0 rounded-ztx-md border border-ztx-border bg-ztx-surface p-4">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 class="m-0 text-ztx-section-title font-semibold">{{ version===null ? '新建 DocType' : definition.doctype }}</h2>
            <p v-if="version!==null" class="mt-1 text-ztx-caption text-ztx-muted">版本 {{ version }} · {{ status }} · 租户级定义</p>
          </div>
          <div class="flex flex-wrap gap-2">
            <ZButton v-if="version!==null" variant="secondary" :disabled="busy" @click="copyVersion">复制版本</ZButton>
            <ZButton variant="secondary" :disabled="busy || version===null" @click="validate">校验</ZButton>
            <ZButton :disabled="busy || !canSave" @click="save">保存草稿</ZButton>
          </div>
        </div>

        <div class="grid grid-cols-3 gap-3 max-[850px]:grid-cols-1">
          <ZFormField label="DocType 标识" field-id="metadata-doctype" required>
            <ZInput id="metadata-doctype" v-model="definition.doctype" :disabled="version!==null" placeholder="例如 PurchaseOrder" />
          </ZFormField>
          <ZFormField label="数据库 Schema" field-id="metadata-schema" required>
            <ZInput id="metadata-schema" v-model="definition.table.schema" placeholder="例如 pur" />
          </ZFormField>
          <ZFormField label="物理表名" field-id="metadata-table" required>
            <ZInput id="metadata-table" v-model="definition.table.name" placeholder="例如 purchase_order" />
          </ZFormField>
        </div>

        <div class="mb-2 mt-5 flex items-center justify-between">
          <h3 class="m-0 text-ztx-section-title font-semibold">字段定义</h3>
          <ZButton size="sm" variant="secondary" @click="addField">添加字段</ZButton>
        </div>
        <div class="overflow-x-auto rounded-ztx-sm border border-ztx-border">
          <table class="w-full min-w-[760px] text-left text-ztx-body">
            <thead class="bg-ztx-surface-soft text-ztx-caption text-ztx-muted">
              <tr><th class="px-2 py-2">字段标识</th><th class="px-2 py-2">显示名称</th><th class="px-2 py-2">类型</th><th class="px-2 py-2">必填</th><th class="px-2 py-2">只读</th><th class="px-2 py-2">唯一</th><th class="px-2 py-2">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="(field,index) in definition.fields" :key="index" class="border-t border-ztx-border">
                <td class="p-1.5"><input v-model="field.name" class="w-full rounded border border-ztx-border bg-ztx-surface px-2 py-1.5" placeholder="field_name"></td>
                <td class="p-1.5"><input v-model="field.label" class="w-full rounded border border-ztx-border bg-ztx-surface px-2 py-1.5" placeholder="字段名称"></td>
                <td class="p-1.5">
                  <select v-model="field.type" class="w-full rounded border border-ztx-border bg-ztx-surface px-2 py-1.5">
                    <option v-for="type in fieldTypes" :key="type" :value="type">{{ type }}</option>
                  </select>
                </td>
                <td class="p-1.5 text-center"><input v-model="field.required" type="checkbox" aria-label="必填"></td>
                <td class="p-1.5 text-center"><input v-model="field.readOnly" type="checkbox" aria-label="只读"></td>
                <td class="p-1.5 text-center"><input v-model="field.unique" type="checkbox" aria-label="唯一"></td>
                <td class="p-1.5"><button type="button" class="text-ztx-danger hover:underline" @click="removeField(index)">删除</button></td>
              </tr>
              <tr v-for="field in definition.fields.filter(item => item.type === 'table')" :key="field.name + '-children'" class="bg-ztx-surface-soft">
                <td colspan="7" class="px-3 py-2">
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <span class="text-ztx-caption font-semibold text-ztx-muted">{{ field.label || field.name }} · 明细字段</span>
                    <button type="button" class="text-ztx-caption text-ztx-primary hover:underline" @click="addChildField(field)">添加子字段</button>
                  </div>
                  <div v-for="(child,index) in field.childFields ?? []" :key="index" class="mt-2 grid grid-cols-[1fr_1fr_1fr_auto] gap-2">
                    <input v-model="child.name" class="min-w-0 rounded border border-ztx-border bg-ztx-surface px-2 py-1.5 text-ztx-caption" placeholder="子字段标识">
                    <input v-model="child.label" class="min-w-0 rounded border border-ztx-border bg-ztx-surface px-2 py-1.5 text-ztx-caption" placeholder="显示名称">
                    <select v-model="child.type" class="min-w-0 rounded border border-ztx-border bg-ztx-surface px-2 py-1.5 text-ztx-caption">
                      <option v-for="type in fieldTypes.filter(item => item !== 'table')" :key="type" :value="type">{{ type }}</option>
                    </select>
                    <button type="button" class="text-ztx-danger" @click="field.childFields?.splice(index,1)">删除</button>
                  </div>
                </td>
              </tr>
              <tr v-if="definition.fields.length===0"><td colspan="7" class="px-3 py-7 text-center text-ztx-caption text-ztx-muted">尚未添加字段</td></tr>
            </tbody>
          </table>
        </div>

        <div v-if="diagnostics.length" class="mt-4 rounded-ztx-sm border border-ztx-danger/30 bg-ztx-danger-soft p-3">
          <h3 class="mb-2 text-ztx-body font-semibold text-ztx-danger">校验诊断</h3>
          <ul class="space-y-1 text-ztx-caption">
            <li v-for="item in diagnostics" :key="item.code+item.path">{{ item.path }} · {{ item.message }} <span class="text-ztx-muted">({{ item.code }})</span></li>
          </ul>
        </div>
      </main>
    </div>
  </section>
</template>
