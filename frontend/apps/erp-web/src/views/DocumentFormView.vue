<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'
import { useWorkspaceStore } from '../stores/workspace'

type Field = {
  name: string; label?: string; type: string; required?: boolean; readOnly?: boolean
  defaultValue?: unknown; options?: string[]; childFields?: Field[]; loadOnDemand?: boolean
}
type Definition = { doctype: string; fields: Field[]; form?: { fields?: string[] } }
type DocumentData = Record<string, unknown>
const route = useRoute()
const router = useRouter()
const workspace = useWorkspaceStore()
const definition = ref<Definition | null>(null)
const document = reactive<DocumentData>({})
const jsonText = reactive<Record<string, string>>({})
const loadedDeferredFields = ref<Set<string>>(new Set())
const busy = ref(false)
const error = ref('')
const message = ref('')
const savedSnapshot = ref('')
const currentSnapshot = computed(() => JSON.stringify({ document, jsonText }))
const isDirty = computed(() => savedSnapshot.value !== '' && savedSnapshot.value !== currentSnapshot.value)
watch(isDirty, dirty => workspace.setDirty(route.fullPath, dirty), { immediate: true })

function restoreSnapshot() {
  if (!savedSnapshot.value) return
  const snapshot = JSON.parse(savedSnapshot.value) as { document: DocumentData; jsonText: Record<string, string> }
  Object.keys(document).forEach(key => { if (!(key in snapshot.document)) delete document[key] })
  Object.assign(document, snapshot.document)
  Object.keys(jsonText).forEach(key => { if (!(key in snapshot.jsonText)) delete jsonText[key] })
  Object.assign(jsonText, snapshot.jsonText)
}

onBeforeRouteLeave(() => {
  // Given 用户编辑了单据但尚未保存，When 切换工作区标签或离开表单，Then 必须明确确认而不能静默丢弃。
  if (!isDirty.value) return true
  if (!window.confirm('当前单据有未保存的修改，确定放弃这些修改吗？')) return false
  restoreSnapshot()
  workspace.setDirty(route.fullPath, false)
  return true
})
const doctype = computed(() => String(route.params.doctype ?? ''))
const documentId = computed(() => route.params.id ? String(route.params.id) : '')
const fields = computed(() => {
  const all = definition.value?.fields ?? []
  const layout = definition.value?.form?.fields
  return layout?.length ? layout.map(name => all.find(field => field.name === name)).filter((field): field is Field => !!field) : all
})
const docstatus = computed(() => Number(document.docstatus ?? 0))

async function request<T>(path: string, method = 'GET', payload?: unknown): Promise<T> {
  const response = await fetch(path, {
    method, credentials: 'include',
    headers: payload ? { 'content-type': 'application/json' } : undefined,
    body: payload ? JSON.stringify(payload) : undefined,
  })
  const data = await response.json().catch(() => null)
  if (!response.ok) throw new Error(data?.error?.message ?? '请求失败')
  return data as T
}

function initializeDefaults() {
  loadedDeferredFields.value.clear()
  for (const field of fields.value) {
    if (documentId.value && field.loadOnDemand) {
      delete document[field.name]
      continue
    }
    document[field.name] = field.defaultValue ?? (field.type === 'boolean' ? false : field.type === 'table' ? [] : '')
    if (field.loadOnDemand) loadedDeferredFields.value.add(field.name)
    if (field.type === 'json') jsonText[field.name] = JSON.stringify(document[field.name] ?? {}, null, 2)
  }
}

async function load() {
  if (!doctype.value) return
  busy.value = true
  error.value = ''
  try {
    loadedDeferredFields.value.clear()
    const meta = await request<{ definition: Definition }>(
      '/api/v1/tenant/runtime/doctypes/' + encodeURIComponent(doctype.value),
    )
    definition.value = meta.definition
    initializeDefaults()
    if (documentId.value) {
      const data = await request<DocumentData>(
        '/api/v1/tenant/documents/' + encodeURIComponent(doctype.value) + '/' + encodeURIComponent(documentId.value),
      )
      Object.assign(document, data)
      for (const field of fields.value.filter(item => item.type === 'json')) {
        jsonText[field.name] = JSON.stringify(document[field.name] ?? {}, null, 2)
      }
    }
    if (route.query.saved === '1') message.value = '单据已保存'
    savedSnapshot.value = currentSnapshot.value
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载单据失败'
  } finally {
    busy.value = false
  }
}

async function loadDeferredField(field: Field) {
  if (!documentId.value || loadedDeferredFields.value.has(field.name)) return
  const wasDirty = isDirty.value
  busy.value = true
  error.value = ''
  try {
    const result = await request<{ value: unknown }>(
      '/api/v1/tenant/documents/' + encodeURIComponent(doctype.value) + '/' +
      encodeURIComponent(documentId.value) + '/fields/' + encodeURIComponent(field.name),
    )
    document[field.name] = result.value
    if (field.type === 'json') jsonText[field.name] = JSON.stringify(result.value ?? {}, null, 2)
    loadedDeferredFields.value = new Set([...loadedDeferredFields.value, field.name])
    if (!wasDirty) savedSnapshot.value = currentSnapshot.value
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载字段失败'
  } finally {
    busy.value = false
  }
}

function inputValue(value: unknown): string {
  return value == null ? '' : String(value)
}
function setValue(name: string, value: string) {
  document[name] = value
}

function addDetailRow(field: Field) {
  const row: DocumentData = {}
  for (const child of field.childFields ?? []) row[child.name] = child.defaultValue ?? ''
  const rows = document[field.name]
  if (Array.isArray(rows)) rows.push(row)
}
function removeDetailRow(field: Field, index: number) {
  const rows = document[field.name]
  if (Array.isArray(rows)) rows.splice(index, 1)
}
function preparePayload() {
  const payload: DocumentData = {}
  for (const field of fields.value) {
    if (field.loadOnDemand && documentId.value && !loadedDeferredFields.value.has(field.name)) continue
    if (field.type === 'json') {
      try { payload[field.name] = JSON.parse(jsonText[field.name] || '{}') }
      catch { throw new Error('字段 ' + field.label + ' 的 JSON 格式不正确') }
    } else if (!field.readOnly) payload[field.name] = document[field.name]
  }
  if (documentId.value && Number.isInteger(document.version)) payload.version = document.version
  return payload
}

async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const path = '/api/v1/tenant/documents/' + encodeURIComponent(doctype.value) +
      (documentId.value ? '/' + encodeURIComponent(documentId.value) : '')
    const result = await request<DocumentData>(path, documentId.value ? 'PUT' : 'POST', preparePayload())
    Object.assign(document, result)
    savedSnapshot.value = currentSnapshot.value
    message.value = '单据已保存'
    if (!documentId.value && result.id) {
      const target = {
        path: '/developer/documents/' + encodeURIComponent(doctype.value) + '/' + encodeURIComponent(String(result.id)),
        query: { saved: '1' },
      }
      workspace.replaceTabPath(route.fullPath, router.resolve(target).fullPath)
      await router.replace(target)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败'
  } finally {
    busy.value = false
  }
}

async function runAction(action: 'submit' | 'cancel' | 'void') {
  if (!documentId.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await request<DocumentData>(
      '/api/v1/tenant/documents/' + encodeURIComponent(doctype.value) + '/' +
      encodeURIComponent(documentId.value) + '/actions/' + action,
      'POST', { version: document.version },
    )
    Object.assign(document, result)
    savedSnapshot.value = currentSnapshot.value
    message.value = action === 'submit' ? '单据已提交' : action === 'cancel' ? '单据已撤销' : '单据已作废'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '单据动作执行失败'
  } finally {
    busy.value = false
  }
}
watch([doctype, documentId], () => { void load() })
onMounted(() => {
  const protectUnload = (event: BeforeUnloadEvent) => {
    if (!isDirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  window.addEventListener('beforeunload', protectUnload)
  void load()
})
</script>

<template>
  <section class="mx-auto flex w-full max-w-[1180px] flex-col gap-3 text-ztx-text">
    <ZAlert v-if="error" tone="error" role="alert">{{ error }}</ZAlert>
    <ZAlert v-if="message" tone="success">{{ message }}</ZAlert>

    <div class="grid grid-cols-2 gap-x-4 gap-y-3 rounded-ztx-md border border-ztx-border bg-ztx-surface p-4 max-[760px]:grid-cols-1">
      <div class="col-span-2 flex items-center justify-between border-b border-ztx-border pb-2 max-[760px]:col-span-1">
        <h2 class="m-0 text-ztx-section-title font-semibold">基本信息</h2>
        <span class="text-ztx-caption text-ztx-muted">
          {{ docstatus === 0 ? '草稿' : docstatus === 1 ? '已提交' : docstatus === 2 ? '已撤销' : '已作废' }}
        </span>
      </div>
      <template v-for="field in fields" :key="field.name">
        <div v-if="field.loadOnDemand && documentId && !loadedDeferredFields.has(field.name)" class="col-span-2 flex items-center justify-between gap-3 rounded-ztx-sm border border-ztx-border border-dashed bg-ztx-surface-raised px-3 py-3 max-[760px]:col-span-1">
          <span class="text-ztx-caption text-ztx-muted">{{ field.label || field.name }} · 大字段尚未加载</span>
          <ZButton size="sm" variant="secondary" :disabled="busy" @click="loadDeferredField(field)">按需加载</ZButton>
        </div>
        <template v-else>
        <ZFormField v-if="field.type !== 'table' && field.type !== 'boolean' && field.type !== 'json' && field.type !== 'text' && field.type !== 'select'"
          :label="field.label || field.name" :field-id="'doc-' + field.name" :required="field.required">
          <ZInput :id="'doc-' + field.name" :model-value="inputValue(document[field.name])"
            @update:model-value="setValue(field.name, $event)"
            :type="field.type === 'date' ? 'date' : field.type === 'datetime' ? 'datetime-local' : field.type === 'time' ? 'time' : ['decimal','money','integer'].includes(field.type) ? 'number' : 'text'"
            :disabled="busy || field.readOnly || docstatus !== 0" :step="['decimal','money'].includes(field.type) ? 'any' : undefined" />
        </ZFormField>
        <ZFormField v-else-if="field.type === 'select'" :label="field.label || field.name" :field-id="'doc-' + field.name" :required="field.required">
          <select :id="'doc-' + field.name" :value="String(document[field.name] ?? '')" @change="setValue(field.name, ($event.target as HTMLSelectElement).value)" :disabled="busy || field.readOnly || docstatus !== 0"
            class="w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body">
            <option value="">请选择</option><option v-for="option in field.options ?? []" :key="option" :value="option">{{ option }}</option>
          </select>
        </ZFormField>
        <ZFormField v-else-if="field.type === 'text'" :label="field.label || field.name" :field-id="'doc-' + field.name" :required="field.required">
          <textarea :id="'doc-' + field.name" :value="String(document[field.name] ?? '')" @input="setValue(field.name, ($event.target as HTMLTextAreaElement).value)" :disabled="busy || field.readOnly || docstatus !== 0"
            class="min-h-24 w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body" />
        </ZFormField>
        <ZFormField v-else-if="field.type === 'json'" :label="field.label || field.name" :field-id="'doc-' + field.name">
          <textarea :id="'doc-' + field.name" v-model="jsonText[field.name]" :disabled="busy || field.readOnly || docstatus !== 0"
            class="min-h-28 w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 font-mono text-ztx-caption" />
        </ZFormField>
        <ZFormField v-else-if="field.type === 'boolean'" :label="field.label || field.name" :field-id="'doc-' + field.name">
          <label class="flex h-9 items-center gap-2 text-ztx-body">
            <input :id="'doc-' + field.name" v-model="document[field.name]" type="checkbox" :disabled="busy || field.readOnly || docstatus !== 0">
            是
          </label>
        </ZFormField>
        <div v-else class="col-span-2 rounded-ztx-sm border border-ztx-border p-3 max-[760px]:col-span-1">
          <div class="mb-2 flex items-center justify-between">
            <h2 class="m-0 text-ztx-section-title font-semibold">{{ field.label || field.name }}</h2>
            <ZButton size="sm" variant="secondary" :disabled="busy || field.readOnly || docstatus !== 0" @click="addDetailRow(field)">添加明细</ZButton>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full min-w-[560px] text-left text-ztx-body">
              <thead><tr class="border-b border-ztx-border text-ztx-caption text-ztx-muted">
                <th v-for="child in field.childFields ?? []" :key="child.name" class="px-2 py-2">{{ child.label || child.name }}</th><th class="w-16" />
              </tr></thead>
              <tbody>
                <tr v-for="(row,index) in document[field.name]" :key="index" class="border-b border-ztx-border">
                  <td v-for="child in field.childFields ?? []" :key="child.name" class="px-2 py-1.5">
                    <input v-model="row[child.name]" :type="['decimal','money','integer'].includes(child.type) ? 'number' : child.type === 'date' ? 'date' : child.type === 'time' ? 'time' : 'text'"
                      :disabled="busy || child.readOnly || field.readOnly || docstatus !== 0"
                      class="w-full min-w-24 rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2 py-1.5">
                  </td>
                  <td class="px-2"><button type="button" class="text-ztx-danger" :disabled="busy || field.readOnly || docstatus !== 0" @click="removeDetailRow(field, Number(index))">删除</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        </template>
      </template>
    </div>

    <footer class="sticky bottom-0 flex flex-wrap items-center justify-between gap-2 border-t border-ztx-border bg-ztx-surface/95 py-3 backdrop-blur">
      <span class="text-ztx-caption text-ztx-muted">保存与生命周期操作 · {{ documentId ? '单据 ' + documentId : '新建单据' }}</span>
      <div class="flex flex-wrap gap-2">
        <ZButton v-if="docstatus === 0" :disabled="busy" @click="save">保存</ZButton>
        <ZButton v-if="documentId && docstatus === 0" variant="secondary" :disabled="busy" @click="runAction('submit')">提交</ZButton>
        <ZButton v-if="documentId && docstatus === 0" variant="secondary" :disabled="busy" @click="runAction('void')">作废</ZButton>
        <ZButton v-if="documentId && docstatus === 1" variant="danger" :disabled="busy" @click="runAction('cancel')">撤销</ZButton>
      </div>
    </footer>
  </section>
</template>
