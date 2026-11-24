import { defineStore } from 'pinia'
import { ref } from 'vue'
export interface WorkspaceTab { path: string; title: string; closable: boolean }
export const useWorkspaceStore = defineStore('workspace', () => {
  const tabs = ref<WorkspaceTab[]>([{ path: '/workspace', title: '工作台', closable: false }])
  const activePath = ref('/workspace')
  function openTab(tab: WorkspaceTab) {
    if (!tabs.value.some((item) => item.path === tab.path)) tabs.value.push(tab)
    activePath.value = tab.path
  }
  function activateTab(path: string) {
    if (tabs.value.some((item) => item.path === path)) activePath.value = path
  }
  function closeTab(path: string) {
    const index = tabs.value.findIndex((item) => item.path === path)
    if (index < 0 || !tabs.value[index]?.closable) return
    const wasActive = activePath.value === path
    tabs.value.splice(index, 1)
    if (wasActive) activePath.value = (tabs.value[Math.max(0, index - 1)] ?? tabs.value[0])?.path ?? '/workspace'
  }
  return { tabs, activePath, openTab, activateTab, closeTab }
})