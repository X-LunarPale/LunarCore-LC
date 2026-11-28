<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { coreMenus } from '../router'
import AppearanceControls from '../components/AppearanceControls.vue'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { socket, url } from '../api'
import { t } from '../i18n'
import { buildNavigation } from '../utils/navigation'

const auth = useAuth()
const app = useApp()
const route = useRoute()
const router = useRouter()
const logo = computed(() => url(app.info?.logoUrl || '/favicon.svg'))
const brandName = computed(() => app.info?.brand || 'LunarCore 琉月')
const wsOnline = ref(false)
const mobileViewport = window.matchMedia('(max-width: 899px)')
const narrow = ref(mobileViewport.matches)
const mobileOpen = ref(false)
const menuQuery = ref('')
const sidebar = ref()
const menuScroll = ref()
const menuToggle = ref()
const searchInput = ref()
const main = ref()
const sidebarToggle = ref()
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
    const ban = route.params.ns === 'anticheat' && app.panels.find(panel => panel.namespace === 'ban')
    if (ban) return `/panel/${ban.namespace}/${ban.id}`
    return `/panel/${route.params.ns}/${route.params.id}`
  }
  return route.path || '/'
})
const activeGroup = computed(() => navigation.value.find(group => group.items.some(item => item.key === active.value))?.id)
const collapseLabel = computed(() => t(narrow.value ? '关闭导航' : collapsed.value ? '展开导航' : '收起导航'))
const connectionLabel = computed(() => t(wsOnline.value ? '实时连接正常' : '实时连接已断开（自动重连中）'))

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
  menuToggle.value?.focus()
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
  if (wasMobile) focusMain()
}
function focusMain() {
  main.value?.focus({ preventScroll: true })
}
function onNavigationClick(event, key) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  return onSelect(key)
}
function openFirstResult(event) {
  if (event.isComposing || !menuQuery.value.trim()) return
  const item = groups.value[0]?.items[0]
  if (item) {
    event.preventDefault()
    onSelect(item.to || item.key)
  }
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
function onViewportChange(event) {
  const focusInNavigation = sidebar.value?.contains(document.activeElement) || document.activeElement === menuToggle.value
  mobileOpen.value = false
  narrow.value = event.matches
  if (focusInNavigation) nextTick(() => (narrow.value ? menuToggle.value : sidebarToggle.value)?.focus())
}
function visibleFocusable(root) {
  if (!root) return []
  return [...root.querySelectorAll('button, input, a[href]')]
    .filter(element => !element.disabled && !element.closest('[inert]') && element.getClientRects().length)
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
watch(() => route.fullPath, () => {
  mobileOpen.value = false
  if (main.value) main.value.scrollTop = 0
})

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
  mobileViewport.addEventListener('change', onViewportChange)
  document.addEventListener('keydown', onKeydown)
  offStatus = socket.onStatus(value => { wsOnline.value = value })
  socket.connect()
})
onUnmounted(() => {
  window.clearInterval(panelRefresh)
  document.removeEventListener('visibilitychange', refreshPanels)
  mobileViewport.removeEventListener('change', onViewportChange)
  document.removeEventListener('keydown', onKeydown)
  offStatus?.()
})
</script>

<template>
  <div class="app-shell" :class="{ 'navigation-collapsed': collapsed }">
    <a v-if="!mobileOpen" class="skip-link" href="#main-content" @click.prevent="focusMain">{{ t('跳到主要内容') }}</a>
    <Transition name="backdrop">
      <button v-if="narrow && mobileOpen" class="mobile-backdrop" :aria-label="t('关闭导航')" tabindex="-1" @click="closeMobile" />
    </Transition>
    <aside ref="sidebar" class="app-sidebar" :class="{ 'mobile-open': mobileOpen }" :inert="narrow && !mobileOpen || undefined" :aria-hidden="narrow && !mobileOpen || undefined">
      <div class="sidebar-heading">
        <button v-if="!collapsed" type="button" class="brand" :aria-label="t('返回仪表盘')" :title="brandName" @click="onSelect('/')">
          <img :src="logo" class="brand-logo" alt="" />
          <span class="brand-name">{{ brandName }}</span>
        </button>
        <button ref="sidebarToggle" type="button" class="lc-icon-button" :aria-label="collapseLabel" :title="collapseLabel" :aria-expanded="!collapsed" aria-controls="primary-navigation" @click="toggleMenu">
          <Icon :name="narrow ? 'x' : collapsed ? 'panel-left-open' : 'panel-left-close'" :size="17" />
        </button>
      </div>
      <div v-if="!collapsed" class="navigation-search">
        <Icon name="search" :size="15" />
        <input ref="searchInput" v-model="menuQuery" :aria-label="t('搜索管理功能')" :placeholder="t('搜索')" @keydown.enter="openFirstResult" />
        <button v-if="menuQuery" type="button" :aria-label="t('清除搜索')" @click="menuQuery = ''"><Icon name="x" :size="14" /></button>
      </div>
      <nav id="primary-navigation" ref="menuScroll" class="navigation" :aria-label="t('主导航')" @keydown="onNavigationKeydown">
        <section v-for="group in groups" :key="group.id" class="navigation-group" :class="{ 'overview-group': group.id === 'overview' }">
          <button v-if="group.id !== 'overview' && !collapsed" type="button" class="group-toggle" :aria-expanded="groupOpen(group.id)" :aria-controls="`navigation-${group.id}`" @click="toggleGroup(group.id)">
            <span>{{ group.title }}</span><Icon name="chevron-down" :size="13" :class="{ closed: !groupOpen(group.id) }" />
          </button>
          <span v-else-if="collapsed && group.id !== 'overview'" class="group-divider" :title="group.title" />
          <div :id="`navigation-${group.id}`" class="navigation-fold" :class="{ 'is-open': groupOpen(group.id) }" :inert="!groupOpen(group.id) || undefined">
            <ul class="navigation-items">
              <li v-for="item in group.items" :key="item.key">
                <a :href="router.resolve(item.to || item.key).href" class="navigation-link"
                  :aria-current="(item.to ? route.fullPath === item.to : item.key === active) ? 'page' : undefined"
                  :aria-label="collapsed ? item.title : undefined" :title="collapsed ? item.title : undefined"
                  @click="event => onNavigationClick(event, item.to || item.key)">
                  <Icon :name="item.icon" :size="16" />
                  <span v-if="!collapsed">{{ item.title }}</span>
                </a>
              </li>
            </ul>
          </div>
        </section>
        <div v-if="!groups.length" class="navigation-empty">{{ t('未找到匹配的功能') }}</div>
        <div v-if="menuError && !collapsed" class="navigation-error">
          <span>{{ t('无法加载导航') }}</span>
          <button type="button" :disabled="menuLoading" @click="refreshPanels"><Icon name="refresh-cw" :size="13" />{{ t('重试') }}</button>
        </div>
      </nav>
    </aside>
    <div class="app-body" :inert="narrow && mobileOpen || undefined">
      <header class="app-header">
        <button v-if="narrow" ref="menuToggle" class="lc-icon-button" type="button" :aria-label="t('展开导航')" :aria-expanded="mobileOpen" aria-controls="primary-navigation" @click="toggleMenu">
          <Icon name="panel-left-open" :size="18" />
        </button>
        <span v-if="narrow || collapsed" class="header-brand">{{ brandName }}</span>
        <span class="connection-status" role="status" :class="{ online: wsOnline }" :aria-label="connectionLabel" :title="connectionLabel">
          <Icon :name="wsOnline ? 'link' : 'triangle-alert'" :size="15" />
        </span>
        <div class="header-actions">
          <AppearanceControls />
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
      <main id="main-content" ref="main" class="app-main" tabindex="-1">
        <router-view v-slot="{ Component, route: current }">
          <Transition name="route"><component :is="Component" :key="current.name === 'panel' ? current.path : current.fullPath" /></Transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell { --sidebar-width: 224px; display: grid; grid-template-columns: var(--sidebar-width) minmax(0, 1fr); height: 100dvh; min-height: 0; transition: grid-template-columns var(--motion-standard) var(--motion-spring-soft); }
.app-shell.navigation-collapsed { --sidebar-width: 56px; }
.app-sidebar { display: flex; flex-direction: column; min-height: 0; min-width: 0; background: var(--lc-sidebar); border-right: 1px solid var(--lc-line); overflow: hidden; }
.sidebar-heading { display: flex; flex: none; align-items: center; justify-content: space-between; gap: 6px; height: var(--lc-header-height); padding: 0 12px; }
.brand { display: flex; align-items: center; gap: 8px; min-width: 0; height: 36px; padding: 0 5px; border: 0; border-radius: var(--lc-radius-control); background: none; text-align: left; cursor: pointer; color: var(--lc-text); }
.brand:hover { background: var(--el-fill-color); }
.brand-logo { width: 24px; height: 24px; flex: none; object-fit: contain; }
.brand-name, .header-brand { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 650; font-size: 13px; }
.navigation-search { display: flex; align-items: center; gap: 8px; height: 34px; flex: none; margin: 5px 12px 12px; padding: 0 9px; border: 1px solid var(--lc-line); border-radius: var(--lc-radius-control); color: var(--lc-text-soft); background: var(--lc-surface); transition: border-color var(--motion-fast), box-shadow var(--motion-fast); }
.navigation-search:focus-within { border-color: var(--lc-focus-ink); box-shadow: var(--lc-focus-halo); }
.navigation-search input { min-width: 0; width: 100%; border: 0; outline: none; background: transparent; color: var(--lc-text); font-size: 12px; }
.navigation-search input::placeholder { color: var(--lc-text-soft); }
.navigation-search button { flex: none; display: grid; place-items: center; width: 22px; height: 24px; padding: 0; border: 0; color: inherit; background: none; cursor: pointer; }
.navigation { flex: 1; min-height: 0; padding: 0 10px 20px; overflow-y: auto; overflow-x: hidden; scrollbar-width: thin; overscroll-behavior: contain; }
.navigation-group + .navigation-group { margin-top: 10px; }
.group-toggle { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; min-height: 28px; padding: 0 9px; border: 0; border-radius: var(--lc-radius-control); color: var(--lc-text-soft); background: none; cursor: pointer; font-size: 12px; font-weight: 550; text-align: left; transition: color var(--motion-fast), transform var(--motion-standard) var(--motion-spring); }
.group-toggle:hover { color: var(--lc-text); }
.group-toggle:active { transform: scale(.98, .94); transition: transform var(--motion-press); }
.group-toggle .lc-icon { transition: transform var(--motion-standard) var(--motion-spring); }
.group-toggle .closed { transform: rotate(-90deg); }
.navigation-fold { display: grid; grid-template-rows: 0fr; opacity: 0; transition: grid-template-rows var(--motion-standard) var(--motion-spring-soft), opacity var(--motion-fast); }
.navigation-fold.is-open { grid-template-rows: 1fr; opacity: 1; }
.navigation-items { display: grid; gap: 2px; list-style: none; min-height: 0; overflow: hidden; padding: 0; margin: 0; }
.navigation-link { position: relative; display: flex; align-items: center; gap: 9px; min-width: 0; min-height: 34px; padding: 6px 9px 6px 12px; border-radius: var(--lc-radius-control); color: var(--el-text-color-regular); text-decoration: none; font-size: 13px; line-height: 1.45; transition: color var(--motion-fast), background-color var(--motion-fast), transform var(--motion-standard) var(--motion-spring); }
.navigation-link .lc-icon { color: var(--lc-text-soft); }
.navigation-link > span { min-width: 0; overflow-wrap: anywhere; }
.navigation-link:hover { color: var(--lc-text); background: var(--el-fill-color); }
.navigation-link[aria-current="page"] { color: var(--lc-text); background: var(--lc-selected-bg); box-shadow: var(--lc-selection-shadow); font-weight: 600; }
.navigation-link[aria-current="page"]::before { content: ""; position: absolute; inset-inline-start: 3px; top: 50%; width: 3px; height: 16px; border-radius: 2px; background: var(--lc-focus-ink); translate: 0 -50%; animation: lc-nav-marker-in var(--motion-standard) var(--motion-spring) both; }
@keyframes lc-nav-marker-in { from { opacity: .4; scale: 1 .52; } to { opacity: 1; scale: 1; } }
.navigation-link[aria-current="page"] .lc-icon { color: var(--lc-text); animation: lc-selection-in var(--motion-standard) var(--motion-spring) both; }
.navigation-link:active { transform: scale(.97, .92); transition: transform var(--motion-press); }
.navigation-link:focus-visible, .group-toggle:focus-visible { outline-offset: -2px; }
.group-divider { display: block; height: 1px; margin: 0 9px 10px; background: var(--lc-line); }
.navigation-empty { padding: 12px 10px; color: var(--lc-text-soft); font-size: 12px; }
.navigation-error { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 16px 6px 0; color: var(--el-color-warning); font-size: 12px; }
.navigation-error button { display: inline-flex; align-items: center; gap: 4px; padding: 0; border: 0; background: none; color: var(--lc-accent); cursor: pointer; font-size: 12px; }
.account-control { border: 0; border-radius: var(--lc-radius-control); color: var(--el-text-color-regular); background: transparent; cursor: pointer; transition: background-color var(--motion-fast), color var(--motion-fast), transform var(--motion-standard) var(--motion-spring); }
.account-control:hover { color: var(--lc-text); background: var(--el-fill-color); }
.account-control:active { transform: scale(.93); transition: transform var(--motion-press); }
.navigation-collapsed .sidebar-heading { justify-content: center; padding: 0; }
.navigation-collapsed .navigation { padding-top: 5px; }
.navigation-collapsed .navigation-link { justify-content: center; padding: 9px; min-height: 36px; }
.app-body { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.app-header { height: var(--lc-header-height); flex: none; display: flex; align-items: center; gap: 12px; padding: 0 var(--lc-page-gutter); border-bottom: 1px solid var(--lc-line); background: var(--lc-surface); }
.header-actions { margin-left: auto; display: flex; align-items: center; gap: 4px; flex: none; }
.connection-status { display: inline-flex; align-items: center; gap: 7px; color: var(--el-color-warning); font-size: 12px; white-space: nowrap; }
.connection-status.online { color: var(--el-color-success); }
.account-control { display: flex; align-items: center; gap: 7px; height: 34px; max-width: 180px; padding: 0 7px; }
.user-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 500; }
.user-avatar { width: 24px; height: 24px; object-fit: contain; border-radius: 4px; image-rendering: pixelated; }
.account-summary { display: flex; flex-direction: column; gap: 2px; padding: 7px 14px 10px; margin-bottom: 4px; border-bottom: 1px solid var(--lc-line); max-width: 240px; overflow-wrap: anywhere; }
.account-summary strong { font-size: 13px; font-weight: 600; }
.account-summary span { color: var(--lc-text-soft); font-size: 11px; }
.app-main { flex: 1; min-width: 0; min-height: 0; overflow: auto; overscroll-behavior: contain; background: var(--lc-surface); }
.app-main:focus { outline: none; }
.mobile-backdrop { position: fixed; inset: 0; z-index: 29; border: 0; background: #10111266; }
.backdrop-enter-active, .backdrop-leave-active { transition: opacity var(--motion-fast) ease; }
.backdrop-enter-from, .backdrop-leave-to { opacity: 0; }
@media (max-width: 899px) {
  .app-shell { display: block; }
  .app-body { height: 100%; }
  .app-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; width: 268px; max-width: calc(100vw - 48px); border-radius: 0 var(--lc-radius-shell) var(--lc-radius-shell) 0; transform: translateX(-100%); transition: transform var(--motion-standard) var(--motion-spring-soft); }
  .app-sidebar.mobile-open { transform: translateX(0); box-shadow: var(--lc-overlay-shadow); }
  .app-header { padding: 0 8px; gap: 6px; }
  .header-brand { flex: 1; }
  .header-actions { gap: 0; }
  .user-name, .account-control > .lc-icon:last-child { display: none; }
  .account-control { width: 42px; height: 42px; padding: 0; justify-content: center; }
  .navigation-link { min-height: 44px; }
  .group-toggle { height: 32px; }
}
</style>
