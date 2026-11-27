<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZButton, ZDataGrid } from '@ztx/ui'

type Field = { name: string; label?: string; type: string }
type Definition = { doctype: string; fields: Field[]; list?: { columns?: string[]; filters?: Array<string | { field: string }>; sort?: Array<string | { field: string }>; search?: string[] } }
type Row = Record<string, unknown> & { id?: string }
const route = useRoute()
const router = useRouter()
const definition = ref<Definition | null>(null)
const rows = ref<Row[]>([])
const page = ref(1)
const pageSize = 20
const total = ref(0)
const search = ref('')
const filterField = ref('')
const filterValue = ref('')
const sortField = ref('')
const sortDirection = ref<'asc' | 'desc'>('asc')
const busy = ref(false)
const error = ref('')
const selectedCount = ref(0)
const filtersOpen = ref(false)
const sortOpen = ref(false)
const doctype = computed(() => String(route.params.doctype ?? ''))

const visibleFields = computed(() => {
  const fields = definition.value?.fields ?? []
  const names = definition.value?.list?.columns
  return names?.length ? fields.filter(field => names.includes(field.name)) : fields
})
const columns = computed(() => visibleFields.value.map(field => ({
  accessorKey: field.name,
  header: field.label || field.name,
})))

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

async function load() {
  if (!doctype.value) return
  busy.value = true
  error.value = ''
  try {
    const meta = await request<{ definition: Definition }>(
      '/api/v1/tenant/runtime/doctypes/' + encodeURIComponent(doctype.value),
    )
    definition.value = meta.definition
    const allowedSort = definition.value.list?.sort ?? []
    if (!sortField.value) sortField.value = typeof allowedSort[0] === 'string' ? allowedSort[0] : ''
    const filter: Record<string, unknown> | undefined = filterField.value && filterValue.value
      ? { field: filterField.value, operator: 'contains', value: filterValue.value }
      : undefined
    const result = await request<{ items: Row[]; total: number }>(
      '/api/v1/tenant/query/' + encodeURIComponent(doctype.value),
      'POST',
      {
        page: page.value,
        pageSize,
        search: search.value || undefined,
        filter,
        sort: sortField.value ? [{ field: sortField.value, direction: sortDirection.value }] : [],
      },
    )
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载列表失败'
  } finally {
    busy.value = false
  }
}

function searchNow() {
  page.value = 1
  void load()
}
function previousPage() {
  if (page.value > 1) { page.value--; void load() }
}
function nextPage() {
  if (page.value * pageSize < total.value) { page.value++; void load() }
}
function openDocument(row: Row) {
  if (!row.id) return
  void router.push('/developer/documents/' + encodeURIComponent(doctype.value) + '/' + encodeURIComponent(row.id))
}
watch(doctype, () => { page.value = 1; void load() })
// Given 列表 Tab 从表单重新激活，When 页面恢复，Then 保留本地筛选/排序/分页状态并刷新最新查询结果。
onActivated(() => { void load() })
</script>

<template>
  <section class="mx-auto flex w-full max-w-[1440px] flex-col gap-3 text-ztx-text">
    <header class="flex flex-wrap items-center justify-between gap-2">
      <span class="text-ztx-caption font-semibold text-ztx-text">{{ doctype }}</span>
      <div class="ml-auto flex flex-wrap items-center gap-1.5">
        <ZButton variant="secondary" size="sm" :disabled="busy" @click="filtersOpen = !filtersOpen">筛选</ZButton>
        <ZButton variant="secondary" size="sm" :disabled="busy" @click="sortOpen = !sortOpen">排序</ZButton>
        <ZButton variant="secondary" size="sm" :disabled="busy" @click="load">刷新</ZButton>
        <RouterLink :to="'/developer/documents/' + encodeURIComponent(doctype)">
          <ZButton size="sm">新建</ZButton>
        </RouterLink>
      </div>
    </header>

    <p v-if="error" class="rounded-ztx-sm border border-ztx-danger/30 bg-ztx-danger/5 px-3 py-2 text-ztx-caption text-ztx-danger" role="alert">{{ error }}</p>

    <div v-if="filtersOpen" class="flex flex-wrap items-end gap-2 rounded-ztx-md border border-ztx-border bg-ztx-surface p-3">
      <label class="min-w-48 flex-1 text-ztx-caption text-ztx-muted">
        搜索
        <input v-model="search" class="mt-1 w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text" placeholder="搜索列表字段" @keyup.enter="searchNow">
      </label>
      <label class="min-w-36 text-ztx-caption text-ztx-muted">
        筛选字段
        <select v-model="filterField" class="mt-1 w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
          <option value="">不筛选</option>
          <option v-for="field in definition?.list?.filters ?? []" :key="typeof field === 'string' ? field : field.field" :value="typeof field === 'string' ? field : field.field">
            {{ typeof field === 'string' ? field : field.field }}
          </option>
        </select>
      </label>
      <label class="min-w-36 text-ztx-caption text-ztx-muted">
        筛选值
        <input v-model="filterValue" class="mt-1 w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text" @keyup.enter="searchNow">
      </label>
      <ZButton size="sm" :disabled="busy" @click="searchNow">应用筛选</ZButton>
    </div>

    <div v-if="sortOpen" class="flex flex-wrap items-end gap-2 rounded-ztx-md border border-ztx-border bg-ztx-surface p-3">
      <label class="min-w-36 text-ztx-caption text-ztx-muted">
        排序字段
        <select v-model="sortField" class="mt-1 w-full rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
          <option value="">默认</option>
          <option v-for="field in definition?.list?.sort ?? []" :key="typeof field === 'string' ? field : field.field" :value="typeof field === 'string' ? field : field.field">
            {{ typeof field === 'string' ? field : field.field }}
          </option>
        </select>
      </label>
      <label class="text-ztx-caption text-ztx-muted">
        顺序
        <select v-model="sortDirection" class="mt-1 rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-2 text-ztx-body text-ztx-text">
          <option value="asc">升序</option><option value="desc">降序</option>
        </select>
      </label>
      <ZButton size="sm" :disabled="busy" @click="searchNow">应用排序</ZButton>
    </div>

    <ZDataGrid :data="rows" :columns="columns" :page-size="200" :searchable="false" :show-pagination="false" :show-summary="false" @selection-change="selectedCount = $event" @row-activate="openDocument" />

    <footer class="flex items-center justify-between gap-3 text-ztx-caption text-ztx-muted">
      <span aria-live="polite">共 {{ total }} 条 · 已选 {{ selectedCount }} 条 · 第 {{ page }} 页 · 每页 {{ pageSize }} 条</span>
      <div class="flex gap-2">
        <ZButton variant="secondary" size="sm" :disabled="busy || page <= 1" @click="previousPage">上一页</ZButton>
        <ZButton variant="secondary" size="sm" :disabled="busy || page * pageSize >= total" @click="nextPage">下一页</ZButton>
      </div>
    </footer>
  </section>
</template>
