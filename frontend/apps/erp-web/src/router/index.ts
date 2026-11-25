import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceHome from '../views/WorkspaceHome.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
import PlatformLogin from '../views/PlatformLogin.vue'
import PlatformAdmin from '../views/PlatformAdmin.vue'
import TenantManagement from '../views/TenantManagement.vue'
import TenantLogin from '../views/TenantLogin.vue'
import TenantAccount from '../views/TenantAccount.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/tenant/login' },
    { path: '/tenant/login', name: 'tenant-login', component: TenantLogin, meta: { title: '企业登录', closable: false, public: true } },
    { path: '/tenant/account', name: 'tenant-account', component: TenantAccount, meta: { title: '账户与安全', closable: true } },
    { path: '/workspace', name: 'workspace', component: WorkspaceHome, meta: { title: '工作台', closable: false } },
    { path: '/platform/login', name: 'platform-login', component: PlatformLogin, meta: { title: '平台管理登录', closable: false, public: true } },
    { path: '/platform/admin', name: 'platform-admin', component: PlatformAdmin, meta: { title: '平台控制台', closable: true } },
    { path: '/platform/tenants', name: 'platform-tenants', component: TenantManagement, meta: { title: '租户管理', closable: true } },
    { path: '/:section/:feature', name: 'feature', component: PlaceholderView },
    { path: '/:pathMatch(.*)*', redirect: '/workspace' },
  ],
})

router.beforeEach(async to => {
  if (to.path === '/tenant/login' || to.path === '/platform/login') return true

  const platformRoute = to.path.startsWith('/platform/')
  const endpoint = platformRoute ? '/api/v1/platform/auth/me' : '/api/v1/tenant/auth/me'
  try {
    const response = await fetch(endpoint, { credentials: 'include', cache: 'no-store' })
    if (response.ok) return true
  } catch {
    // 网络错误也不能放行未认证用户进入受保护工作区。
  }
  return {
    path: platformRoute ? '/platform/login' : '/tenant/login',
    query: { redirect: to.fullPath },
  }
})
