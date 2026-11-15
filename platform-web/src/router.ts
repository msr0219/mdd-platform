import { createRouter, createWebHistory, RouterView } from 'vue-router'
import type { Menu } from './types'
import Dashboard from './views/Dashboard.vue'
import ModelStatus from './views/ModelStatus.vue'
import ViewRenderer from './views/ViewRenderer.vue'
import NotFound from './views/NotFound.vue'
import UserManagement from './views/UserManagement.vue'
import ServiceLogs from './views/ServiceLogs.vue'
import { auth, can } from './auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: Dashboard, meta: { title: '工作台' } },
    { path: '/login', name: 'login', component: NotFound, meta: { title: '登录' } },
    { path: '/platform/users', name: 'users', component: UserManagement, meta: { title: '用户与角色', permission: 'users:manage' } },
    { path: '/platform/service-logs', name: 'service-logs', component: ServiceLogs, meta: { title: '服务调用日志', permission: 'users:manage' } },
    { path: '/platform/models', name: 'model-status', component: ModelStatus, meta: { title: '模型管理', permission: 'meta:inspect' } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound, meta: { title: '页面未找到' } },
  ],
})
const removers: (() => void)[] = []
router.beforeEach(to => {
  if (!auth.user || auth.user.mustChangePassword) return to.path === '/login' ? true : '/login'
  if (to.path === '/login') return '/'
  if (!can(to.meta.permission as string | undefined)) return '/'
  return true
})
export function menuPath(menu: Menu, parent = ''): string { return `${parent}/${menu.code}` }
export function syncRoutes(menus: Menu[]) {
  removers.splice(0).forEach(remove => remove())
  const build = (nodes: Menu[], parent = ''): any[] => [...nodes].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).map(menu => {
    const path = menuPath(menu, parent)
    const meta = { title: menu.name, viewCode: menu.view, permission: menu.permission }
    if (menu.children?.length) return { path, name: `menu:${menu.code}`, component: RouterView, meta, children: build(menu.children, path) }
    return { path, name: `menu:${menu.code}`, component: RouterView, meta, children: [
      { path: '', name: `view:${menu.code}`, component: ViewRenderer, meta },
      { path: ':id', name: `detail:${menu.code}`, component: ViewRenderer, meta },
    ] }
  })
  build(menus).forEach(route => removers.push(router.addRoute(route)))
}
