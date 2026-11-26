<script setup lang="ts" generic="TData extends Record<string, unknown>">
import { computed, ref } from 'vue'
import {
  FlexRender,
  functionalUpdate,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useVueTable,
  type ColumnDef,
  type RowSelectionState,
  type SortingState,
} from '@tanstack/vue-table'

const props = withDefaults(defineProps<{
  data: TData[]
  columns: ColumnDef<TData, unknown>[]
  pageSize?: number
  searchable?: boolean
  searchPlaceholder?: string
}>(), {
  pageSize: 20,
  searchable: true,
  searchPlaceholder: '搜索当前列表',
})

const sorting = ref<SortingState>([])
const globalFilter = ref('')
const rowSelection = ref<RowSelectionState>({})
const table = useVueTable({
  get data() { return props.data },
  get columns() { return props.columns },
  state: {
    get sorting() { return sorting.value },
    get globalFilter() { return globalFilter.value },
    get rowSelection() { return rowSelection.value },
  },
  onSortingChange: updater => { sorting.value = functionalUpdate(updater, sorting.value) },
  onGlobalFilterChange: updater => { globalFilter.value = String(functionalUpdate(updater, globalFilter.value) ?? '') },
  onRowSelectionChange: updater => { rowSelection.value = functionalUpdate(updater, rowSelection.value) },
  enableRowSelection: true,
  getCoreRowModel: getCoreRowModel(),
  getFilteredRowModel: getFilteredRowModel(),
  getSortedRowModel: getSortedRowModel(),
  getPaginationRowModel: getPaginationRowModel(),
  initialState: { pagination: { pageSize: props.pageSize } },
})

const filteredCount = computed(() => table.getFilteredRowModel().rows.length)
const selectedCount = computed(() => table.getSelectedRowModel().rows.length)

function toggleRow(rowId: string, event: Event) {
  table.getRow(rowId)?.toggleSelected((event.target as HTMLInputElement).checked)
}
function togglePage(event: Event) {
  table.toggleAllPageRowsSelected((event.target as HTMLInputElement).checked)
}
</script>

<template>
  <section class="flex min-w-0 flex-col gap-3 text-ztx-body text-ztx-text">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <label v-if="searchable" class="flex min-w-48 items-center gap-2 rounded-ztx-sm border border-ztx-border bg-ztx-surface px-2.5 py-1.5 text-ztx-muted focus-within:border-ztx-primary">
        <span class="sr-only">搜索列表</span>
        <input
          :value="globalFilter"
          :placeholder="searchPlaceholder"
          class="w-full min-w-0 bg-transparent text-ztx-text outline-none placeholder:text-ztx-muted"
          @input="table.setGlobalFilter(($event.target as HTMLInputElement).value)"
        >
      </label>
      <div class="ml-auto flex items-center gap-3 text-ztx-caption text-ztx-muted" aria-live="polite">
        <span>共 {{ filteredCount }} 条</span>
        <span>已选 {{ selectedCount }} 条</span>
      </div>
    </div>

    <div class="min-w-0 overflow-x-auto rounded-ztx-md border border-ztx-border bg-ztx-surface">
      <table class="w-full border-collapse text-left text-ztx-body">
        <thead class="bg-ztx-surface-soft text-ztx-caption font-semibold text-ztx-muted">
          <tr v-for="headerGroup in table.getHeaderGroups()" :key="headerGroup.id" class="h-9 border-b border-ztx-border">
            <th class="w-9 px-3">
              <input
                type="checkbox"
                aria-label="选择当前页全部行"
                :checked="table.getIsAllPageRowsSelected()"
                :aria-checked="table.getIsSomePageRowsSelected() ? 'mixed' : table.getIsAllPageRowsSelected()"
                @change="togglePage"
              >
            </th>
            <th v-for="header in headerGroup.headers" :key="header.id" class="whitespace-nowrap px-3 py-2">
              <button
                v-if="header.column.getCanSort()"
                type="button"
                class="inline-flex items-center gap-1 hover:text-ztx-primary"
                @click="header.column.getToggleSortingHandler()?.($event)"
              >
                <FlexRender :render="header.column.columnDef.header" :props="header.getContext()" />
                <span aria-hidden="true">{{ header.column.getIsSorted() === 'asc' ? '↑' : header.column.getIsSorted() === 'desc' ? '↓' : '↕' }}</span>
              </button>
              <FlexRender v-else :render="header.column.columnDef.header" :props="header.getContext()" />
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in table.getRowModel().rows" :key="row.id" class="h-9 border-b border-ztx-border last:border-b-0 hover:bg-ztx-surface-warm">
            <td class="px-3 py-1.5">
              <input
                type="checkbox"
                :aria-label="'选择第 ' + (row.index + 1) + ' 行'"
                :checked="row.getIsSelected()"
                @change="toggleRow(row.id, $event)"
              >
            </td>
            <td v-for="cell in row.getVisibleCells()" :key="cell.id" class="px-3 py-1.5">
              <FlexRender :render="cell.column.columnDef.cell" :props="cell.getContext()" />
            </td>
          </tr>
          <tr v-if="table.getRowModel().rows.length === 0">
            <td :colspan="table.getVisibleLeafColumns().length + 1" class="px-3 py-10 text-center text-ztx-caption text-ztx-muted">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="flex items-center justify-end gap-2">
      <button
        type="button"
        class="rounded-ztx-sm border border-ztx-border px-2.5 py-1.5 text-ztx-caption text-ztx-text-secondary hover:bg-ztx-surface-soft disabled:cursor-not-allowed disabled:opacity-40"
        :disabled="!table.getCanPreviousPage()"
        @click="table.previousPage()"
      >上一页</button>
      <span class="min-w-16 text-center text-ztx-caption tabular-nums text-ztx-muted">{{ table.getState().pagination.pageIndex + 1 }} / {{ Math.max(table.getPageCount(), 1) }}</span>
      <button
        type="button"
        class="rounded-ztx-sm border border-ztx-border px-2.5 py-1.5 text-ztx-caption text-ztx-text-secondary hover:bg-ztx-surface-soft disabled:cursor-not-allowed disabled:opacity-40"
        :disabled="!table.getCanNextPage()"
        @click="table.nextPage()"
      >下一页</button>
    </div>
  </section>
</template>
