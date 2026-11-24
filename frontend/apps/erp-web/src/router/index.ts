import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceHome from '../views/WorkspaceHome.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
import PlatformLogin from '../views/PlatformLogin.vue'
import PlatformAdmin from '../views/PlatformAdmin.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/platform/login' },
    { path: '/workspace', name: 'workspace', component: WorkspaceHome, meta: { title: '工作台', closable: false } },
    { path: '/platform/login', name: 'platform-login', component: PlatformLogin, meta: { title: '平台管理员登录', closable: true } },
    { path: '/platform/admin', name: 'platform-admin', component: PlatformAdmin, meta: { title: '平台控制台', closable: true } },
    { path: '/:section/:feature', name: 'feature', component: PlaceholderView },
    { path: '/:pathMatch(.*)*', redirect: '/workspace' },
  ],
})
