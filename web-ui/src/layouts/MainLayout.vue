<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { coreMenus } from '../router'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { socket, url } from '../api'
import { locale, setLocale, t } from '../i18n'
import { buildNavigation, navigationGroup } from '../utils/navigation'

const auth = useAuth()
const app = useApp()
const route = useRoute()
const router = useRouter()
const logo = computed(() => url(app.info?.logoUrl || '/favicon.svg'))
const brandName = computed(() => app.info?.brand || 'LunarCore 琉月')
const wsOnline = ref(false)
const narrow = ref(window.innerWidth < 900)
const mobileOpen = ref(false)
const menuQuery = ref('')
const sidebar = ref()
const menuScroll = ref()
const menuToggle = ref()
const searchInput = ref()
const menuError = ref(false)
const menuLoading = ref(false)
const groupStorageKey = 'lc-nav-open-groups'

function readOpenGroups() {
  try {
    const value = JSON.parse(localStorage.getItem(groupStorageKey) || '[]')
    return Array.isArray(value) ? value.filter(id => typeof id === 'string') : []
  } catch { return [] }
}
const openGroups = ref(readOpenGroups())
const collapsed = computed(() => !narrow.value && app.collapsed)
const navigation = computed(() => buildNavigation(coreMenus, app.panels, permission => auth.can(permission), t))
const groups = computed(() => menuQuery.value.trim()
  ? buildNavigation(coreMenus, app.panels, permission => auth.can(permission), t, menuQuery.value)
  : navigation.value)
const active = computed(() => {
  if (route.name === 'panel') {
    if (route.params.ns === 'anticheat' && app.panels.some(panel => panel.namespace === 'ban')) return '/panel/ban/ban'
    return `/panel/${route.params.ns}/${route.params.id}`
  }
  return route.path || '/'
})
const activeGroup = computed(() => navigation.value.find(group => group.items.some(item => item.key === active.value))?.id)
const workspaceGroup = computed(() => {
  const item = route.name === 'panel'
    ? app.findPanel(route.params.ns, route.params.id)
    : coreMenus.find(item => item.path === route.path)
  return t(item ? navigationGroup(item).title : '工作空间')
})
const title = computed(() => route.name === 'panel'
  ? t(app.findPanel(route.params.ns, route.params.id)?.title || '扩展面板')
  : route.meta.title ? t(route.meta.title) : '')
const collapseLabel = computed(() => t(narrow.value ? '关闭导航' : collapsed.value ? '展开导航' : '收起导航'))

function saveOpenGroups(value) {
  openGroups.value = value
  try { localStorage.setItem(groupStorageKey, JSON.stringify(value)) } catch { /* Storage may be unavailable. */ }
}
function groupOpen(id) {
  return collapsed.value || id === 'overview' || !!menuQuery.value.trim() || openGroups.value.includes(id)
}
function toggleGroup(id) {
  saveOpenGroups(openGroups.value.includes(id)
    ? openGroups.value.filter(value => value !== id)
    : [...openGroups.value, id])
}
watch(activeGroup, group => {
  if (group && group !== 'overview' && !openGroups.value.includes(group)) saveOpenGroups([...openGroups.value, group])
}, { immediate: true })

watch([active, collapsed], async () => {
  await nextTick()
  const item = menuScroll.value?.querySelector('[aria-current="page"]')
  if (!item || !menuScroll.value) return
  const bounds = menuScroll.value.getBoundingClientRect()
  const rect = item.getBoundingClientRect()
  const delta = rect.top < bounds.top ? rect.top - bounds.top - 6
    : rect.bottom > bounds.bottom ? rect.bottom - bounds.bottom + 6 : 0
  if (delta) menuScroll.value.scrollTop += delta
}, { immediate: true, flush: 'post' })

async function closeMobile() {
  mobileOpen.value = false
  await nextTick()
  menuToggle.value?.$el?.focus()
}
async function toggleMenu() {
  if (!narrow.value) {
    menuQuery.value = ''
    app.toggleCollapse()
    return
  }
  if (mobileOpen.value) return closeMobile()
  mobileOpen.value = true
  await nextTick()
  searchInput.value?.focus()
}
async function onSelect(key) {
  const wasMobile = narrow.value && mobileOpen.value
  const failure = route.fullPath === key ? null : await router.push(key)
  if (failure) return
  mobileOpen.value = false
  menuQuery.value = ''
  await nextTick()
  if (wasMobile) document.getElementById('main-content')?.focus({ preventScroll: true })
}
function onNavigationClick(event, key) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  return onSelect(key)
}
async function logout() {
  const confirmed = await ElMessageBox.confirm(t('确定要退出登录吗？'), t('退出'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!confirmed) return
  await auth.logout()
  router.replace({ name: 'login' })
}
function onCommand(command) {
  if (command === 'profile') router.push({ name: 'profile' })
  else if (command === 'logout') logout()
  else if (command === 'refresh') refreshPanels()
}
function onResize() {
  const next = window.innerWidth < 900
  if (next !== narrow.value) mobileOpen.value = false
  narrow.value = next
}
function visibleFocusable(root) {
  if (!root) return []
  return [...root.querySelectorAll('button, input, a[href]')]
    .filter(element => !element.disabled && element.getClientRects().length)
}
function onNavigationKeydown(event) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key) || event.target.tagName === 'INPUT') return
  const targets = visibleFocusable(event.currentTarget)
  const index = targets.indexOf(event.target)
  if (index < 0) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? targets.length - 1
    : (index + (event.key === 'ArrowDown' ? 1 : -1) + targets.length) % targets.length
  targets[next]?.focus()
}
function onKeydown(event) {
  if (!narrow.value || !mobileOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMobile()
  } else if (event.key === 'Tab') {
    const targets = visibleFocusable(sidebar.value)
    const edge = event.shiftKey ? targets[0] : targets[targets.length - 1]
    if (document.activeElement === edge) {
      event.preventDefault()
      targets[event.shiftKey ? targets.length - 1 : 0]?.focus()
    }
  }
}
watch(() => route.fullPath, () => { mobileOpen.value = false })

let offStatus
let panelRefresh
async function refreshPanels() {
  if (document.hidden || menuLoading.value) return
  menuLoading.value = true
  try {
    await app.loadPanels()
    menuError.value = false
  } catch {
    menuError.value = true
  } finally {
    menuLoading.value = false
  }
}
onMounted(() => {
  app.loadInfo()
  refreshPanels()
  panelRefresh = window.setInterval(refreshPanels, 30000)
  document.addEventListener('visibilitychange', refreshPanels)
  window.addEventListener('resize', onResize)
  document.addEventListener('keydown', onKeydown)
  offStatus = socket.onStatus(value => { wsOnline.value = value })
  socket.connect()
})
onUnmounted(() => {
  window.clearInterval(panelRefresh)
  document.removeEventListener('visibilitychange', refreshPanels)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('keydown', onKeydown)
  offStatus?.()
})
</script>

<template>
  <div class="app-shell" :class="{ 'navigation-collapsed': collapsed }">
    <a class="skip-link" href="#main-content">{{ t('跳到主要内容') }}</a>
    <Transition name="backdrop">
      <button v-if="narrow && mobileOpen" class="mobile-backdrop" :aria-label="t('关闭导航')" tabindex="-1" @click="closeMobile" />
    </Transition>
    <aside ref="sidebar" class="app-sidebar" :class="{ 'mobile-open': mobileOpen }" :inert="narrow && !mobileOpen || undefined" :aria-hidden="narrow && !mobileOpen || undefined">
      <button class="brand" :aria-label="t('返回仪表盘')" :title="brandName" @click="onSelect('/')">
        <img :src="logo" class="brand-logo" alt="" />
        <span v-if="!collapsed" class="brand-name">{{ brandName }}</span>
      </button>
      <div v-if="!collapsed" class="navigation-search">
        <Icon name="search" :size="15" />
        <input ref="searchInput" v-model="menuQuery" :aria-label="t('搜索管理功能')" :placeholder="t('搜索管理功能')" />
        <button v-if="menuQuery" type="button" :aria-label="t('清除搜索')" @click="menuQuery = ''"><Icon name="x" :size="14" /></button>
      </div>
      <nav id="primary-navigation" ref="menuScroll" class="navigation" :aria-label="t('主导航')" @keydown="onNavigationKeydown">
        <section v-for="group in groups" :key="group.id" class="navigation-group" :class="{ 'overview-group': group.id === 'overview' }">
          <button v-if="group.id !== 'overview' && !collapsed" type="button" class="group-toggle" :aria-expanded="groupOpen(group.id)" :aria-controls="`navigation-${group.id}`" @click="toggleGroup(group.id)">
            <span>{{ group.title }}</span><Icon name="chevron-down" :size="13" :class="{ closed: !groupOpen(group.id) }" />
          </button>
          <span v-else-if="collapsed && group.id !== 'overview'" class="group-divider" :title="group.title" />
          <ul v-show="groupOpen(group.id)" :id="`navigation-${group.id}`" class="navigation-items">
            <li v-for="item in group.items" :key="item.key">
              <el-tooltip :disabled="!collapsed" :content="item.title" placement="right" :show-after="250">
                <a :href="router.resolve(item.key).href" class="navigation-link" :class="{ selected: item.key === active }" :aria-current="item.key === active ? 'page' : undefined" :aria-label="collapsed ? item.title : undefined" @click="event => onNavigationClick(event, item.key)">
                  <Icon :name="item.icon" :size="17" />
                  <span v-if="!collapsed">{{ item.title }}</span>
                </a>
              </el-tooltip>
            </li>
          </ul>
        </section>
        <div v-if="!groups.length" class="navigation-empty">{{ t('未找到匹配的功能') }}</div>
        <div v-if="menuError && !collapsed" class="navigation-error">
          <span>{{ t('无法加载导航') }}</span>
          <button type="button" :disabled="menuLoading" @click="refreshPanels"><Icon name="refresh-cw" :size="13" />{{ t('重试') }}</button>
        </div>
      </nav>
      <div class="sidebar-footer">
        <span v-if="!collapsed" class="sidebar-caption">{{ t('管理控制台') }}</span>
        <el-tooltip :content="collapseLabel" placement="right">
          <button type="button" class="icon-control" :aria-label="collapseLabel" @click="toggleMenu"><Icon :name="narrow ? 'x' : collapsed ? 'panel-left-open' : 'panel-left-close'" :size="17" /></button>
        </el-tooltip>
      </div>
    </aside>
    <div class="app-body" :inert="narrow && mobileOpen || undefined">
      <header class="app-header">
        <el-button ref="menuToggle" text circle class="header-toggle" :aria-label="t('展开或折叠菜单')" :aria-expanded="narrow ? mobileOpen : !collapsed" aria-controls="primary-navigation" @click="toggleMenu">
          <Icon :name="collapsed || narrow ? 'panel-left-open' : 'panel-left-close'" :size="18" />
        </el-button>
        <div class="header-location">
          <span class="header-group">{{ workspaceGroup }}</span>
          <Icon name="chevron-right" :size="12" class="location-divider" />
          <span class="header-title">{{ title }}</span>
        </div>
        <div class="header-actions">
          <el-tooltip :content="wsOnline ? t('实时连接正常') : t('实时连接已断开（自动重连中）')">
            <span class="connection-status" :class="{ online: wsOnline }" role="status" :aria-label="wsOnline ? t('实时连接正常') : t('实时连接已断开（自动重连中）')">
              <span class="status-dot" /><span class="connection-label">{{ wsOnline ? t('实时连接') : t('连接重试中') }}</span>
            </span>
          </el-tooltip>
          <el-dropdown trigger="click" @command="setLocale">
            <button type="button" class="icon-control language-control" :aria-label="t('语言')"><Icon name="languages" :size="17" /><span>{{ locale === 'en_US' ? 'EN' : '中' }}</span></button>
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="zh_CN" :disabled="locale === 'zh_CN'">中文</el-dropdown-item>
              <el-dropdown-item command="en_US" :disabled="locale === 'en_US'">English</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
          <el-tooltip :content="app.dark ? t('切换到亮色') : t('切换到暗色')">
            <button type="button" class="icon-control theme-control" :aria-label="app.dark ? t('切换到亮色') : t('切换到暗色')" @click="app.toggleTheme()"><Icon :name="app.dark ? 'sun' : 'moon'" :size="17" /></button>
          </el-tooltip>
          <el-dropdown trigger="click" @command="onCommand">
            <button type="button" class="account-control" :aria-label="t('账户菜单')">
              <img v-if="auth.user?.mcName" :src="`https://mc-heads.net/avatar/${encodeURIComponent(auth.user.mcName)}/24`" alt="" class="user-avatar" />
              <Icon v-else name="circle-user-round" :size="21" />
              <span class="user-name">{{ auth.user?.username }}</span><Icon name="chevron-down" :size="12" />
            </button>
            <template #dropdown><el-dropdown-menu class="account-menu">
              <div class="account-summary"><strong>{{ auth.user?.username }}</strong><span>{{ auth.user?.roleName }}</span></div>
              <el-dropdown-item command="profile"><Icon name="user-cog" :size="16" />{{ t('个人设置') }}</el-dropdown-item>
              <el-dropdown-item command="refresh"><Icon name="refresh-cw" :size="16" />{{ t('刷新菜单') }}</el-dropdown-item>
              <el-dropdown-item command="logout" divided><Icon name="log-out" :size="16" />{{ t('退出登录') }}</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main id="main-content" class="app-main" tabindex="-1">
        <router-view v-slot="{ Component, route: current }">
          <Transition name="route" mode="out-in"><component :is="Component" :key="current.fullPath" /></Transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell { --sidebar-width: 236px; display: grid; grid-template-columns: var(--sidebar-width) minmax(0, 1fr); height: 100dvh; min-height: 0; }
.app-shell.navigation-collapsed { --sidebar-width: 64px; }
.app-sidebar { display: flex; flex-direction: column; min-height: 0; min-width: 0; background: var(--lc-surface); border-right: 1px solid var(--lc-line); overflow: hidden; }
.brand { display: flex; align-items: center; gap: 10px; height: var(--lc-header-height); min-height: var(--lc-header-height); width: 100%; padding: 0 18px; border: 0; border-bottom: 1px solid var(--lc-line); background: none; text-align: left; cursor: pointer; color: var(--lc-text); }
.brand-logo { width: 28px; height: 28px; flex: none; object-fit: contain; }
.brand-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 650; font-size: 14px; }
.navigation-search { display: flex; align-items: center; gap: 8px; height: 34px; flex: none; margin: 16px 14px 10px; padding: 0 9px; border: 1px solid var(--lc-line); border-radius: 5px; color: var(--lc-text-soft); background: var(--lc-bg); }
.navigation-search:focus-within { border-color: var(--lc-accent); box-shadow: 0 0 0 2px var(--el-color-primary-light-9); }
.navigation-search input { min-width: 0; width: 100%; border: 0; outline: none; background: transparent; color: var(--lc-text); font-size: 12px; }
.navigation-search input::placeholder { color: var(--lc-text-soft); }
.navigation-search button { flex: none; display: grid; place-items: center; width: 22px; height: 24px; padding: 0; border: 0; color: inherit; background: none; cursor: pointer; }
.navigation { flex: 1; min-height: 0; padding: 0 10px 20px; overflow-y: auto; overflow-x: hidden; scrollbar-width: thin; overscroll-behavior: contain; }
.navigation-group { margin-top: 12px; }
.navigation-group.overview-group { margin-top: 0; padding-bottom: 9px; border-bottom: 1px solid var(--lc-line); }
.group-toggle { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; height: 28px; padding: 0 10px; border: 0; color: var(--lc-text-soft); background: none; cursor: pointer; font-size: 11px; font-weight: 600; }
.group-toggle:hover { color: var(--lc-text); }
.group-toggle .lc-icon { transition: transform 240ms var(--motion-spring-soft); }
.group-toggle .closed { transform: rotate(-90deg); }
.navigation-items { list-style: none; padding: 0; margin: 0; }
.navigation-items li + li { margin-top: 2px; }
.navigation-link { display: flex; align-items: center; gap: 10px; min-width: 0; min-height: 35px; padding: 7px 10px; border-radius: 5px; color: var(--el-text-color-regular); text-decoration: none; font-size: 13px; line-height: 1.45; transition: color 140ms, background-color 140ms; }
.navigation-link .lc-icon { color: var(--lc-text-soft); }
.navigation-link > span { min-width: 0; overflow-wrap: anywhere; }
.navigation-link:hover { color: var(--lc-text); background: var(--el-fill-color-light); }
.navigation-link.selected { color: var(--lc-accent); background: var(--el-color-primary-light-9); font-weight: 600; }
.navigation-link.selected .lc-icon { color: inherit; }
.navigation-link:focus-visible, .group-toggle:focus-visible { outline-offset: -2px; }
.group-divider { display: block; height: 1px; margin: 0 9px 10px; background: var(--lc-line); }
.navigation-empty { padding: 12px 10px; color: var(--lc-text-soft); font-size: 12px; }
.navigation-error { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 16px 6px 0; color: var(--el-color-warning); font-size: 12px; }
.navigation-error button { display: inline-flex; align-items: center; gap: 4px; padding: 0; border: 0; background: none; color: var(--lc-accent); cursor: pointer; font-size: 12px; }
.sidebar-footer { height: 48px; flex: none; display: flex; align-items: center; justify-content: space-between; padding: 0 14px 0 20px; border-top: 1px solid var(--lc-line); }
.sidebar-caption { color: var(--lc-text-soft); font-size: 11px; }
.icon-control { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-width: 32px; height: 32px; padding: 0 7px; border: 0; border-radius: 5px; color: var(--el-text-color-regular); background: transparent; cursor: pointer; transition: background-color 140ms, color 140ms; }
.icon-control:hover { color: var(--lc-text); background: var(--el-fill-color-light); }
.navigation-collapsed .brand { justify-content: center; padding: 0; }
.navigation-collapsed .navigation { padding-top: 14px; }
.navigation-collapsed .navigation-link { justify-content: center; padding: 9px; min-height: 36px; }
.navigation-collapsed .sidebar-footer { justify-content: center; padding: 0; }
.app-body { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.app-header { height: var(--lc-header-height); flex: none; display: flex; align-items: center; gap: 12px; padding: 0 22px; border-bottom: 1px solid var(--lc-line); background: var(--lc-surface); }
.header-toggle { flex: none; }
.header-location { min-width: 0; display: flex; align-items: center; gap: 10px; font-size: 12px; }
.header-group, .location-divider { color: var(--lc-text-soft); flex: none; }
.header-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--el-text-color-regular); }
.header-actions { margin-left: auto; display: flex; align-items: center; gap: 5px; flex: none; }
.connection-status { display: inline-flex; align-items: center; gap: 6px; color: var(--el-color-warning); font-size: 11px; white-space: nowrap; margin-right: 12px; }
.connection-status.online { color: var(--el-color-success); }
.status-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: currentColor; }
.language-control { font-size: 11px; }
.account-control { display: flex; align-items: center; gap: 7px; height: 34px; max-width: 180px; padding: 0 6px; border: 0; border-radius: 5px; background: transparent; color: var(--el-text-color-regular); cursor: pointer; }
.account-control:hover { background: var(--el-fill-color-light); }
.user-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 500; }
.user-avatar { width: 24px; height: 24px; object-fit: contain; border-radius: 4px; image-rendering: pixelated; }
.account-summary { display: flex; flex-direction: column; gap: 2px; padding: 7px 14px 10px; margin-bottom: 4px; border-bottom: 1px solid var(--lc-line); max-width: 240px; overflow-wrap: anywhere; }
.account-summary strong { font-size: 13px; font-weight: 600; }
.account-summary span { color: var(--lc-text-soft); font-size: 11px; }
.app-main { flex: 1; min-width: 0; min-height: 0; overflow: auto; overscroll-behavior: contain; background: var(--lc-bg); }
.app-main:focus { outline: none; }
.mobile-backdrop { position: fixed; inset: 0; z-index: 29; border: 0; background: #171a2080; }
.backdrop-enter-active, .backdrop-leave-active { transition: opacity 180ms ease; }
.backdrop-enter-from, .backdrop-leave-to { opacity: 0; }
@media (max-width: 1100px) { .connection-label { display: none; } .connection-status { margin-right: 5px; } }
@media (max-width: 899px) {
  .app-shell { display: block; }
  .app-body { height: 100%; }
  .app-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; width: 268px; max-width: calc(100vw - 48px); transform: translateX(-100%); transition: transform 320ms var(--motion-spring-soft); }
  .app-sidebar.mobile-open { transform: translateX(0); }
  .app-header { padding: 0 12px; gap: 8px; }
  .header-group, .location-divider { display: none; }
  .header-location { flex: 1; }
  .header-actions { gap: 2px; margin-left: 0; }
  .user-name, .account-control > .lc-icon:last-child { display: none; }
  .account-control { width: 32px; padding: 0; justify-content: center; }
  .language-control span { display: none; }
  .connection-status { margin-right: 6px; }
  .navigation-link { min-height: 40px; }
  .group-toggle { height: 32px; }
}
@media (max-width: 360px) { .theme-control { display: none; } .app-header { padding: 0 8px; gap: 4px; } }
</style>
