<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZBell, ZChevronRight, ZCommand, ZMaximize2, ZUserRound, ZShieldCheck, ZX, ZIconButton, ZButton, ZAlert } from '@ztx/ui'
import { menuModules, platformMenuModules, findFeature } from './navigation/menu'
import { useWorkspaceStore } from './stores/workspace'
const route=useRoute(),router=useRouter(),workspace=useWorkspaceStore()
const isAuthRoute=computed(()=>route.path==='/'||route.path==='/tenant/login'||route.path==='/platform/login')
const isPlatformWorkspace=computed(()=>route.path.startsWith('/platform/')&&route.path!='/platform/login')
const activeModuleId=ref('workspace'),openCategoryId=ref<string|null>('personal'),flyoutOpen=ref(false),isTouch=ref(false)
const account=ref<{id:string;username:string;mfaEnabled?:boolean;kind:'platform'|'tenant'}|null>(null),accountMenuOpen=ref(false),accountMenuError=ref('')
const navCollapsed=ref(localStorage.getItem('ztx-nav-collapsed')==='true'),collapsedMenuOpen=ref(false)
const navRoot=ref<HTMLElement|null>(null),categoryButtons=new Map<string,HTMLButtonElement>(),moduleButtons=new Map<string,HTMLButtonElement>(),flyoutTop=ref(120)
const collapsedMenuRef=ref<HTMLElement|null>(null),collapsedMenuTop=ref(10)
let closeTimer:ReturnType<typeof setTimeout>|undefined
let accountCloseTimer:ReturnType<typeof setTimeout>|undefined
let openedByHover=false
const activeMenuModules=computed(()=>isPlatformWorkspace.value?platformMenuModules:menuModules)
const activeModule=computed(()=>activeMenuModules.value.find(item=>item.id===activeModuleId.value)??activeMenuModules.value[0]!)
const activeCategory=computed(()=>activeModule.value.categories.find(item=>item.id===openCategoryId.value))
watch(navCollapsed,value=>localStorage.setItem('ztx-nav-collapsed',String(value)))
watch(()=>route.fullPath,()=>{
 const path=route.path
 if(path==='/'||path==='/tenant/login'||path==='/platform/login')return
 const platform=path.startsWith('/platform/')
 workspace.setMode(platform?'platform':'tenant')
 const modules=platform?platformMenuModules:menuModules
 activeModuleId.value=modules[0]?.id??''
 openCategoryId.value=modules[0]?.categories[0]?.id??null
 const feature=findFeature(path)
 workspace.openTab({
  path,
  title:feature?.label??String(route.meta.title??'功能页面'),
  closable:route.meta.closable!==false,
 })
 flyoutOpen.value=false
 collapsedMenuOpen.value=false
 accountMenuOpen.value=false
 void refreshAccount()
},{immediate:true})
function setCategoryButton(id:string,element:HTMLButtonElement|null){if(element)categoryButtons.set(id,element);else categoryButtons.delete(id)}
function setModuleButton(id:string,element:HTMLButtonElement|null){if(element)moduleButtons.set(id,element);else moduleButtons.delete(id)}
function clearCloseTimer(){if(closeTimer)clearTimeout(closeTimer);closeTimer=undefined}
function scheduleClose(){clearCloseTimer();closeTimer=setTimeout(()=>{flyoutOpen.value=false;collapsedMenuOpen.value=false;openedByHover=false},220)}
function onMenuPointerLeave(event:PointerEvent){
 // Given 用户在菜单触发区或弹出菜单内移动指针，When 鼠标离开当前区域，Then 延迟关闭以便进入相邻菜单；触屏抬起不视为离开。
 if(event.pointerType!=='touch')scheduleClose()
}
function clearAccountCloseTimer(){if(accountCloseTimer)clearTimeout(accountCloseTimer);accountCloseTimer=undefined}
function scheduleAccountClose(){clearAccountCloseTimer();accountCloseTimer=setTimeout(()=>{accountMenuOpen.value=false},220)}
function toggleAccountMenu(){clearAccountCloseTimer();accountMenuOpen.value=!accountMenuOpen.value;accountMenuError.value=''}
function onAccountMenuPointerLeave(event:PointerEvent){
 // Given 用户点击头像打开账户菜单，When 鼠标移出头像与菜单区域，Then 延迟关闭菜单；触屏上不因抬起关闭。
 if(event.pointerType!=='touch')scheduleAccountClose()
}
function selectModule(id:string){
 if(navCollapsed.value&&activeModuleId.value===id){collapsedMenuOpen.value=!collapsedMenuOpen.value;return}
 activeModuleId.value=id
 openCategoryId.value=activeMenuModules.value.find(item=>item.id===id)?.categories[0]?.id??null
 flyoutOpen.value=false;openedByHover=false;clearCloseTimer();collapsedMenuOpen.value=navCollapsed.value
}
function onModulePointerEnter(id:string){
 if(!navCollapsed.value||isTouch.value)return
 clearCloseTimer();activeModuleId.value=id
 openCategoryId.value=activeMenuModules.value.find(item=>item.id===id)?.categories[0]?.id??null
 flyoutOpen.value=false;openedByHover=false;collapsedMenuOpen.value=true
 void nextTick(()=>{
  const trigger=moduleButtons.get(id)?.getBoundingClientRect(),root=navRoot.value,menuElement=collapsedMenuRef.value
  if(!trigger||!root||!menuElement)return
  // Given 弹窗顶部显示模块名称，When 用户从模块图标向右移动，Then 第一个二级菜单项应与图标垂直对齐。
  const menuRect=menuElement.getBoundingClientRect()
  const firstItem=menuElement.querySelector<HTMLElement>('.category-entry')
  const firstItemOffset=firstItem?firstItem.getBoundingClientRect().top-menuRect.top:0
  const rootRect=root.getBoundingClientRect()
  const preferredTop=trigger.top-rootRect.top-firstItemOffset
  const menuHeight=menuRect.height
  collapsedMenuTop.value=Math.max(8,Math.min(preferredTop,root.clientHeight-menuHeight-8))
 })
}
function onModuleClick(id:string,event:MouseEvent){
 // Given 桌面端指针悬停已打开二级菜单，When 随后的 click 事件到达，Then 不重复切换菜单状态。
 if(navCollapsed.value&&!isTouch.value&&event.detail>0)return
 selectModule(id)
}
function toggleNavigation(){navCollapsed.value=!navCollapsed.value;collapsedMenuOpen.value=false;flyoutOpen.value=false;openedByHover=false;clearCloseTimer()}
function openCategory(id:string){clearCloseTimer();openCategoryId.value=id;flyoutOpen.value=true;nextTick(()=>{const button=categoryButtons.get(id),root=navRoot.value;if(button&&root){const row=button.getBoundingClientRect(),panel=root.getBoundingClientRect();flyoutTop.value=Math.max(12,Math.min(row.top-panel.top,root.clientHeight-180))}})}
function onCategoryPointerEnter(id:string){if(!isTouch.value){openedByHover=true;openCategory(id)}}
function onCategoryClick(id:string){
 if(openedByHover&&openCategoryId.value===id&&flyoutOpen.value){openedByHover=false;return}
 // 点击只负责确保下级菜单打开，不与悬停争夺展开状态，也不反向关闭菜单。
 if(openCategoryId.value===id&&flyoutOpen.value)return
 openCategory(id)
}
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
async function navigateTo(path:string){
 flyoutOpen.value=false
 // 路由守卫可能因未保存修改拒绝导航；仅在导航成功后由 route watcher 创建/激活 Tab。
 await router.push(path)
}
async function activateTab(path:string){
 // 不提前改变 activePath，避免用户取消未保存提示后出现“激活标签与当前页面不一致”。
 await router.push(path)
}
async function closeTab(path:string,event:Event){
 event.stopPropagation()
 const wasActive=workspace.activePath===path
 const tab=workspace.tabs.find(item=>item.path===path)
 if(!wasActive&&tab?.dirty&&!window.confirm('该标签页有未保存的修改，确定关闭并放弃吗？'))return
 if(wasActive){
  const remaining=workspace.tabs.filter(tab=>tab.path!==path)
  const fallback=remaining[Math.max(0,workspace.tabs.findIndex(tab=>tab.path===path)-1)]?.path??remaining[0]?.path
  if(fallback){
   const failure=await router.push(fallback)
   if(failure)return
  }
 }
 workspace.closeTab(path)
}
async function refreshAccount(){
 if(route.path==='/platform/login'||route.path==='/tenant/login'){account.value=null;return}
 const platform=route.path.startsWith('/platform/')
 try{
  const response=await fetch(platform?'/api/v1/platform/auth/me':'/api/v1/tenant/auth/me',{credentials:'include',cache:'no-store'})
  account.value=response.ok?{...await response.json(),kind:platform?'platform':'tenant'}:null
 }catch{account.value=null}
}
async function logout(){
 accountMenuError.value=''
 const platform=account.value?.kind==='platform'
 try{
  const response=await fetch(platform?'/api/v1/platform/auth/logout':'/api/v1/tenant/auth/logout',{method:'POST',credentials:'include'})
  if(!response.ok&&response.status!==401)throw new Error('退出登录失败，请稍后重试')
  account.value=null
  accountMenuOpen.value=false
  await router.replace(platform?'/platform/login':'/tenant/login')
 }catch(cause){accountMenuError.value=cause instanceof Error?cause.message:'退出登录失败'}
}
function openAccountPage(path:string){accountMenuOpen.value=false;void router.push(path)}
function toggleFullscreen(){if(!document.fullscreenElement)void document.documentElement.requestFullscreen?.();else void document.exitFullscreen?.()}
function onGlobalKeydown(event:KeyboardEvent){if(event.key==='Escape'&&flyoutOpen.value){flyoutOpen.value=false;const id=openCategoryId.value;if(id)nextTick(()=>categoryButtons.get(id)?.focus())}}
onMounted(()=>{isTouch.value=window.matchMedia('(pointer: coarse)').matches;window.addEventListener('keydown',onGlobalKeydown);void refreshAccount()})
onBeforeUnmount(()=>{clearCloseTimer();clearAccountCloseTimer();window.removeEventListener('keydown',onGlobalKeydown)})
</script>
<template>
  <div v-if="!isAuthRoute" class="ztx-shell min-h-screen bg-ztx-page font-sans text-ztx-text">
    <header class="workspace-header sticky top-0 z-30 flex h-11 min-h-11 items-center gap-3 border-b border-ztx-border bg-ztx-surface pr-3.5">
      <div class="brand-lockup flex h-ztx-topbar w-ztx-sidebar basis-ztx-sidebar shrink-0 items-center gap-2 border-r border-ztx-border px-4 max-[720px]:w-ztx-sidebar-mobile max-[720px]:basis-ztx-sidebar-mobile" :class="{ 'is-nav-collapsed': navCollapsed, '!w-ztx-sidebar-collapsed !basis-ztx-sidebar-collapsed justify-center px-0 max-[720px]:!w-ztx-sidebar-collapsed-mobile max-[720px]:!basis-ztx-sidebar-collapsed-mobile': navCollapsed }" :aria-label="isPlatformWorkspace ? 'ZTX 平台管理工作区' : 'ZTX 企业工作区'">
        <div class="brand-mark grid size-[26px] place-items-center rounded-[6px] bg-ztx-primary text-ztx-on-primary">
          <ZCommand :size="18" :stroke-width="2.2" />
        </div>
        <div v-if="!navCollapsed" class="brand-wordmark flex flex-col gap-[3px] leading-none">
          <strong class="text-[14px] font-bold tracking-[.1em]">ZTX ERP</strong>
        </div>
      </div>
      <nav class="workspace-tabs flex h-ztx-topbar min-h-ztx-topbar min-w-0 flex-1 items-stretch overflow-x-auto" aria-label="工作区标签">
        <button
          v-for="tab in workspace.tabs"
          :key="tab.path"
          class="workspace-tab relative inline-flex min-h-11 min-w-[104px] max-w-[180px] items-center justify-center gap-[9px] whitespace-nowrap px-[11px] text-ztx-body text-ztx-muted hover:bg-ztx-surface-soft hover:text-ztx-text"
          :class="{ 'is-active': workspace.activePath === tab.path, 'border-x border-b-2 border-ztx-border border-b-ztx-primary bg-ztx-page font-semibold text-ztx-primary-strong': workspace.activePath === tab.path }"
          :aria-current="workspace.activePath === tab.path ? 'page' : undefined"
          @click="activateTab(tab.path)"
        >
          <span>{{ tab.title }}</span>
          <span v-if="tab.dirty" class="size-1.5 rounded-full bg-ztx-warning" aria-label="未保存修改" />
          <span
            v-if="tab.closable"
            class="tab-close grid size-[18px] place-items-center rounded text-ztx-muted hover:bg-ztx-border hover:text-ztx-text"
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
      <div class="header-actions flex shrink-0 items-center gap-1">
        <ZIconButton label="全屏" @click="toggleFullscreen">
          <ZMaximize2 />
        </ZIconButton>
        <ZIconButton label="通知">
          <ZBell />
        </ZIconButton>
        <div class="account-menu-root relative flex items-center" @pointerenter="clearAccountCloseTimer" @pointerleave="onAccountMenuPointerLeave">
          <ZIconButton
            label="用户账户菜单"
            button-class="grid size-8 place-items-center rounded-[5px] bg-transparent text-ztx-text-secondary hover:bg-ztx-surface-warm hover:text-ztx-primary-strong"
            has-popup="menu"
            :expanded="accountMenuOpen"
            @click="toggleAccountMenu"
          >
            <ZUserRound />
          </ZIconButton>
          <div v-if="accountMenuOpen" class="account-menu-panel absolute right-0 top-[calc(100%+7px)] z-[80] w-56 rounded-ztx-md border border-ztx-border bg-ztx-surface p-[7px] shadow-[var(--ztx-shadow-popover)]" role="menu" aria-label="用户账户菜单">
            <div class="account-menu-identity flex flex-col gap-[3px] border-b border-ztx-border px-2.5 pb-[11px] pt-[9px]">
              <strong class="text-ztx-body text-ztx-text">{{ account?.username ?? '尚未登录' }}</strong>
              <small class="text-ztx-caption text-ztx-muted">{{ account ? (account.kind === 'platform' ? '平台管理员' : '企业用户') : '账户' }}</small>
            </div>
            <ZButton v-if="account?.kind === 'platform'" class="account-menu-item flex w-full items-center gap-[9px] rounded px-2.5 py-[9px] text-left text-ztx-body text-ztx-text-secondary hover:bg-ztx-surface-warm hover:text-ztx-primary-strong" variant="ghost" size="sm" role="menuitem" @click="openAccountPage('/platform/admin')">
              <ZShieldCheck :size="15" /> 平台控制台
            </ZButton>
            <ZButton v-if="account?.kind === 'platform'" class="account-menu-item flex w-full items-center gap-[9px] rounded px-2.5 py-[9px] text-left text-ztx-body text-ztx-text-secondary hover:bg-ztx-surface-warm hover:text-ztx-primary-strong" variant="ghost" size="sm" role="menuitem" @click="openAccountPage('/platform/admin#password-settings')">
              <ZUserRound :size="15" /> 修改密码
            </ZButton>
            <ZButton v-if="account?.kind === 'tenant'" class="account-menu-item flex w-full items-center gap-[9px] rounded px-2.5 py-[9px] text-left text-ztx-body text-ztx-text-secondary hover:bg-ztx-surface-warm hover:text-ztx-primary-strong" variant="ghost" size="sm" role="menuitem" @click="openAccountPage('/tenant/account')">
              <ZUserRound :size="15" /> 修改密码
            </ZButton>
            <ZButton v-if="!account" class="account-menu-item flex w-full items-center gap-[9px] rounded px-2.5 py-[9px] text-left text-ztx-body text-ztx-text-secondary hover:bg-ztx-surface-warm hover:text-ztx-primary-strong" variant="ghost" size="sm" role="menuitem" @click="openAccountPage(route.path.startsWith('/platform') ? '/platform/login' : '/tenant/login')">
              <ZUserRound :size="15" /> 登录
            </ZButton>
            <ZAlert v-if="accountMenuError" class="account-menu-error mx-2 my-[5px] text-ztx-caption text-ztx-danger" tone="error" role="alert">{{ accountMenuError }}</ZAlert>
            <ZButton v-if="account" class="account-menu-logout mt-1 w-full border-t border-ztx-border pt-2 text-left text-ztx-danger" variant="ghost" size="sm" role="menuitem" @click="logout">
              <ZX :size="15" /> 退出登录
            </ZButton>
          </div>
        </div>
      </div>
    </header>
    <div class="workspace-body flex min-h-[calc(100vh-var(--ztx-topbar-height))]" :class="{ 'is-nav-collapsed': navCollapsed }">
      <aside
        ref="navRoot"
        class="ztx-sidebar relative z-20 flex min-h-[calc(100vh-var(--ztx-topbar-height))] w-ztx-sidebar shrink-0 flex-col border-r border-ztx-border bg-ztx-surface transition-[width,flex-basis] duration-200 max-[720px]:w-ztx-sidebar-mobile max-[720px]:basis-ztx-sidebar-mobile"
        :class="{ 'is-collapsed': navCollapsed, 'has-collapsed-menu': collapsedMenuOpen, '!w-ztx-sidebar-collapsed !basis-ztx-sidebar-collapsed': navCollapsed, 'max-[720px]:!w-ztx-sidebar-collapsed-mobile max-[720px]:!basis-ztx-sidebar-collapsed-mobile': navCollapsed }"
        aria-label="一级菜单"
        @pointerenter="clearCloseTimer"
        @pointerleave="onMenuPointerLeave"
      >
        <nav class="sidebar-navigation min-h-0 flex-1 overflow-y-auto px-[9px] pb-[14px] pt-[10px]" :class="{ 'px-[7px]': navCollapsed }" aria-label="一级菜单">
          <div v-for="module in activeMenuModules" :key="module.id" class="module-group mb-[3px] flex flex-col" @pointerleave="navCollapsed && onMenuPointerLeave($event)">
            <button
              class="module-entry flex min-h-11 w-full items-center justify-start gap-3 rounded-[7px] px-3 text-ztx-section-title text-ztx-text hover:bg-ztx-surface-soft"
              :aria-label="module.label"
              :title="navCollapsed ? module.label : undefined"
              :class="{ 'is-active': activeModuleId === module.id, 'bg-ztx-surface-warm font-semibold text-ztx-primary-strong': activeModuleId === module.id, '!min-h-[46px] justify-center px-0': navCollapsed }"
              :ref="element => setModuleButton(module.id, element as HTMLButtonElement | null)"
              :aria-expanded="navCollapsed ? activeModuleId === module.id && collapsedMenuOpen : activeModuleId === module.id"
              @pointerenter="onModulePointerEnter(module.id)"
              @click="onModuleClick(module.id, $event)"
            >
              <component :is="module.icon" :size="19" :stroke-width="1.8" />
              <span :class="{ hidden: navCollapsed }">{{ module.label }}</span>
              <ZChevronRight
                v-if="!navCollapsed"
                class="module-chevron text-ztx-muted transition-transform duration-150"
                :class="{ 'is-expanded rotate-90': activeModuleId === module.id }"
                :size="15"
              />
            </button>
            <div
              v-if="activeModuleId === module.id && !navCollapsed"
              class="inline-category-list flex flex-col gap-px pb-1.5 pl-[38px] pt-[3px]"
              :aria-label="module.label + '二级菜单'"
            >
              <button
                v-for="(category, index) in module.categories"
                :key="category.id"
                :ref="element => setCategoryButton(category.id, element as HTMLButtonElement | null)"
                class="category-entry flex min-h-[37px] w-full items-center justify-between gap-2 rounded-md px-[10px] text-ztx-nav text-ztx-text-secondary hover:bg-ztx-surface-soft hover:text-ztx-primary-strong"
                 :class="{ 'is-open bg-ztx-surface-soft font-semibold text-ztx-primary-strong': openCategoryId === category.id && flyoutOpen }"
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
        <div
          v-if="navCollapsed && collapsedMenuOpen"
          ref="collapsedMenuRef"
          class="collapsed-category-popover absolute left-[calc(100%-var(--ztx-popover-overlap))] z-[25] flex w-ztx-collapsed-menu flex-col gap-0.5 rounded-r-ztx-md border border-ztx-border bg-ztx-surface p-2 shadow-[var(--ztx-shadow-popover)]"
          :style="{ top: collapsedMenuTop + 'px' }"
          :aria-label="activeModule.label + '二级菜单'"
          @pointerenter="clearCloseTimer"
          @pointerleave="onMenuPointerLeave"
        >
          <div class="collapsed-menu-heading flex min-h-9 items-center border-b border-ztx-border px-[9px] pb-[7px] text-ztx-text"><strong>{{ activeModule.label }}</strong></div>
          <button
            v-for="(category, index) in activeModule.categories"
            :key="category.id"
            :ref="element => setCategoryButton(category.id, element as HTMLButtonElement | null)"
            class="category-entry flex min-h-9 w-full items-center justify-between gap-2 rounded-md px-[9px] text-ztx-nav text-ztx-text-secondary hover:bg-ztx-surface-soft hover:text-ztx-primary-strong"
            :class="{ 'is-open bg-ztx-surface-soft font-semibold text-ztx-primary-strong': openCategoryId === category.id && flyoutOpen }"
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
        <div class="rail-footer flex items-center justify-start gap-2.5 border-t border-ztx-border px-3.5 py-3 text-ztx-caption" :class="{ 'justify-center px-1.5 py-2.5': navCollapsed }">
          <span class="environment-dot size-[7px] shrink-0 rounded-full bg-ztx-success-indicator shadow-[0_0_0_3px_rgba(108,148,110,0.12)]" />
          <span class="environment-label" :class="{ hidden: navCollapsed }">演示环境</span>
          <button
            class="nav-collapse-toggle ml-auto grid size-[30px] place-items-center rounded-[5px] border border-ztx-border bg-ztx-surface text-ztx-muted hover:bg-ztx-surface-warm hover:text-ztx-primary-strong"
            type="button"
            :aria-label="navCollapsed ? '展开导航栏' : '折叠导航栏'"
            :aria-expanded="!navCollapsed"
            @click="toggleNavigation"
          >
            <ZChevronRight :size="17" :class="{ 'rotate-180': !navCollapsed }" />
          </button>
        </div>
        <Transition name="flyout">
          <div
            v-if="flyoutOpen && activeCategory"
            class="feature-flyout absolute z-30 max-h-[calc(100vh-110px)] w-[252px] overflow-auto rounded-ztx-md border border-ztx-border-strong bg-ztx-surface p-2.5 shadow-[var(--ztx-shadow-popover)] transition-[opacity,transform] duration-150"
            :style="{ top: flyoutTop + 'px', left: navCollapsed ? 'calc(var(--ztx-sidebar-collapsed-width-active) + var(--ztx-collapsed-menu-width) - var(--ztx-popover-overlap))' : 'calc(100% - 1px)' }"
            role="menu"
            :aria-label="activeCategory.label + '功能菜单'"
            @pointerenter="clearCloseTimer"
            @pointerleave="onMenuPointerLeave"
            @keydown="onFlyoutKeydown"
          >
            <button
              v-for="(feature, index) in activeCategory.features"
              :key="feature.id"
              class="feature-entry flex min-h-9 w-full items-center justify-start rounded px-2.5 py-2 text-left text-ztx-nav text-ztx-text hover:bg-ztx-surface-warm hover:text-ztx-primary-strong"
              role="menuitem"
              :data-feature-first="index === 0 ? 'true' : undefined"
              @click="navigateTo(feature.path)"
            >
              <span class="feature-copy flex flex-col gap-1">
                <strong>{{ feature.label }}</strong>
              </span>
            </button>
          </div>
        </Transition>
      </aside>
      <main class="workspace-main flex min-w-0 flex-1 flex-col px-[26px] pt-4 max-[1050px]:px-[18px] max-[720px]:px-3 max-[720px]:pt-3">
        <RouterView v-slot="{ Component, route: viewRoute }"><KeepAlive><component :is="Component" :key="viewRoute.fullPath" /></KeepAlive></RouterView>
        <footer class="workspace-footer mt-auto flex justify-between gap-3 py-[15px] pb-3 text-ztx-caption text-ztx-muted max-[720px]:flex-col max-[720px]:items-start">
          <span>© 2026 ZTX · 制造业低代码 ERP 平台</span>
          <span class="flex items-center gap-[7px]">{{ isPlatformWorkspace ? '平台工作区' : '企业工作区' }} <i class="size-[5px] rounded-full bg-ztx-success-indicator" /> 安全连接</span>
        </footer>
      </main>
    </div>
  </div>
  <RouterView v-else />
</template>
