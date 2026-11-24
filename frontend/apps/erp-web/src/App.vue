<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZBell, ZChevronRight, ZCommand, ZMaximize2, ZUserRound, ZX, ZIconButton } from '@ztx/ui'
import { menuModules, findFeature } from './navigation/menu'
import { useWorkspaceStore } from './stores/workspace'
const route=useRoute(),router=useRouter(),workspace=useWorkspaceStore()
const activeModuleId=ref('workspace'),openCategoryId=ref<string|null>('personal'),flyoutOpen=ref(false),isTouch=ref(false)
const navCollapsed=ref(localStorage.getItem('ztx-nav-collapsed')==='true'),collapsedMenuOpen=ref(false)
const navRoot=ref<HTMLElement|null>(null),categoryButtons=new Map<string,HTMLButtonElement>(),flyoutTop=ref(120)
let closeTimer:ReturnType<typeof setTimeout>|undefined
let openedByHover=false
const activeModule=computed(()=>menuModules.find(item=>item.id===activeModuleId.value)??menuModules[0]!)
const activeCategory=computed(()=>activeModule.value.categories.find(item=>item.id===openCategoryId.value))
watch(navCollapsed,value=>localStorage.setItem('ztx-nav-collapsed',String(value)))
watch(()=>route.fullPath,path=>{
 if(path==='/')return
 const feature=findFeature(path)
 workspace.openTab({path,title:feature?.label??String(route.meta.title??'功能页面'),closable:path!='/workspace'})
},{immediate:true})
function setCategoryButton(id:string,element:HTMLButtonElement|null){if(element)categoryButtons.set(id,element);else categoryButtons.delete(id)}
function clearCloseTimer(){if(closeTimer)clearTimeout(closeTimer);closeTimer=undefined}
function scheduleClose(){clearCloseTimer();closeTimer=setTimeout(()=>{flyoutOpen.value=false;openedByHover=false},220)}
function selectModule(id:string){if(navCollapsed.value&&activeModuleId.value===id){collapsedMenuOpen.value=!collapsedMenuOpen.value;return}activeModuleId.value=id;openCategoryId.value=menuModules.find(item=>item.id===id)?.categories[0]?.id??null;flyoutOpen.value=false;openedByHover=false;clearCloseTimer();collapsedMenuOpen.value=navCollapsed.value}
function toggleNavigation(){navCollapsed.value=!navCollapsed.value;collapsedMenuOpen.value=false;flyoutOpen.value=false;openedByHover=false;clearCloseTimer()}
function openCategory(id:string){clearCloseTimer();openCategoryId.value=id;flyoutOpen.value=true;nextTick(()=>{const button=categoryButtons.get(id),root=navRoot.value;if(button&&root){const row=button.getBoundingClientRect(),panel=root.getBoundingClientRect();flyoutTop.value=Math.max(12,Math.min(row.top-panel.top,root.clientHeight-180))}})}
function onCategoryPointerEnter(id:string){if(!isTouch.value){openedByHover=true;openCategory(id)}}
function toggleCategory(id:string){clearCloseTimer();openedByHover=false;if(openCategoryId.value===id&&flyoutOpen.value)flyoutOpen.value=false;else openCategory(id)}
function onCategoryClick(id:string){if(openedByHover&&openCategoryId.value===id&&flyoutOpen.value){openedByHover=false;return}toggleCategory(id)}
function onCategoryKeydown(event:KeyboardEvent,id:string,index:number){
 const categories=activeModule.value.categories
 if(event.key==='ArrowDown'||event.key==='ArrowUp'){
  event.preventDefault();const nextIndex=event.key==='ArrowDown'?(index+1)%categories.length:(index-1+categories.length)%categories.length
  const next=categories[nextIndex];if(next){openCategory(next.id);nextTick(()=>categoryButtons.get(next.id)?.focus())}
 }else if(event.key==='ArrowRight'||event.key==='Enter'||event.key===' '){
  event.preventDefault();openedByHover=false;openCategory(id);nextTick(()=>navRoot.value?.querySelector<HTMLElement>('[data-feature-first="true"]')?.focus())
 }else if(event.key==='Escape'){event.preventDefault();flyoutOpen.value=false}
}
function onFlyoutKeydown(event:KeyboardEvent){
 const items=Array.from(navRoot.value?.querySelectorAll<HTMLElement>('[role="menuitem"]')??[])
 const current=items.indexOf(document.activeElement as HTMLElement)
 if(event.key==='ArrowDown'||event.key==='ArrowUp'){
  event.preventDefault();const next=event.key==='ArrowDown'?(current+1+items.length)%items.length:(current-1+items.length)%items.length;items[next]?.focus()
 }else if(event.key==='Home'){event.preventDefault();items[0]?.focus()}
 else if(event.key==='End'){event.preventDefault();items.at(-1)?.focus()}
 else if(event.key==='Escape'||event.key==='ArrowLeft'){
  event.preventDefault();flyoutOpen.value=false;const id=openCategoryId.value;if(id)nextTick(()=>categoryButtons.get(id)?.focus())
 }
}
async function navigateTo(path:string){const feature=findFeature(path);if(feature)workspace.openTab({path,title:feature.label,closable:path!='/workspace'});flyoutOpen.value=false;await router.push(path)}
async function activateTab(path:string){workspace.activateTab(path);await router.push(path)}
async function closeTab(path:string,event:Event){event.stopPropagation();const wasActive=workspace.activePath===path;workspace.closeTab(path);if(wasActive)await router.push(workspace.activePath)}
function toggleFullscreen(){if(!document.fullscreenElement)void document.documentElement.requestFullscreen?.();else void document.exitFullscreen?.()}
function onGlobalKeydown(event:KeyboardEvent){if(event.key==='Escape'&&flyoutOpen.value){flyoutOpen.value=false;const id=openCategoryId.value;if(id)nextTick(()=>categoryButtons.get(id)?.focus())}}
onMounted(()=>{isTouch.value=window.matchMedia('(pointer: coarse)').matches;window.addEventListener('keydown',onGlobalKeydown)})
onBeforeUnmount(()=>{clearCloseTimer();window.removeEventListener('keydown',onGlobalKeydown)})
</script>
<template>
  <div class="ztx-shell">
    <header class="workspace-header">
      <div class="brand-lockup" aria-label="ZTX 企业工作区">
        <div class="brand-mark">
          <ZCommand :size="18" :stroke-width="2.2" />
        </div>
        <div class="brand-wordmark">
          <strong>ZTX ERP</strong>
        </div>
      </div>
      <nav class="workspace-tabs" aria-label="工作区标签">
        <button
          v-for="tab in workspace.tabs"
          :key="tab.path"
          class="workspace-tab"
          :class="{ 'is-active': workspace.activePath === tab.path }"
          :aria-current="workspace.activePath === tab.path ? 'page' : undefined"
          @click="activateTab(tab.path)"
        >
          <span>{{ tab.title }}</span>
          <span
            v-if="tab.closable"
            class="tab-close"
            role="button"
            :aria-label="'关闭 ' + tab.title"
            tabindex="0"
            @click="closeTab(tab.path, $event)"
            @keydown.enter.stop="closeTab(tab.path, $event)"
          >
            <ZX :size="12" />
          </span>
        </button>
      </nav>
      <div class="header-actions">
        <ZIconButton label="全屏" @click="toggleFullscreen">
          <ZMaximize2 />
        </ZIconButton>
        <ZIconButton label="通知">
          <ZBell />
        </ZIconButton>
        <ZIconButton label="用户">
          <ZUserRound />
        </ZIconButton>
      </div>
    </header>
    <div class="workspace-body" :class="{ 'is-nav-collapsed': navCollapsed }">
      <aside
        ref="navRoot"
        class="ztx-sidebar"
        :class="{ 'is-collapsed': navCollapsed, 'has-collapsed-menu': collapsedMenuOpen }"
        aria-label="一级菜单"
        @pointerenter="clearCloseTimer"
      >
        <nav class="sidebar-navigation" aria-label="一级菜单">
          <div v-for="module in menuModules" :key="module.id" class="module-group">
            <button
              class="module-entry"
              :aria-label="module.label"
              :title="navCollapsed ? module.label : undefined"
              :class="{ 'is-active': activeModuleId === module.id }"
              :aria-expanded="activeModuleId === module.id && !navCollapsed"
              @click="selectModule(module.id)"
            >
              <component :is="module.icon" :size="19" :stroke-width="1.8" />
              <span>{{ module.label }}</span>
              <ZChevronRight
                v-if="!navCollapsed"
                class="module-chevron"
                :class="{ 'is-expanded': activeModuleId === module.id }"
                :size="15"
              />
            </button>
            <div
              v-if="activeModuleId === module.id && !navCollapsed"
              class="inline-category-list"
              :aria-label="module.label + '二级菜单'"
            >
              <button
                v-for="(category, index) in module.categories"
                :key="category.id"
                :ref="element => setCategoryButton(category.id, element as HTMLButtonElement | null)"
                class="category-entry"
                :class="{ 'is-open': openCategoryId === category.id && flyoutOpen }"
                :aria-expanded="openCategoryId === category.id && flyoutOpen"
                aria-haspopup="menu"
                @pointerenter="onCategoryPointerEnter(category.id)"
                @click="onCategoryClick(category.id)"
                @keydown="onCategoryKeydown($event, category.id, index)"
              >
                <span>{{ category.label }}</span>
                <ZChevronRight :size="15" />
              </button>
            </div>
            <div
              v-if="activeModuleId === module.id && navCollapsed && collapsedMenuOpen"
              class="collapsed-category-popover"
              :aria-label="module.label + '二级菜单'"
            >
              <button
                v-for="(category, index) in module.categories"
                :key="category.id"
                :ref="element => setCategoryButton(category.id, element as HTMLButtonElement | null)"
                class="category-entry"
                :class="{ 'is-open': openCategoryId === category.id && flyoutOpen }"
                :aria-expanded="openCategoryId === category.id && flyoutOpen"
                aria-haspopup="menu"
                @pointerenter="onCategoryPointerEnter(category.id)"
                @click="onCategoryClick(category.id)"
                @keydown="onCategoryKeydown($event, category.id, index)"
              >
                <span>{{ category.label }}</span>
                <ZChevronRight :size="15" />
              </button>
            </div>
          </div>
        </nav>
        <div class="rail-footer">
          <span class="environment-dot" />
          <span class="environment-label">演示环境</span>
          <button
            class="nav-collapse-toggle"
            type="button"
            :aria-label="navCollapsed ? '展开导航栏' : '折叠导航栏'"
            :aria-expanded="!navCollapsed"
            @click="toggleNavigation"
          >
            <ZChevronRight :size="17" />
          </button>
        </div>
        <Transition name="flyout">
          <div
            v-if="flyoutOpen && activeCategory"
            class="feature-flyout"
            :style="{ top: flyoutTop + 'px' }"
            role="menu"
            :aria-label="activeCategory.label + '功能菜单'"
            @pointerenter="clearCloseTimer"
            @pointerleave="scheduleClose"
            @keydown="onFlyoutKeydown"
          >
            <div class="flyout-heading">
              <div>
                <span class="eyebrow">FUNCTIONS</span>
                <strong>{{ activeCategory.label }}</strong>
              </div>
            </div>
            <button
              v-for="(feature, index) in activeCategory.features"
              :key="feature.id"
              class="feature-entry"
              role="menuitem"
              :data-feature-first="index === 0 ? 'true' : undefined"
              @click="navigateTo(feature.path)"
            >
              <span class="feature-copy">
                <strong>{{ feature.label }}</strong>
                <small>{{ feature.description }}</small>
              </span>
              <ZChevronRight :size="14" />
            </button>
          </div>
        </Transition>
      </aside>
      <main class="workspace-main">
        <RouterView />
        <footer class="workspace-footer">
          <span>© 2026 ZTX · 制造业低代码 ERP 平台</span>
          <span>平台工作区 <i /> 安全连接</span>
        </footer>
      </main>
    </div>
  </div>
</template>