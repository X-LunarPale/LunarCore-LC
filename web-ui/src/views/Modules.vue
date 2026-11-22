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
    <header class="page-header"><h1 class="page-title">{{ t('模块管理') }}</h1></header>
    <el-tabs v-model="tab" class="catalog-tabs">
      <el-tab-pane :label="t('已安装')" name="installed">
        <div class="toolbar">
          <el-input v-model="q" :placeholder="t('搜索模块')" :aria-label="t('搜索模块')" clearable><template #prefix><Icon name="search" :size="14" /></template></el-input>
          <el-select v-model="stateFilter" :aria-label="t('模块状态')"><el-option :label="t('全部状态')" value="all" /><el-option :label="t('已启用')" value="enabled" /><el-option :label="t('未启用')" value="disabled" /></el-select>
          <el-button :loading="loading" :disabled="!!busy" :title="t('刷新')" :aria-label="t('刷新')" @click="load"><Icon name="refresh-cw" :size="15" /></el-button>
        </div>
        <div v-if="error" class="load-error">
          <el-alert :title="loaded ? t('模块列表更新失败，正在显示上次读取的内容。') : t('模块列表加载失败')" :description="error" type="error" show-icon :closable="false" />
          <el-button :disabled="loading || !!busy" @click="load">{{ t('重试') }}</el-button>
        </div>
        <div v-if="loaded" class="list-summary">
          <span>{{ t('已安装 {count} 个模块', { count: items.length }) }}</span>
          <span v-if="shown.length !== items.length">{{ t('当前显示 {count} 项', { count: shown.length }) }}</span>
        </div>
        <div v-loading="loading && !loaded" class="module-list" :aria-busy="loading || !!busy">
          <article v-for="m in shown" :key="m.id" class="module-row">
            <div class="module-main">
              <Icon class="module-icon" name="package" :size="17" />
              <div class="module-copy">
                <div class="module-heading">
                  <h2>{{ m.name || m.id }}</h2>
                  <span v-if="m.version" class="module-version">v{{ m.version }}</span>
                </div>
                <div v-if="m.name && m.name !== m.id" class="module-id"><code>{{ m.id }}</code></div>
                <p v-if="m.description" class="module-description">{{ m.description }}</p>
                <details v-if="m.authors?.length || m.depends?.length || m.softDepends?.length" class="module-details">
                  <summary>{{ t('模块信息') }}</summary>
                  <div class="module-meta">
                    <span v-if="m.authors?.length">{{ t('作者') }}: {{ m.authors.join(', ') }}</span>
                    <span v-if="m.depends?.length">{{ t('依赖') }}: {{ m.depends.join(', ') }}</span>
                    <span v-if="m.softDepends?.length">{{ t('可选') }}: {{ m.softDepends.join(', ') }}</span>
                  </div>
                </details>
              </div>
            </div>
            <span class="module-state" :class="{ enabled: m.enabled }">{{ t(m.enabled ? '已启用' : m.loaded ? '已加载' : '未启用') }}</span>
            <div v-if="auth.can('core.modules.manage')" class="ops">
              <el-button v-if="!m.enabled" size="small" :loading="busy === m.id + 'enable'" :disabled="!!busy || loading" @click="op(m, 'enable')">{{ t('启用') }}</el-button>
              <template v-else>
                <el-button size="small" :loading="busy === m.id + 'reload'" :disabled="!!busy || loading" @click="op(m, 'reload')"><Icon name="rotate-cw" :size="13" />{{ t('重载') }}</el-button>
                <el-button size="small" type="danger" plain :loading="busy === m.id + 'disable'" :disabled="!!busy || loading" @click="op(m, 'disable')">{{ t('禁用') }}</el-button>
              </template>
            </div>
          </article>
          <el-empty v-if="loaded && !loading && !shown.length" :description="t('没有找到模块')" :image-size="48" />
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('模块市场')" name="market" :disabled="!!busy">
        <ModuleMarket v-if="tab === 'market'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.modules-page { min-width: 0; container-type: inline-size; }
.catalog-tabs :deep(> .el-tabs__content) { overflow: visible; }
.toolbar .el-input { width: min(240px, 100%); min-width: 0; }
.toolbar .el-select { width: 146px; max-width: 100%; }
.toolbar .el-button { margin: 0; }
.load-error { display: flex; align-items: flex-start; gap: 10px; margin: 0 0 14px; }
.load-error :deep(.el-alert) { flex: 1; min-width: 0; }
.load-error :deep(.el-alert__description) { overflow-wrap: anywhere; }
.list-summary { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 14px; padding-bottom: 9px; color: var(--lc-text-soft); font-size: 12px; }
.module-list { min-height: 90px; border-top: 1px solid var(--lc-line); }
.module-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(100px, .22fr) auto; align-items: start; gap: 12px 24px; padding: 14px 0; border-bottom: 1px solid var(--lc-line); }
.module-main { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.module-icon { box-sizing: content-box; flex: none; padding: 7px; border-radius: 8px; background: var(--el-fill-color-light); color: var(--lc-text-soft); }
.module-copy { min-width: 0; overflow-wrap: anywhere; }
.module-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px; }
.module-heading h2 { margin: 0; font-size: 14px; font-weight: 600; }
.module-id, .module-version { color: var(--lc-text-soft); font-size: 12px; }
.module-description { margin: 4px 0 0; font-size: 13px; color: var(--lc-text-soft); }
.module-details { margin-top: 4px; font-size: 12px; color: var(--lc-text-soft); }
.module-details summary { cursor: pointer; width: fit-content; }
.module-meta { display: flex; flex-wrap: wrap; gap: 4px 14px; padding-top: 6px; }
.module-state { display: inline-flex; align-items: baseline; gap: 6px; font-size: 12px; color: var(--lc-text-soft); }
.module-state::before { content: ""; flex: 0 0 6px; height: 6px; border-radius: 50%; background: currentColor; }
.module-state.enabled::before { color: var(--el-color-success); }
.ops { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; min-width: 140px; }
.ops .el-button { min-height: 32px; margin: 0; gap: 5px; }
@container (max-width: 560px) {
  .module-row { grid-template-columns: minmax(0, 1fr) auto; align-items: start; }
  .ops { grid-column: 1 / -1; justify-content: flex-start; min-width: 0; }
}
@container (max-width: 360px) {
  .toolbar .el-input { width: 100%; }
  .toolbar .el-select { flex: 1; min-width: 0; }
  .load-error { flex-direction: column; }
}
</style>
