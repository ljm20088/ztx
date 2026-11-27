import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export interface WorkspaceTab { path: string; title: string; closable: boolean; dirty?: boolean }
type WorkspaceMode = 'tenant' | 'platform'

export const useWorkspaceStore = defineStore('workspace', () => {
  const mode = ref<WorkspaceMode>('tenant')
  const tenantTabs = ref<WorkspaceTab[]>([{ path: '/workspace', title: '工作台', closable: false }])
  const platformTabs = ref<WorkspaceTab[]>([{ path: '/platform/admin', title: '平台概览', closable: false }])
  const tenantActivePath = ref('/workspace')
  const platformActivePath = ref('/platform/admin')
  const tabs = computed(() => mode.value === 'platform' ? platformTabs.value : tenantTabs.value)
  const activePath = computed(() => mode.value === 'platform' ? platformActivePath.value : tenantActivePath.value)

  function setMode(next: WorkspaceMode) {
    mode.value = next
  }

  function openTab(tab: WorkspaceTab) {
    const currentTabs = tabs.value
    if (!currentTabs.some(item => item.path === tab.path)) currentTabs.push(tab)
    if (mode.value === 'platform') platformActivePath.value = tab.path
    else tenantActivePath.value = tab.path
  }

  function setDirty(path: string, dirty: boolean) {
    const tab = tabs.value.find(item => item.path === path)
    if (tab) tab.dirty = dirty
  }

  function activateTab(path: string) {
    if (tabs.value.some(item => item.path === path)) {
      if (mode.value === 'platform') platformActivePath.value = path
      else tenantActivePath.value = path
    }
  }

  function closeTab(path: string) {
    const currentTabs = tabs.value
    const index = currentTabs.findIndex(item => item.path === path)
    if (index < 0 || !currentTabs[index]?.closable) return
    const wasActive = activePath.value === path
    currentTabs.splice(index, 1)
    if (wasActive) {
      const fallback = (currentTabs[Math.max(0, index - 1)] ?? currentTabs[0])?.path
      if (mode.value === 'platform') platformActivePath.value = fallback ?? '/platform/admin'
      else tenantActivePath.value = fallback ?? '/workspace'
    }
  }

  return { mode, tabs, activePath, setMode, openTab, activateTab, setDirty, closeTab }
})
