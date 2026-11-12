<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatVersion, locale, t } from '../i18n'

const auth = useAuth()
const data = ref({ items: [], pending: [], state: 'idle', error: '' })
const loading = ref(false)
const busy = ref(false)
const query = ref('')
const category = ref('all')
const name = m => locale.value === 'en_US' ? m.nameEn : m.name
const description = m => locale.value === 'en_US' ? m.descriptionEn : m.description
const errorText = message => String(message || '').split(' / ')[locale.value === 'en_US' ? 1 : 0] || message
const categories = { survival: '生存', players: '玩家', security: '保护', server: '服务器' }
const locked = computed(() => busy.value || data.value.state === 'downloading' || data.value.pending.length > 0)
const shown = computed(() => data.value.items.filter(m => (category.value === 'all' || category.value === m.category)
  && [m.id, name(m), description(m)].join(' ').toLowerCase().includes(query.value.trim().toLowerCase())))
const pendingNames = computed(() => data.value.pending.map(change => {
  const module = data.value.items.find(m => m.id === change.id)
  return `${module ? name(module) : change.id} · ${t(change.asset ? '安装或更新' : '卸载')}`
}).join('，'))

async function load() {
  if (loading.value) return
  loading.value = true
  try { data.value = await api.get('/api/market') }
  catch (e) { ElMessage.error(errorText(e.message)) }
  finally { loading.value = false }
}

async function action(module, operation) {
  if (operation === 'remove') {
    const accepted = await ElMessageBox.confirm(
      t('卸载「{name}」？模块配置和数据会保留，重启后生效。', { name: name(module) }),
      t('卸载模块'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!accepted) return
  }
  busy.value = true
  try {
    const result = await api.post(`/api/market/${encodeURIComponent(module.id)}/${operation}`)
    if (result.ok === false) throw new Error(result.message || t('操作失败'))
    ElMessage.success(t(operation === 'remove' ? '已安排卸载' : '正在下载模块'))
    await load()
  } catch (e) { ElMessage.error(errorText(e.message)) }
  finally { busy.value = false }
}

async function cancel() {
  busy.value = true
  try {
    await api.del('/api/market/pending')
    await load()
    ElMessage.success(t('已取消待应用变更'))
  } catch (e) { ElMessage.error(errorText(e.message)) }
  finally { busy.value = false }
}

useAutoRefresh(load, { immediate: true, seconds: () => data.value.state === 'downloading' ? 2 : 0 })
</script>

<template>
  <section class="market">
    <div class="market-note">
      <Icon name="package" :size="16" />
      <span>{{ t('附加模块按需安装。安装、更新和卸载均在完整重启后生效。') }}</span>
      <span class="market-version">{{ formatVersion(data.version) }}</span>
    </div>
    <el-alert v-if="data.state === 'failed'" :title="errorText(data.error)" type="error" :closable="false" show-icon />
    <div v-if="data.state === 'downloading'" class="market-status" role="status">
      <Icon name="download" :size="16" /> {{ t('正在下载模块') }} · {{ data.current }}
    </div>
    <div v-if="data.pending.length" class="market-status pending" role="status">
      <div><strong>{{ t('等待重启') }}</strong><p>{{ pendingNames }}</p></div>
      <el-button v-if="auth.can('core.modules.manage')" :disabled="busy" @click="cancel">{{ t('取消变更') }}</el-button>
    </div>
    <div class="market-toolbar">
      <el-input v-model="query" :placeholder="t('搜索模块')" clearable>
        <template #prefix><Icon name="search" :size="14" /></template>
      </el-input>
      <el-select v-model="category" :aria-label="t('模块分类')">
        <el-option :label="t('全部分类')" value="all" />
        <el-option v-for="(label, key) in categories" :key="key" :label="t(label)" :value="key" />
      </el-select>
      <el-button :loading="loading" :aria-label="t('刷新')" @click="load"><Icon name="refresh-cw" :size="15" /></el-button>
    </div>
    <div v-loading="loading && !data.items.length" class="market-list">
      <article v-for="module in shown" :key="module.id" class="market-row">
        <div class="market-name">
          <Icon name="package" :size="20" />
          <div><strong>{{ name(module) }}</strong><small>{{ module.id }} · v{{ module.version }}</small></div>
        </div>
        <div class="market-description">
          <p>{{ description(module) }}</p>
          <small v-if="module.depends.length">{{ t('依赖') }}: {{ module.depends.map(id => name(data.items.find(m => m.id === id) || { name: id, nameEn: id })).join(', ') }}</small>
          <small v-if="module.installedVersion">{{ t('已安装') }}: v{{ module.installedVersion }}</small>
        </div>
        <div v-if="auth.can('core.modules.manage')" class="market-actions">
          <el-button v-if="!module.installedVersion || module.updateAvailable" type="primary" size="small"
            :disabled="locked" @click="action(module, 'install')">
            <Icon name="download" :size="14" /> {{ t(module.installedVersion ? '更新' : '安装') }}
          </el-button>
          <el-tag v-else type="success" size="small">{{ t('已安装') }}</el-tag>
          <el-button v-if="module.installedVersion" size="small" type="danger" plain :disabled="locked"
            @click="action(module, 'remove')">{{ t('卸载') }}</el-button>
        </div>
        <el-tag v-else size="small">{{ t(module.installedVersion ? '已安装' : '未安装') }}</el-tag>
      </article>
      <el-empty v-if="!loading && !shown.length" :description="t('没有找到模块')" />
    </div>
  </section>
</template>

<style scoped>
.market{min-width:0}.market-note{display:flex;align-items:center;gap:8px;font-size:12px;line-height:1.7;color:var(--lc-text-soft);margin-bottom:16px}.market-note>svg{flex-shrink:0}.market-version{margin-left:auto;white-space:nowrap}.market-toolbar{display:flex;gap:10px;margin:16px 0}.market-toolbar .el-input{max-width:320px}.market-toolbar .el-select{width:150px;flex-shrink:0}.market-status{display:flex;align-items:center;gap:10px;padding:12px 14px;margin:12px 0;background:var(--el-fill-color-light);border-left:3px solid var(--el-color-primary);font-size:13px}.pending{justify-content:space-between;border-color:var(--el-color-warning)}.market-status p{margin:5px 0 0;font-size:12px;overflow-wrap:anywhere}.market-list{border-top:1px solid var(--el-border-color);min-height:100px}.market-row{display:grid;grid-template-columns:minmax(180px,.8fr) minmax(150px,1.4fr) auto;gap:20px;align-items:center;padding:18px 4px;border-bottom:1px solid var(--el-border-color-lighter)}.market-name{display:flex;align-items:center;gap:10px;min-width:0}.market-name>svg{color:var(--el-color-primary);flex-shrink:0}.market-name strong{font-size:13px;font-weight:600}.market-name small{display:block;color:var(--lc-text-soft);font-size:11px;margin-top:5px;overflow-wrap:anywhere}.market-description{min-width:0;font-size:12px;line-height:1.8}.market-description p{margin:0;overflow-wrap:anywhere}.market-description small{display:block;color:var(--lc-text-soft);font-size:11px}.market-actions{display:flex;align-items:center;gap:8px}.market-actions .el-button+.el-button{margin-left:0}.market-actions .el-button{gap:5px}@media(max-width:800px){.market-row{grid-template-columns:minmax(0,1fr) auto;gap:10px}.market-description{grid-column:1/-1;grid-row:2}.market-actions{grid-column:2;grid-row:1}}@media(max-width:480px){.market-note{flex-wrap:wrap}.market-version{margin-left:24px}.market-toolbar{flex-wrap:wrap;gap:8px}.market-toolbar .el-input{max-width:none;flex-basis:100%}.market-toolbar .el-select{flex:1}.market-row{padding:14px 0}.market-actions{flex-wrap:wrap;justify-content:flex-end;max-width:140px}.pending{align-items:flex-start}}
</style>
