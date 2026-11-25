<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZBell, ZChevronRight, ZCommand, ZMaximize2, ZUserRound, ZShieldCheck, ZX, ZIconButton, ZButton, ZAlert } from '@ztx/ui'
import { menuModules, findFeature } from './navigation/menu'
import { useWorkspaceStore } from './stores/workspace'
const route=useRoute(),router=useRouter(),workspace=useWorkspaceStore()
const isAuthRoute=computed(()=>route.path==='/'||route.path==='/tenant/login'||route.path==='/platform/login')
const activeModuleId=ref('workspace'),openCategoryId=ref<string|null>('personal'),flyoutOpen=ref(false),isTouch=ref(false)
const account=ref<{id:string;username:string;mfaEnabled?:boolean;kind:'platform'|'tenant'}|null>(null),accountMenuOpen=ref(false),accountMenuError=ref('')
const navCollapsed=ref(localStorage.getItem('ztx-nav-collapsed')==='true'),collapsedMenuOpen=ref(false)
const navRoot=ref<HTMLElement|null>(null),categoryButtons=new Map<string,HTMLButtonElement>(),moduleButtons=new Map<string,HTMLButtonElement>(),flyoutTop=ref(120)
const collapsedMenuRef=ref<HTMLElement|null>(null),collapsedMenuTop=ref(10)
let closeTimer:ReturnType<typeof setTimeout>|undefined
let accountCloseTimer:ReturnType<typeof setTimeout>|undefined
let openedByHover=false
const activeModule=computed(()=>menuModules.find(item=>item.id===activeModuleId.value)??menuModules[0]!)
const activeCategory=computed(()=>activeModule.value.categories.find(item=>item.id===openCategoryId.value))
watch(navCollapsed,value=>localStorage.setItem('ztx-nav-collapsed',String(value)))
watch(()=>route.fullPath,path=>{
 if(path==='/'||path==='/tenant/login'||path==='/platform/login')return
 const feature=findFeature(path)
 workspace.openTab({path,title:feature?.label??String(route.meta.title??'功能页面'),closable:path!='/workspace'})
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
 openCategoryId.value=menuModules.find(item=>item.id===id)?.categories[0]?.id??null
 flyoutOpen.value=false;openedByHover=false;clearCloseTimer();collapsedMenuOpen.value=navCollapsed.value
}
function onModulePointerEnter(id:string){
 if(!navCollapsed.value||isTouch.value)return
 clearCloseTimer();activeModuleId.value=id
 openCategoryId.value=menuModules.find(item=>item.id===id)?.categories[0]?.id??null
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
async function navigateTo(path:string){const feature=findFeature(path);if(feature)workspace.openTab({path,title:feature.label,closable:path!='/workspace'});flyoutOpen.value=false;await router.push(path)}
async function activateTab(path:string){workspace.activateTab(path);await router.push(path)}
async function closeTab(path:string,event:Event){event.stopPropagation();const wasActive=workspace.activePath===path;workspace.closeTab(path);if(wasActive)await router.push(workspace.activePath)}
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
  <div v-if="!isAuthRoute" class="ztx-shell">
    <header class="workspace-header">
      <div class="brand-lockup" :class="{ 'is-nav-collapsed': navCollapsed }" aria-label="ZTX 企业工作区">
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
        <div class="account-menu-root" @pointerenter="clearAccountCloseTimer" @pointerleave="onAccountMenuPointerLeave">
          <ZIconButton
            label="用户账户菜单"
            button-class="account-menu-trigger"
            has-popup="menu"
            :expanded="accountMenuOpen"
            @click="toggleAccountMenu"
          >
            <ZUserRound />
          </ZIconButton>
          <div v-if="accountMenuOpen" class="account-menu-panel" role="menu" aria-label="用户账户菜单">
            <div class="account-menu-identity">
              <strong>{{ account?.username ?? '尚未登录' }}</strong>
              <small>{{ account ? (account.kind === 'platform' ? '平台管理员' : '企业用户') : '账户' }}</small>
            </div>
            <ZButton v-if="account?.kind === 'platform'" class="account-menu-item" variant="ghost" size="sm" role="menuitem" @click="openAccountPage('/platform/admin')">
              <ZShieldCheck :size="15" /> 平台控制台
            </ZButton>
            <ZButton v-if="account?.kind === 'platform'" class="account-menu-item" variant="ghost" size="sm" role="menuitem" @click="openAccountPage('/platform/admin#password-settings')">
              <ZUserRound :size="15" /> 修改密码
            </ZButton>
            <ZButton v-if="account?.kind === 'tenant'" class="account-menu-item" variant="ghost" size="sm" role="menuitem" @click="openAccountPage('/tenant/account')">
              <ZUserRound :size="15" /> 修改密码
            </ZButton>
            <ZButton v-if="!account" class="account-menu-item" variant="ghost" size="sm" role="menuitem" @click="openAccountPage(route.path.startsWith('/platform') ? '/platform/login' : '/tenant/login')">
              <ZUserRound :size="15" /> 登录
            </ZButton>
            <ZAlert v-if="accountMenuError" class="account-menu-error" tone="error" role="alert">{{ accountMenuError }}</ZAlert>
            <ZButton v-if="account" class="account-menu-logout" variant="ghost" size="sm" role="menuitem" @click="logout">
              <ZX :size="15" /> 退出登录
            </ZButton>
          </div>
        </div>
      </div>
    </header>
    <div class="workspace-body" :class="{ 'is-nav-collapsed': navCollapsed }">
      <aside
        ref="navRoot"
        class="ztx-sidebar"
        :class="{ 'is-collapsed': navCollapsed, 'has-collapsed-menu': collapsedMenuOpen }"
        aria-label="一级菜单"
        @pointerenter="clearCloseTimer"
        @pointerleave="onMenuPointerLeave"
      >
        <nav class="sidebar-navigation" aria-label="一级菜单">
          <div v-for="module in menuModules" :key="module.id" class="module-group" @pointerleave="navCollapsed && onMenuPointerLeave($event)">
            <button
              class="module-entry"
              :aria-label="module.label"
              :title="navCollapsed ? module.label : undefined"
              :class="{ 'is-active': activeModuleId === module.id }"
              :ref="element => setModuleButton(module.id, element as HTMLButtonElement | null)"
              :aria-expanded="navCollapsed ? activeModuleId === module.id && collapsedMenuOpen : activeModuleId === module.id"
              @pointerenter="onModulePointerEnter(module.id)"
              @click="onModuleClick(module.id, $event)"
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
          </div>
        </nav>
        <div
          v-if="navCollapsed && collapsedMenuOpen"
          ref="collapsedMenuRef"
          class="collapsed-category-popover"
          :style="{ top: collapsedMenuTop + 'px' }"
          :aria-label="activeModule.label + '二级菜单'"
          @pointerenter="clearCloseTimer"
          @pointerleave="onMenuPointerLeave"
        >
          <div class="collapsed-menu-heading"><strong>{{ activeModule.label }}</strong></div>
          <button
            v-for="(category, index) in activeModule.categories"
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
            @pointerleave="onMenuPointerLeave"
            @keydown="onFlyoutKeydown"
          >
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
              </span>
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
  <RouterView v-else />
</template>
<style scoped>
.account-menu-root{position:relative;display:flex;align-items:center}
.account-menu-trigger{width:32px;height:32px;display:grid;place-items:center;border:0;border-radius:5px;background:transparent;color:var(--ztx-text-secondary);cursor:pointer}
.account-menu-trigger:hover,.account-menu-trigger[aria-expanded="true"]{background:var(--ztx-surface-warm);color:var(--ztx-primary-strong)}
.account-menu-panel{position:absolute;z-index:80;top:calc(100% + 7px);right:0;width:224px;padding:7px;border:1px solid var(--ztx-border);border-radius:7px;background:var(--ztx-surface);box-shadow:var(--ztx-shadow-popover)}
.account-menu-identity{display:flex;flex-direction:column;gap:3px;padding:9px 10px 11px;border-bottom:1px solid var(--ztx-border)}
.account-menu-identity strong{font-size:13px;color:var(--ztx-text)}
.account-menu-identity small{font-size:12px;color:var(--ztx-muted)}
.account-menu-panel>button{width:100%;display:flex;align-items:center;gap:9px;padding:9px 10px;border:0;border-radius:4px;background:transparent;color:var(--ztx-text-secondary);text-align:left;font:inherit;font-size:13px;cursor:pointer}
.account-menu-panel>button:hover{background:var(--ztx-surface-warm);color:var(--ztx-primary-strong)}
.account-menu-panel>button.account-menu-logout{margin-top:4px;border-top:1px solid var(--ztx-border);border-radius:0;color:#a3473c}
.account-menu-error{margin:5px 8px;color:#a3473c;font-size:12px}
</style>
