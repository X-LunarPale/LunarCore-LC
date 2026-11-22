<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatVersion, locale, t } from '../i18n'
import { requireSuccess } from '../utils/panel'

const auth = useAuth()
const data = ref({ items: [], pending: [], state: 'idle', error: '' })
const loading = ref(false)
const loaded = ref(false)
const loadError = ref('')
const busy = ref(false)
const query = ref('')
const category = ref('all')
function moduleText(source, english) {
  if (locale.value === 'en_US') return english || source || ''
  return t(source)
}

const name = module => moduleText(module.name, module.nameEn) || module.id
const description = module => moduleText(module.description, module.descriptionEn)

function errorText(message) {
  const [source, english] = String(message || '').split(' / ')
  if (locale.value === 'en_US') return english || t(message)
  if (locale.value === 'zh_HK') {
    const translation = t(source)
    return translation === source ? message : translation
  }
  return source || message
}

const categories = { survival: '生存', players: '玩家', security: '保护', server: '服务器' }
const locked = computed(() => busy.value || loading.value || data.value.state === 'downloading' || data.value.pending.length > 0)
const shown = computed(() => data.value.items.filter(m => (category.value === 'all' || category.value === m.category)
  && [m.id, name(m), description(m)].join(' ').toLowerCase().includes(query.value.trim().toLowerCase())))
const pendingNames = computed(() => data.value.pending.map(change => {
  const module = data.value.items.find(m => m.id === change.id)
  return `${module ? name(module) : change.id} · ${t(change.asset ? '安装或更新' : '卸载')}`
}).join('，'))

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const response = requireSuccess(await api.get('/api/market'))
    if (!Array.isArray(response?.items) || !Array.isArray(response?.pending))
      throw new Error('模块市场返回格式错误')
    data.value = response
    loaded.value = true
    loadError.value = ''
  }
  catch (e) { loadError.value = e.message }
  finally { loading.value = false }
}

async function action(module, operation) {
  if (locked.value || !auth.can('core.modules.manage')) return
  busy.value = true
  try {
    if (operation === 'remove') {
      const accepted = await ElMessageBox.confirm(
        t('卸载「{name}」？模块配置和数据会保留，重启后生效。', { name: name(module) }),
        t('卸载模块'), { type: 'warning' }).then(() => true).catch(() => false)
      if (!accepted) return
    }
    requireSuccess(await api.post(`/api/market/${encodeURIComponent(module.id)}/${operation}`))
    ElMessage.success(t(operation === 'remove' ? '已安排卸载' : '正在下载模块'))
    await load()
  } catch (e) { ElMessage.error(errorText(e.message)) }
  finally { busy.value = false }
}

async function cancel() {
  if (busy.value || loading.value || !auth.can('core.modules.manage')) return
  busy.value = true
  try {
    requireSuccess(await api.del('/api/market/pending'))
    await load()
    ElMessage.success(t('已取消待应用变更'))
  } catch (e) { ElMessage.error(errorText(e.message)) }
  finally { busy.value = false }
}

useAutoRefresh(() => { if (!busy.value) return load() }, { immediate: true, seconds: () => data.value.state === 'downloading' ? 2 : 0 })
</script>

<template>
  <section class="market">
    <div class="toolbar">
      <el-input v-model="query" :placeholder="t('搜索模块')" :aria-label="t('搜索模块')" clearable>
        <template #prefix><Icon name="search" :size="14" /></template>
      </el-input>
      <el-select v-model="category" :aria-label="t('模块分类')">
        <el-option :label="t('全部分类')" value="all" />
        <el-option v-for="(label, key) in categories" :key="key" :label="t(label)" :value="key" />
      </el-select>
      <el-button :loading="loading" :disabled="busy" :title="t('刷新')" :aria-label="t('刷新')" @click="load"><Icon name="refresh-cw" :size="15" /></el-button>
      <small v-if="data.version" class="market-version">{{ formatVersion(data.version) }}</small>
    </div>
    <el-alert v-if="loadError" :title="t('模块市场加载失败')" :description="errorText(loadError)" type="error" :closable="false" show-icon />
    <el-alert v-if="data.state === 'failed'" :title="errorText(data.error)" type="error" :closable="false" show-icon />
    <div v-if="data.state === 'downloading'" class="market-status" role="status">
      <Icon name="download" :size="16" /> {{ t('正在下载模块') }} · {{ data.current }}
    </div>
    <div v-if="data.pending.length" class="market-status pending" role="status">
      <div><strong>{{ t('等待重启') }}</strong><p>{{ pendingNames }}</p></div>
      <el-button v-if="auth.can('core.modules.manage')" :disabled="busy" @click="cancel">{{ t('取消变更') }}</el-button>
    </div>
    <div v-loading="loading && !data.items.length" class="market-list" :aria-busy="loading || busy">
      <article v-for="module in shown" :key="module.id" class="market-row">
        <div class="market-module">
          <details>
            <summary><h2>{{ name(module) }} <small v-if="module.version">v{{ module.version }}</small></h2></summary>
            <div class="market-meta">
              <code>{{ module.id }}</code>
              <span v-if="module.depends?.length">{{ t('依赖') }}: {{ module.depends.map(id => name(data.items.find(m => m.id === id) || { name: id, nameEn: id })).join(', ') }}</span>
            </div>
          </details>
          <p v-if="description(module)">{{ description(module) }}</p>
        </div>
        <span class="market-installed" :class="{ installed: module.installedVersion }">{{ module.installedVersion ? `${t('已安装')} v${module.installedVersion}` : t('未安装') }}</span>
        <div v-if="auth.can('core.modules.manage')" class="market-actions">
          <el-button v-if="!module.installedVersion || module.updateAvailable" type="primary" size="small"
            :disabled="locked" :title="t('安装、更新和卸载在重启后生效。')" @click="action(module, 'install')">
            <Icon name="download" :size="14" /> {{ t(module.installedVersion ? '更新' : '安装') }}
          </el-button>
          <el-button v-if="module.installedVersion" size="small" type="danger" plain :disabled="locked"
            @click="action(module, 'remove')">{{ t('卸载') }}</el-button>
        </div>
      </article>
      <el-empty v-if="loaded && !loading && !shown.length" :description="t('没有找到模块')" :image-size="48" />
    </div>
  </section>
</template>

<style scoped>
.market { min-width: 0; container-type: inline-size; }
.toolbar .el-input { width: min(240px, 100%); min-width: 0; }
.toolbar .el-select { width: 146px; max-width: 100%; }
.market-version { margin-inline-start: auto; color: var(--lc-text-soft); }
.market > .el-alert { margin-bottom: 12px; }
.market-status { display: flex; align-items: center; gap: 10px; margin: 12px 0 18px; padding: 12px 14px; border-left: 3px solid var(--el-color-primary); background: var(--el-fill-color-light); font-size: 13px; }
.pending { justify-content: space-between; border-color: var(--el-color-warning); }
.pending > div { min-width: 0; }
.market-status p { margin: 5px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.market-list { border-top: 1px solid var(--lc-line); min-height: 90px; }
.market-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(130px, .28fr) auto; align-items: start; gap: 12px 24px; padding: 14px 0; border-bottom: 1px solid var(--lc-line); }
.market-module { min-width: 0; overflow-wrap: anywhere; }
.market-module details { color: var(--lc-text-soft); font-size: 12px; }
.market-module summary { width: fit-content; cursor: pointer; }
.market-module h2 { display: inline; margin: 0; font-size: 14px; font-weight: 600; color: var(--lc-text); }
.market-module small { margin-inline-start: 4px; color: var(--lc-text-soft); font-size: 12px; font-weight: 400; }
.market-module p { margin: 4px 0 0; font-size: 13px; color: var(--lc-text-soft); }
.market-meta { display: flex; flex-wrap: wrap; gap: 4px 14px; padding-top: 6px; }
.market-installed { font-size: 12px; color: var(--lc-text-soft); overflow-wrap: anywhere; }
.market-installed.installed { color: var(--lc-text); }
.market-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; min-width: 140px; }
.market-actions .el-button { min-height: 32px; margin: 0; gap: 5px; }
@container (max-width: 560px) {
  .market-row { grid-template-columns: minmax(0, 1fr) minmax(64px, .38fr); }
  .market-actions { grid-column: 1 / -1; justify-content: flex-start; min-width: 0; }
}
@container (max-width: 360px) {
  .toolbar .el-input { width: 100%; }
  .toolbar .el-select { flex: 1; min-width: 0; }
  .market-version { width: 100%; }
  .pending { align-items: flex-start; flex-wrap: wrap; }
}
</style>
