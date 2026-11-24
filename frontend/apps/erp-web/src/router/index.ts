import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceHome from '../views/WorkspaceHome.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/workspace' },
    { path: '/workspace', name: 'workspace', component: WorkspaceHome, meta: { title: '工作台', closable: false } },
    { path: '/:section/:feature', name: 'feature', component: PlaceholderView },
    { path: '/:pathMatch(.*)*', redirect: '/workspace' },
  ],
})