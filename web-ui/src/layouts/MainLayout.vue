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
const logo = computed(() => url(app.info?.logoUrl || '/favicon.svg'))
const brandName = computed(() => app.info?.brand || 'LunarCore 琉月')
const route = useRoute()
const router = useRouter()
const wsOnline = ref(false)
const narrow = ref(window.innerWidth < 900)
const mobileOpen = ref(false)
const menuQuery = ref('')
const menu = ref()
const menuScroll = ref()
const menuToggle = ref()
const sidebar = ref()
const searchInput = ref()
const groupStorageKey = 'lc-nav-open-groups'
function readOpenGroups() {
  try {
    const value = JSON.parse(localStorage.getItem(groupStorageKey) || '[]')
    return Array.isArray(value) ? value.filter(id => typeof id === 'string') : []
  } catch { return [] }
}
const openGroups = ref(readOpenGroups())
const navigation = computed(() => buildNavigation(coreMenus, app.panels, permission => auth.can(permission), t))
const groups = computed(() => menuQuery.value.trim()
  ? buildNavigation(coreMenus, app.panels, permission => auth.can(permission), t, menuQuery.value)
  : navigation.value)
const workspaceGroup = computed(() => {
  const item = route.name === 'panel'
    ? app.findPanel(route.params.ns, route.params.id)
    : coreMenus.find(item => item.path === route.path)
  return t(item ? navigationGroup(item).title : '工作空间')
})

const active = computed(() => {
  if (route.name === 'panel') {
    if (route.params.ns === 'anticheat' && app.panels.some(p => p.namespace === 'ban')) return '/panel/ban/ban'
    return `/panel/${route.params.ns}/${route.params.id}`
  }
  return route.path === '' ? '/' : route.path
})

const title = computed(() => {
  if (route.name === 'panel') {
    const p = app.findPanel(route.params.ns, route.params.id)
    return t(p ? p.title : '扩展面板')
  }
  return route.meta.title ? t(route.meta.title) : ''
})

const collapsed = computed(() => narrow.value ? !mobileOpen.value : app.collapsed)
const collapseLabel = computed(() => t(narrow.value ? '关闭导航' : collapsed.value ? '展开导航' : '收起导航'))
const activeGroup = computed(() => navigation.value.find(group => group.id !== 'overview' && group.items.some(item => item.key === active.value))?.id)

function saveOpenGroups(value) {
  openGroups.value = value
  try { localStorage.setItem(groupStorageKey, JSON.stringify(value)) } catch { /* storage unavailable */ }
}
watch(activeGroup, group => {
  if (group && !openGroups.value.includes(group)) saveOpenGroups([...openGroups.value, group])
}, { immediate: true })

async function onMenuClick(event) {
  const title = event.target.closest('.el-sub-menu__title')
  if (!title || collapsed.value || menuQuery.value.trim()) return
  const section = title.closest('[data-nav-group]')
  const group = section?.dataset.navGroup
  if (!group) return
  // Persist the final state after Element Plus handles the title click.
  await nextTick()
  const next = openGroups.value.filter(id => id !== group)
  if (section.classList.contains('is-opened')) next.push(group)
  saveOpenGroups(next)
}
// Search temporarily opens matching sections without replacing saved choices.
watch([collapsed, () => groups.value.map(group => group.id).join('|'), menuQuery, openGroups], async (_, __, onCleanup) => {
  let cancelled = false
  onCleanup(() => { cancelled = true })
  await nextTick()
  if (cancelled || collapsed.value || !menu.value) return
  for (const group of groups.value) {
    if (group.id === 'overview') continue
    if (menuQuery.value.trim() || openGroups.value.includes(group.id)) menu.value.open(group.id)
    else menu.value.close(group.id)
  }
}, { immediate: true, flush: 'post' })

let navigationScrollTimer
watch([active, activeGroup, collapsed], () => {
  window.clearTimeout(navigationScrollTimer)
  if (collapsed.value) return
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  navigationScrollTimer = window.setTimeout(() => {
    const item = sidebar.value?.$el.querySelector('.menu [aria-current="page"]')
    const wrap = menuScroll.value?.wrapRef
    if (collapsed.value || !item?.getClientRects().length || !wrap) return
    const bounds = wrap.getBoundingClientRect()
    const rect = item.getBoundingClientRect()
    const delta = rect.top < bounds.top ? rect.top - bounds.top - 8
      : rect.bottom > bounds.bottom ? rect.bottom - bounds.bottom + 8 : 0
    if (delta) wrap.scrollTo({ top: wrap.scrollTop + delta, behavior: reduced ? 'auto' : 'smooth' })
  }, reduced ? 0 : 450)
}, { immediate: true, flush: 'post' })

async function onGroupKeydown(event, group) {
  if (event.target !== event.currentTarget) return
  if (!['Enter', ' ', 'ArrowRight', 'ArrowLeft'].includes(event.key)) return
  event.preventDefault()
  if (!collapsed.value) {
    const opened = event.currentTarget.classList.contains('is-opened')
    if (event.key === 'ArrowRight' && opened || event.key === 'ArrowLeft' && !opened) return
    event.currentTarget.querySelector('.el-sub-menu__title')?.click()
  } else if (event.key !== 'ArrowLeft') {
    menu.value.open(group)
    await nextTick()
    document.querySelector(`.lc-navigation-popper [data-nav-item-group="${group}"]`)?.focus()
  } else menu.value.close(group)
}
function onItemKeydown(event, group) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.click()
  } else if (collapsed.value && group && (event.key === 'Escape' || event.key === 'ArrowLeft')) {
    event.preventDefault()
    event.stopPropagation()
    menu.value.close(group)
    sidebar.value.$el.querySelector(`[data-nav-group="${group}"]`)?.focus()
  }
}
function onMenuKeydown(event) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  const root = event.target.closest('.lc-navigation-popper') || menu.value?.$el
  const targets = [...root.querySelectorAll('[role="menuitem"][tabindex="0"]')]
    .filter(element => element.getClientRects().length)
  const index = targets.indexOf(document.activeElement)
  if (index < 0 || !targets.length) return
  event.preventDefault()
  const target = event.key === 'Home' ? 0 : event.key === 'End' ? targets.length - 1
    : (index + (event.key === 'ArrowDown' ? 1 : -1) + targets.length) % targets.length
  targets[target].focus()
}

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
  mobileOpen.value = false
  menuQuery.value = ''
  await router.push(key)
  await nextTick()
  menu.value?.updateActiveIndex(active.value)
  if (wasMobile) document.getElementById('main-content')?.focus({ preventScroll: true })
}

async function logout() {
  const ok = await ElMessageBox.confirm(t('确定要退出登录吗？'), t('退出'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return
  await auth.logout()
  router.replace({ name: 'login' })
}

function onCommand(cmd) {
  if (cmd === 'profile') router.push({ name: 'profile' })
  else if (cmd === 'logout') logout()
  else if (cmd === 'refresh') app.loadPanels()
}

function onResize() {
  const next = window.innerWidth < 900
  if (next !== narrow.value) mobileOpen.value = false
  narrow.value = next
}
function onKeydown(event) {
  if (event.target.closest('#primary-navigation, .lc-navigation-popper')) onMenuKeydown(event)
  if (!narrow.value || !mobileOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMobile()
  } else if (event.key === 'Tab') {
    const targets = [...sidebar.value.$el.querySelectorAll('button, input, [tabindex="0"], a[href]')]
      .filter(element => !element.disabled && element.getClientRects().length)
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
function refreshPanels() {
  if (!document.hidden) app.loadPanels().catch(() => {})
}
onMounted(() => {
  app.loadInfo()
  refreshPanels()
  panelRefresh = window.setInterval(refreshPanels, 30000)
  document.addEventListener('visibilitychange', refreshPanels)
  window.addEventListener('resize', onResize)
  document.addEventListener('keydown', onKeydown)
  offStatus = socket.onStatus((v) => (wsOnline.value = v))
  socket.connect()
})
onUnmounted(() => {
  window.clearInterval(panelRefresh)
  window.clearTimeout(navigationScrollTimer)
  document.removeEventListener('visibilitychange', refreshPanels)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('keydown', onKeydown)
  offStatus && offStatus()
})
</script>

<template>
  <el-container class="layout">
    <a class="skip-link" href="#main-content">{{ t('跳到主要内容') }}</a>
    <Transition name="backdrop"><button v-if="narrow && mobileOpen" class="mobile-backdrop" :aria-label="t('关闭导航')" tabindex="-1" @click="closeMobile" /></Transition>
    <el-aside ref="sidebar" :width="collapsed && !narrow ? '64px' : '248px'" class="aside" :class="{ 'mobile-open': narrow && mobileOpen, 'is-collapsed': collapsed }" :inert="narrow && !mobileOpen || undefined" :aria-hidden="narrow && !mobileOpen || undefined">
      <button class="brand" :aria-label="t('返回仪表盘')" :title="brandName" @click="onSelect('/')">
        <img :src="logo" class="logo" :alt="brandName" />
        <Transition name="brand-label"><div v-if="!collapsed" class="brand-text">
          <div class="brand-cn">{{ brandName }}</div>
          <div class="brand-en">{{ t('管理控制台') }}</div>
        </div></Transition>
      </button>
      <div v-if="!collapsed || narrow" class="nav-search">
        <Icon name="search" :size="15" />
        <input ref="searchInput" v-model="menuQuery" :aria-label="t('搜索管理功能')" :placeholder="t('搜索管理功能')" />
        <button v-if="menuQuery" :aria-label="t('清除搜索')" @click="menuQuery = ''"><Icon name="x" :size="14" /></button>
      </div>
      <el-scrollbar ref="menuScroll" class="menu-scroll">
        <el-menu id="primary-navigation" ref="menu" :aria-label="t('主导航')" aria-orientation="vertical" :default-active="active" :default-openeds="openGroups" :collapse="collapsed" :collapse-transition="false" :show-timeout="120" :hide-timeout="180" popper-effect="light" popper-class="lc-navigation-popper" class="menu" @select="onSelect" @click="onMenuClick">
          <template v-for="g in groups" :key="g.id">
            <template v-if="g.id === 'overview'">
              <el-menu-item v-for="item in g.items" :key="item.key" :index="item.key" tabindex="0" :aria-current="item.key === active ? 'page' : undefined" @keydown="onItemKeydown">
                <Icon :name="item.icon" :size="18" />
                <template #title><span class="menu-title">{{ item.title }}</span></template>
              </el-menu-item>
            </template>
            <el-sub-menu v-else :index="g.id" :data-nav-group="g.id" tabindex="0" :class="{ 'first-section': g.id === groups.find(group => group.id !== 'overview')?.id }" @keydown="event => onGroupKeydown(event, g.id)">
              <template #title><Icon :name="g.icon" :size="18" /><span class="menu-title">{{ g.title }}</span></template>
              <el-menu-item v-for="item in g.items" :key="item.key" :index="item.key" tabindex="0" :data-nav-item-group="g.id" :title="item.title" :aria-current="item.key === active ? 'page' : undefined" @keydown="event => onItemKeydown(event, g.id)">
                <Icon :name="item.icon" :size="16" />
                <template #title><span class="menu-title">{{ item.title }}</span></template>
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
        <div v-if="!groups.length" class="nav-empty">{{ t('未找到匹配的功能') }}</div>
      </el-scrollbar>
      <div class="nav-footer">
        <span v-if="!collapsed" class="nav-connection"><span class="ws-dot" :class="{ on: wsOnline }" />{{ wsOnline ? t('实时连接') : t('连接重试中') }}</span>
        <el-tooltip :content="collapseLabel" placement="right"><button class="nav-toggle" :aria-label="collapseLabel" @click="toggleMenu"><Icon :name="narrow ? 'x' : collapsed ? 'panel-left-open' : 'panel-left-close'" :size="17" /></button></el-tooltip>
      </div>
    </el-aside>
    <el-container class="main-col" :inert="narrow && mobileOpen || undefined">
      <el-header class="header">
        <el-button ref="menuToggle" text circle :aria-label="t('展开或折叠菜单')" :aria-expanded="!collapsed" aria-controls="primary-navigation" @click="toggleMenu">
          <Icon :name="collapsed ? 'panel-left-open' : 'panel-left-close'" />
        </el-button>
        <div class="breadcrumb"><span class="workspace-label">{{ workspaceGroup }}</span><Icon name="chevron-right" :size="13" /><Transition name="breadcrumb-title" mode="out-in"><span :key="route.fullPath" class="header-title">{{ title }}</span></Transition></div>
        <div class="spacer" />
        <el-tooltip :content="wsOnline ? t('实时连接正常') : t('实时连接已断开（自动重连中）')">
          <span class="connection-status" :class="{ online: wsOnline }"><span class="ws-dot" :class="{ on: wsOnline }" /><span class="connection-label">{{ wsOnline ? t('实时连接') : t('连接重试中') }}</span></span>
        </el-tooltip>
        <el-dropdown trigger="click" @command="setLocale">
          <button class="language-button" :aria-label="t('语言')"><Icon name="languages" :size="17" /><span>{{ locale === 'en_US' ? 'English' : '中文' }}</span></button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="zh_CN" :disabled="locale === 'zh_CN'">中文</el-dropdown-item>
            <el-dropdown-item command="en_US" :disabled="locale === 'en_US'">English</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
        <el-tooltip :content="app.dark ? t('切换到亮色') : t('切换到暗色')">
          <el-button text circle :aria-label="app.dark ? t('切换到亮色') : t('切换到暗色')" @click="app.toggleTheme()">
            <Icon :name="app.dark ? 'sun' : 'moon'" />
          </el-button>
        </el-tooltip>
        <el-dropdown trigger="click" @command="onCommand">
          <button class="user-chip" :aria-label="t('账户菜单')">
            <img v-if="auth.user?.mcName" :src="`https://mc-heads.net/avatar/${auth.user.mcName}/24`" class="user-avatar" />
            <Icon v-else name="circle-user-round" :size="20" />
            <span class="user-name">{{ auth.user?.username }}</span>
            <el-tag size="small" effect="plain" round>{{ auth.user?.roleName }}</el-tag>
            <Icon name="chevron-down" :size="13" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile"><Icon name="user-cog" :size="16" />&nbsp;{{ t('个人设置') }}</el-dropdown-item>
              <el-dropdown-item command="refresh"><Icon name="refresh-cw" :size="16" />&nbsp;{{ t('刷新菜单') }}</el-dropdown-item>
              <el-dropdown-item command="logout" divided><Icon name="log-out" :size="16" />&nbsp;{{ t('退出登录') }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main id="main-content" class="main" tabindex="-1">
        <router-view v-slot="{ Component, route: r }">
          <Transition name="route" mode="out-in"><component :is="Component" :key="r.fullPath" /></Transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout { height: 100dvh; min-height: 0; }
.aside { --nav-ink: var(--el-text-color-regular); --nav-muted: var(--lc-text-soft); --nav-line: var(--el-border-color-light); --nav-hover: var(--el-fill-color-light); display: flex; flex-direction: column; flex-shrink: 0; overflow: hidden; background: var(--lc-surface); color: var(--nav-ink); border-right: 1px solid var(--nav-line); transition: width 300ms var(--motion-spring-soft), transform var(--motion-standard) var(--motion-spring); }
.brand { height: 76px; flex-shrink: 0; display: flex; align-items: center; gap: 10px; padding: 0 18px; width: 100%; min-width: 0; border: 0; background: none; color: var(--lc-text); cursor: pointer; text-align: left; }
.logo { width: 32px; height: 32px; object-fit: contain; flex-shrink: 0; }
.brand-text { min-width: 0; flex: 1; }
.brand-cn { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--lc-text); font-size: 14px; font-weight: 650; letter-spacing: 0; line-height: 1.4; }
.brand-en { color: var(--nav-muted); font-size: 11px; letter-spacing: 0; margin-top: 3px; }
.brand-label-enter-active { transition: opacity 200ms ease-out, transform var(--motion-standard) var(--motion-spring); }
.brand-label-leave-active { transition: opacity 100ms ease-in; }
.brand-label-enter-from { opacity: 0; transform: translateX(-8px); }
.brand-label-leave-to { opacity: 0; }
.nav-search { flex-shrink: 0; height: 36px; display: flex; align-items: center; gap: 8px; margin: 0 14px 14px; padding: 0 10px; border: 1px solid var(--nav-line); border-radius: 6px; background: var(--lc-bg); color: var(--nav-muted); transition: border-color var(--motion-fast); }
.nav-search input { width: 100%; min-width: 0; outline: none; border: 0; background: transparent; color: var(--nav-ink); font-size: 12px; }
.nav-search input::placeholder { color: var(--nav-muted); }
.nav-search:focus-within { border-color: var(--el-color-primary); }
.nav-search button { display: grid; place-items: center; width: 24px; height: 24px; flex-shrink: 0; border: 0; border-radius: 4px; background: none; color: inherit; cursor: pointer; padding: 0; }
.nav-search button:hover { background: var(--el-fill-color-light); color: var(--lc-text); }
.menu-scroll { flex: 1; min-height: 0; }
.menu { padding: 0 10px 14px; border: 0; background: transparent; --el-menu-item-height: 38px; --el-menu-sub-item-height: 36px; --el-menu-text-color: var(--nav-ink); --el-menu-bg-color: transparent; --el-menu-hover-bg-color: var(--nav-hover); --el-menu-active-color: var(--el-color-primary); }
.menu :deep(.el-menu-item), .menu :deep(.el-sub-menu__title) { position: relative; height: 38px; line-height: 38px; margin: 2px 0; padding: 0 12px !important; border-radius: 6px; gap: 10px; font-size: 13px; letter-spacing: 0; color: var(--nav-ink); transition: background-color var(--motion-fast), color var(--motion-fast); }
.menu :deep(.el-sub-menu__title) { padding-right: 30px !important; font-weight: 550; }
.menu :deep(.el-sub-menu:focus-visible) { outline: none; }
.menu :deep(.el-sub-menu:focus-visible > .el-sub-menu__title) { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.menu :deep(.el-sub-menu__icon-arrow) { right: 12px; font-size: 12px; color: var(--nav-muted); transition: transform 300ms var(--motion-spring-soft); }
.menu :deep(.el-menu-item > .lc-icon), .menu :deep(.el-sub-menu__title > .lc-icon) { color: var(--nav-muted); width: 18px; }
.menu :deep(.el-sub-menu .el-menu) { padding: 2px 0 8px 16px; background: transparent; }
.menu :deep(.el-sub-menu .el-menu-item) { min-width: 0; height: 36px; line-height: 36px; font-size: 12px; font-weight: 400; }
.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) { color: var(--lc-text); }
.menu :deep(.first-section) { margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--nav-line); }
.menu :deep(.el-menu-item.is-active) { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; animation: nav-select 280ms var(--motion-spring-soft) both; }
.menu :deep(.el-menu-item.is-active)::before { content: ''; position: absolute; width: 2px; left: 0; top: 10px; bottom: 10px; border-radius: 2px; background: currentColor; }
.menu :deep(.el-menu-item.is-active .lc-icon) { color: var(--el-color-primary); }
.menu-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-empty { padding: 18px; color: var(--nav-muted); font-size: 12px; }
.nav-footer { min-height: 52px; padding: 10px 18px; display: flex; align-items: center; justify-content: space-between; gap: 8px; border-top: 1px solid var(--nav-line); flex-shrink: 0; }
.nav-connection { display: inline-flex; align-items: center; gap: 7px; font-size: 11px; color: var(--nav-muted); white-space: nowrap; }
.nav-toggle { width: 30px; height: 30px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 5px; background: transparent; color: var(--nav-muted); cursor: pointer; transition: background-color var(--motion-fast), color var(--motion-fast), transform 280ms var(--motion-spring); }
.nav-toggle:hover { background: var(--nav-hover); color: var(--lc-text); }
.nav-toggle:active { transform: scale(.92); }
.is-collapsed .brand { justify-content: center; padding: 0; }
.is-collapsed .menu { width: 63px; padding: 0 8px 14px; }
.is-collapsed .menu :deep(.el-menu-item), .is-collapsed .menu :deep(.el-sub-menu__title) { justify-content: center; width: 46px; height: 40px; padding: 0 !important; }
.is-collapsed .menu :deep(.el-menu-item .el-menu-tooltip__trigger) { justify-content: center; width: 46px; padding: 0 !important; }
.is-collapsed .menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.is-collapsed .menu :deep(.el-sub-menu.is-active > .el-sub-menu__title > .lc-icon) { color: var(--el-color-primary); }
.is-collapsed .nav-footer { justify-content: center; padding: 10px 0; }
@keyframes nav-select { from { transform: translateX(-2px); } to { transform: translateX(0); } }
.main-col { min-width: 0; min-height: 0; }
.header { height: 68px; flex-shrink: 0; display: flex; align-items: center; gap: 12px; padding: 0 28px; background: var(--lc-surface); border-bottom: 1px solid var(--lc-sidebar-border); }
.breadcrumb { display: flex; gap: 12px; align-items: center; min-width: 0; font-size: 12px; color: var(--lc-text-soft); }
.header-title { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.breadcrumb-title-enter-active { transition: transform 340ms var(--motion-spring), opacity 180ms ease-out; }
.breadcrumb-title-leave-active { transition: opacity 100ms ease-in; }
.breadcrumb-title-enter-from { opacity: 0; transform: translateY(6px); }
.breadcrumb-title-leave-to { opacity: 0; }
.spacer { flex: 1; }
.connection-status { display: inline-flex; gap: 7px; align-items: center; color: var(--lc-text-soft); font-size: 11px; margin-right: 10px; white-space: nowrap; }
.connection-status.online { color: var(--el-color-success); }
.ws-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--el-color-warning); display: inline-block; }
.ws-dot.on { background: var(--el-color-success); }
.user-chip { display: inline-flex; align-items: center; gap: 9px; cursor: pointer; padding: 5px 8px; border-radius: 7px; color: var(--el-text-color-primary); border: 0; background: transparent; font-size: 12px; transition: background-color var(--motion-fast), transform 320ms var(--motion-spring); }
.user-chip:hover { background: var(--el-fill-color-light); }
.user-chip:active { transform: scale(.96); transition-duration: 90ms; }
.language-button { display: inline-flex; align-items: center; gap: 5px; padding: 6px 8px; border: 0; border-radius: 6px; background: transparent; color: var(--el-text-color-regular); cursor: pointer; font-size: 12px; white-space: nowrap; transition: background-color var(--motion-fast), transform 320ms var(--motion-spring); }
.language-button:hover { background: var(--el-fill-color-light); }
.language-button:active { transform: scale(.96); transition-duration: 90ms; }
.language-button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.user-avatar { width: 27px; height: 27px; border-radius: 7px; }
.user-name { font-weight: 550; }
.user-chip :deep(.el-tag) { border: 0; background: var(--el-fill-color-light); color: var(--lc-text-soft); font-size: 10px; }
.main { padding: 0; overflow: auto; background: var(--lc-bg); }
.mobile-backdrop { position: fixed; inset: 0; z-index: 29; border: 0; background: #08172888; backdrop-filter: blur(2px); }
.backdrop-enter-active, .backdrop-leave-active { transition: opacity 220ms ease; }
.backdrop-enter-from, .backdrop-leave-to { opacity: 0; }
@media (max-width: 899px) {
  .aside { position: fixed; inset: 0 auto 0 0; z-index: 30; transform: translateX(-100%); }
  .aside.mobile-open { transform: translateX(0); }
  .header { padding: 0 12px; height: 60px; gap: 5px; }
  .workspace-label, .breadcrumb > .lc-icon, .connection-label { display: none; }
  .connection-status { margin-right: 0; }
  .user-chip { gap: 5px; padding: 4px; }
  .user-chip :deep(.el-tag) { display: none; }
  .language-button span { display: none; }
  .brand { padding-left: 18px; }
}
@media (prefers-reduced-motion: reduce) { .aside, .user-chip, .language-button, .nav-toggle, .brand-label-enter-active, .brand-label-leave-active { transition: none; } .menu :deep(.el-menu-item.is-active) { animation: none; } }
</style>

<style>
.lc-navigation-popper { padding: 6px !important; border-radius: 7px !important; background: var(--lc-surface) !important; }
.lc-navigation-popper .el-menu { min-width: 200px; background: transparent; border: 0; }
.lc-navigation-popper .el-menu-item { height: 38px; line-height: 38px; padding: 0 12px; gap: 10px; border-radius: 5px; color: var(--el-text-color-regular); font-size: 12px; }
.lc-navigation-popper .el-menu-item .lc-icon { width: 16px; color: var(--lc-text-soft); }
.lc-navigation-popper .el-menu-item.is-active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.lc-navigation-popper .el-menu-item.is-active .lc-icon { color: var(--el-color-primary); }
.lc-navigation-popper .el-menu-item:hover { background: var(--el-fill-color-light); }
.lc-navigation-popper .menu-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
