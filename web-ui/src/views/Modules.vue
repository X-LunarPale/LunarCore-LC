<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { requireSuccess } from '../utils/panel'
import { t } from '../i18n'
import ModuleMarket from './ModuleMarket.vue'

const auth = useAuth()
const app = useApp()
const items = ref([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const busy = ref('')
const q = ref('')
const stateFilter = ref('all')
const tab = ref('installed')

async function load() {
  if (loading.value) return false
  loading.value = true
  try {
    const result = requireSuccess(await api.get('/api/modules'))
    if (!Array.isArray(result?.items) || result.items.some(item =>
      !item || typeof item !== 'object' || typeof item.id !== 'string' || !item.id.trim()
    )) throw new Error(t('模块列表返回格式错误'))
    items.value = result.items
    loaded.value = true
    error.value = ''
    return true
  } catch (e) {
    error.value = e.message
    return false
  } finally {
    loading.value = false
  }
}

const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return items.value.filter(m => (stateFilter.value === 'all' || m.enabled === (stateFilter.value === 'enabled'))
    && (!s || [m.id, m.name, m.description].some(x => String(x || '').toLowerCase().includes(s))))
})

async function op(m, action) {
  if (busy.value || loading.value || !auth.can('core.modules.manage')) return
  busy.value = m.id + action
  try {
    if (action === 'disable') {
      const ok = await ElMessageBox.confirm(t('确定禁用模块「{name}」吗？依赖它的模块也会被禁用。', { name: m.name }), t('禁用模块'), { type: 'warning' }).then(() => true).catch(() => false)
      if (!ok) return
    }
    const r = requireSuccess(await api.post(`/api/modules/${encodeURIComponent(m.id)}/${action}`))
    ElMessage.success(r?.message || t({ enable: '已启用', disable: '已禁用', reload: '已重载' }[action]))
    await load()
    try {
      await app.loadPanels()
    } catch (e) {
      ElMessage.warning(t('模块操作已完成，但菜单更新失败：{error}', { error: e.message }))
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = ''
  }
}

useAutoRefresh(() => { if (!loading.value && !busy.value) return load() }, {
  immediate: true,
  active: () => tab.value === 'installed'
})
</script>

<template>
  <div class="page modules-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('模块管理') }}</h1>
      </div>
      <div v-if="tab === 'installed'" class="toolbar">
        <el-input v-model="q" :placeholder="t('搜索模块')" :aria-label="t('搜索模块')" clearable><template #prefix><Icon name="search" :size="14" /></template></el-input>
        <el-select v-model="stateFilter" :aria-label="t('模块状态')"><el-option :label="t('全部状态')" value="all" /><el-option :label="t('已启用')" value="enabled" /><el-option :label="t('未启用')" value="disabled" /></el-select>
        <el-button :loading="loading" :disabled="!!busy" @click="load"><Icon name="refresh-cw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
      </div>
    </div>
    <el-tabs v-model="tab" class="workspace-tabs"><el-tab-pane :label="t('已安装')" name="installed" :disabled="!!busy" /><el-tab-pane :label="t('模块市场')" name="market" :disabled="!!busy" /></el-tabs>
    <ModuleMarket v-if="tab === 'market'" />
    <div v-if="tab === 'installed' && error" class="load-error">
      <el-alert :title="loaded ? t('模块列表更新失败，正在显示上次读取的内容。') : t('模块列表加载失败')" :description="error" type="error" show-icon :closable="false" />
      <el-button :disabled="loading || !!busy" @click="load">{{ t('重试') }}</el-button>
    </div>
    <div v-if="tab === 'installed' && loaded" class="list-summary"><span>{{ t('已安装 {count} 个模块', { count: items.length }) }}</span><span>{{ t('当前显示 {count} 项', { count: shown.length }) }}</span></div>
    <div v-if="tab === 'installed'" v-loading="loading" class="grid" :aria-busy="loading || !!busy">
      <article v-for="m in shown" :key="m.id" class="mod" :class="{ off: !m.enabled }">
        <div class="mod-head">
          <div class="mod-icon"><Icon name="package" :size="18" /></div>
          <div class="mod-name">
            <div class="mod-label"><h2>{{ m.name }}</h2><span class="muted ver">v{{ m.version }}</span></div>
            <div class="muted mono id">{{ m.id }}</div>
          </div>
          <el-tag :type="m.enabled ? 'success' : 'info'" effect="light">{{ t(m.enabled ? '已启用' : m.loaded ? '已加载' : '未启用') }}</el-tag>
        </div>
        <div class="details"><div class="desc">{{ m.description || t('暂无描述') }}</div>
        <details v-if="m.authors?.length || m.depends?.length || m.softDepends?.length" class="module-details"><summary>{{ t('模块信息') }}</summary><div class="meta">
          <span v-if="m.authors?.length"><Icon name="user" :size="12" /> {{ m.authors.join(', ') }}</span>
          <span v-if="m.depends?.length"><Icon name="link" :size="12" /> {{ t('依赖') }} {{ m.depends.join(', ') }}</span>
          <span v-if="m.softDepends?.length" class="muted">{{ t('可选') }} {{ m.softDepends.join(', ') }}</span>
        </div></details>
        </div>
        <div v-if="auth.can('core.modules.manage')" class="ops">
          <el-button v-if="!m.enabled" size="small" type="primary" :loading="busy === m.id + 'enable'" :disabled="!!busy || loading" @click="op(m, 'enable')">{{ t('启用') }}</el-button>
          <template v-else>
            <el-button size="small" :loading="busy === m.id + 'reload'" :disabled="!!busy || loading" @click="op(m, 'reload')"><Icon name="rotate-cw" :size="13" />&nbsp;{{ t('重载') }}</el-button>
            <el-button size="small" type="danger" plain :loading="busy === m.id + 'disable'" :disabled="!!busy || loading" @click="op(m, 'disable')">{{ t('禁用') }}</el-button>
          </template>
        </div>
      </article>
      <el-empty v-if="loaded && !loading && !shown.length" :description="t('没有找到模块')" />
    </div>
  </div>
</template>

<style scoped>
.modules-page { min-width: 0; color: var(--lc-text, #19283b); letter-spacing: 0; }
.page-title { font-size: 22px; font-weight: 600; line-height: 1.3; letter-spacing: 0; }
.page-header { gap: 16px; margin-bottom: 20px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0; }
.toolbar :deep(.el-input) { width: 200px; min-width: 0; max-width: 100%; }
.toolbar :deep(.el-select) { width: 130px; min-width: 0; }
.toolbar :deep(.el-button) { margin: 0; }
.list-summary { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px; padding: 0 2px 12px; font-size: 12px; color: var(--lc-text-soft, #718096); }
.list-summary b { margin-left: 8px; font-weight: 500; color: var(--lc-text, #19283b); }
.load-error { display: flex; align-items: flex-start; gap: 10px; margin: 0 0 14px; }
.load-error :deep(.el-alert) { flex: 1; min-width: 0; }
.load-error :deep(.el-alert__description) { overflow-wrap: anywhere; }
.grid { display: flex; flex-direction: column; min-height: 120px; overflow: hidden; border-top: 1px solid var(--el-border-color); }
.mod { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.2fr) auto; align-items: center; gap: 18px; padding: 16px 0; transition: background-color 180ms ease; }
.mod + .mod { border-top: 1px solid var(--lc-sidebar-border, #e3e8ef); }
.mod-head { display: flex; align-items: center; gap: 10px; min-width: 0; }
.mod-icon { display: flex; flex: 0 0 30px; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 6px; background: var(--el-fill-color-light, #f4f6f9); color: var(--el-color-primary); }
.mod-icon :deep(svg) { width: 17px; height: 17px; }
.mod.off .mod-icon { color: var(--lc-text-soft, #718096); }
.mod-name { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.mod-label { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 7px; }
.mod-label h2 { margin: 0; font-size: 13px; font-weight: 600; line-height: 1.6; }
.ver { font-size: 12px; font-weight: 400; white-space: nowrap; }
.id { margin-top: 3px; font-size: 11px; font-weight: 400; }
.mod-head :deep(.el-tag) { flex-shrink: 0; border-radius: 4px; font-size: 11px; }
.details, .meta, .meta span { min-width: 0; overflow-wrap: anywhere; }
.desc { color: var(--el-text-color-regular, #4d5f74); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.meta { display: flex; flex-wrap: wrap; gap: 5px 14px; margin-top: 5px; font-size: 11px; line-height: 1.7; color: var(--lc-text-soft, #718096); }
.meta :deep(svg) { margin-right: 3px; vertical-align: -2px; }
.module-details { margin-top: 7px; font-size: 11px; color: var(--lc-text-soft); }.module-details summary { cursor: pointer; width: fit-content; }.module-details summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.ops { display: flex; justify-content: flex-end; gap: 8px; min-width: 142px; }
.ops :deep(.el-button) { min-height: 32px; margin: 0; transition: background-color 180ms, border-color 180ms, transform 320ms var(--motion-spring); }
.ops :deep(.el-button:focus-visible), .load-error :deep(.el-button:focus-visible) { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
@media (hover: hover) and (pointer: fine) { .mod:hover { background: var(--el-fill-color-light, #f8fafc); } }
@media (max-width: 1100px) {
  .mod { grid-template-columns: minmax(0, 1fr) auto; gap: 10px 16px; }
  .details { grid-column: 1 / -1; grid-row: 2; }
  .ops { grid-column: 2; grid-row: 1; }
}
@media (max-width: 680px) {
  .page-header { flex-direction: column; align-items: flex-start; gap: 14px; }
  .toolbar { width: 100%; flex-wrap: wrap; }
  .toolbar :deep(.el-input) { flex: 1; width: auto; }
}
@media (max-width: 600px) {
  .mod { grid-template-columns: minmax(0, 1fr); gap: 10px; padding: 14px 0; }
  .mod-head { grid-column: 1; grid-row: 1; }
  .details { grid-column: 1; grid-row: 2; }
  .ops { grid-column: 1; grid-row: 3; justify-content: flex-start; min-width: 0; }
  .mod-icon { display: none; }
  .load-error { flex-direction: column; }
}
@media (prefers-reduced-motion: reduce) { .mod, .ops :deep(.el-button) { transition: none; } }
</style>
