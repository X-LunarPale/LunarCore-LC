import { createRouter, createWebHistory } from 'vue-router'
import { watch } from 'vue'
import { BASE } from './api'
import { useAuth } from './stores/auth'
import { useApp } from './stores/app'
import { ElMessage } from 'element-plus'
import { locale, t } from './i18n.js'

export const coreMenus = [
  { path: '/', name: 'dashboard', title: '仪表盘', icon: 'layout-dashboard', group: '概览', perm: 'core.dashboard', component: () => import('./views/Dashboard.vue') },
  { path: '/players', name: 'players', title: '玩家', icon: 'users', group: '概览', perm: 'core.players.view', component: () => import('./views/Players.vue') },
  { path: '/console', name: 'console', title: '控制台', icon: 'terminal', group: '概览', perm: 'core.console.view', component: () => import('./views/Console.vue') },
  { path: '/server', name: 'server', title: '服务器操作', icon: 'server', group: '概览', perm: 'core.server.manage', component: () => import('./views/Server.vue') },
  { path: '/modules', name: 'modules', title: '模块管理', icon: 'blocks', group: '系统', perm: 'core.modules.view', component: () => import('./views/Modules.vue') },
  { path: '/plugins', name: 'plugins', title: '插件', icon: 'puzzle', group: '系统', perm: 'core.plugins.view', component: () => import('./views/Plugins.vue') },
  { path: '/audit', name: 'audit', title: '操作日志', icon: 'scroll-text', group: '系统', perm: 'core.audit.view', component: () => import('./views/Audit.vue') },
  { path: '/config', name: 'config', title: '服务器配置', icon: 'file-cog', group: '系统', perm: 'core.config.edit', component: () => import('./views/Config.vue') },
  { path: '/accounts', name: 'accounts', title: '账号与角色', icon: 'shield-check', group: '系统', perm: 'core.users.manage', component: () => import('./views/Accounts.vue') }
]

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('./layouts/MainLayout.vue'),
    children: [
      ...coreMenus.map((m) => ({
        path: m.path === '/' ? '' : m.path.slice(1),
        name: m.name,
        component: m.component,
        meta: { title: m.title, perm: m.perm, group: m.group }
      })),
      { path: 'profile', name: 'profile', component: () => import('./views/Profile.vue'), meta: { title: '个人设置' } },
      { path: 'panel/:ns/:id', name: 'panel', component: () => import('./views/PanelView.vue'), meta: { title: '扩展面板' } },
      { path: 'welcome', name: 'welcome', component: () => import('./views/Welcome.vue'), meta: { title: '欢迎' } },
      { path: ':pathMatch(.*)*', name: 'notfound', component: () => import('./views/NotFound.vue'), meta: { title: '页面不存在' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(BASE + '/'),
  routes
})

function firstAccessibleRoute(auth, app) {
  const first = coreMenus.find((m) => auth.can(m.perm))
  if (first) return { name: first.name }
  if (app.panels.length) return { name: 'panel', params: { ns: app.panels[0].namespace, id: app.panels[0].id } }
  return { name: 'welcome' }
}

router.beforeEach(async (to) => {
  const auth = useAuth()
  if (to.meta.public) return true
  if (!auth.token) return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  const app = useApp()
  if (!app.panelsLoaded) {
    try {
      await app.loadPanels()
    } catch {
      /* ignore */
    }
  }
  if (to.meta.perm && !auth.can(to.meta.perm)) {
    if (to.name !== 'dashboard') ElMessage.warning(t('当前账号没有访问此页面的权限'))
    return firstAccessibleRoute(auth, app)
  }
  return true
})

export function updateTitle(route) {
  const brand = useApp().info?.brand || 'LunarCore 琉月'
  document.title = (route.meta.title ? t(route.meta.title) + ' - ' : '') + brand
}
router.afterEach(updateTitle)
watch(locale, () => updateTitle(router.currentRoute.value))

export default router
