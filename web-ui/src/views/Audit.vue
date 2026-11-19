<script setup>
import { reactive, ref } from 'vue'
import { useAutoRefresh } from '../utils/autoRefresh'
import { api } from '../api'
import { requireSuccess } from '../utils/panel'
import { formatDateTime } from '../utils/format'
import { t } from '../i18n'

const s = reactive({ items: [], total: 0, page: 1, size: 20, q: '', module: '', modules: [], loading: false })
const query = ref('')
const error = ref('')
let revision = 0

async function load() {
  const current = ++revision
  s.loading = true
  try {
    const r = requireSuccess(await api.get('/api/audit', { page: s.page, size: s.size, q: s.q, module: s.module }))
    if (current !== revision) return
    if (!Array.isArray(r?.items)) throw new Error(t('操作日志返回格式错误'))
    s.items = r.items
    s.total = r.total
    s.modules = (r.modules || []).filter(Boolean).sort()
    error.value = ''
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) s.loading = false
  }
}

function search() {
  s.q = query.value.trim()
  s.page = 1
  load()
}

const selected = ref(null)
const moduleNames = { motd: '维护与简介', web: '管理后台', performance: '性能优化', playertitle: '称号管理', permissions: '权限管理', chat: '聊天社交', economy: '经济系统', signin: '每日签到', land: '领地系统', anticheat: '反作弊', antixray: '反矿透', core: '核心服务', ban: '封禁管理', teleport: '传送系统', vote: '玩法投票', scoreboard: '计分板', world: '世界管理', essentials: '基础命令', qqauth: '账号认证' }
const actionNames = { 'console.exec': '执行控制台指令', 'embedded.switches': '更新优化开关', permissions: '调整权限', create: '创建记录', delete: '删除记录', update: '更新记录', enable: '启用模块', disable: '禁用模块', reload: '重载配置', 'config.save': '保存配置' }
const moduleLabel = value => t(moduleNames[value] || value || '系统')
const actionLabel = value => t(actionNames[value] || value || '管理操作')
const actorName = value => String(value || t('未知')).replace(/^web:/, '')
const actorSource = value => String(value).startsWith('web:') ? t('管理后台') : value === 'console' ? t('服务器控制台') : t('游戏内')
useAutoRefresh(() => { if (!s.loading && s.page === 1 && !selected.value) return load() }, { immediate: true })
</script>

<template>
  <div class="page audit-page">
    <header class="page-header"><h1 class="page-title">{{ t('操作日志') }}</h1></header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="audit-surface">
      <form class="toolbar" role="search" @submit.prevent="search">
        <el-select v-model="s.module" :placeholder="t('全部模块')" :aria-label="t('模块')" clearable @change="search">
          <el-option v-for="m in s.modules" :key="m" :label="moduleLabel(m)" :value="m" />
        </el-select>
        <el-input v-model="query" :placeholder="t('搜索操作者 / 动作 / 详情')" :aria-label="t('搜索操作者 / 动作 / 详情')" clearable @clear="search">
          <template #prefix><Icon name="search" :size="14" /></template>
        </el-input>
        <el-button native-type="submit" :loading="s.loading">{{ t('搜索') }}</el-button>
        <el-tooltip :content="t('刷新')"><el-button class="refresh-button" :aria-label="t('刷新')" :disabled="s.loading" @click="load"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
      </form>
      <el-table :data="s.items" v-loading="s.loading && !s.items.length" class="audit-table" :empty-text="t('暂无操作记录')">
        <el-table-column :label="t('操作')" min-width="200">
          <template #default="{ row }"><div class="operation"><Icon :name="row.action?.includes('delete') ? 'trash-2' : row.action?.includes('console') ? 'terminal' : 'file-pen-line'" :size="16" /><div><button class="record-link" type="button" @click="selected = row">{{ actionLabel(row.action) }}</button><small>{{ moduleLabel(row.module) }}</small></div></div></template>
        </el-table-column>
        <el-table-column :label="t('时间')" width="180"><template #default="{ row }"><time class="record-time">{{ formatDateTime(row.time) }}</time></template></el-table-column>
        <el-table-column :label="t('操作者')" width="170"><template #default="{ row }"><div class="actor"><Icon name="user-round" :size="15" /><div><span>{{ actorName(row.actor) }}</span><small>{{ actorSource(row.actor) }}</small></div></div></template></el-table-column>
        <el-table-column :label="t('记录摘要')" min-width="260"><template #default="{ row }"><span class="detail-summary">{{ row.detail || t('无补充信息') }}</span></template></el-table-column>
      </el-table>
      <el-dialog :model-value="!!selected" :title="t('操作记录详情')" width="640px" append-to-body @update:model-value="value => { if (!value) selected = null }">
        <template v-if="selected"><dl class="record-meta"><div><dt>{{ t('时间') }}</dt><dd>{{ formatDateTime(selected.time) }}</dd></div><div><dt>{{ t('操作者') }}</dt><dd>{{ actorName(selected.actor) }} · {{ actorSource(selected.actor) }}</dd></div><div><dt>{{ t('模块') }}</dt><dd>{{ moduleLabel(selected.module) }} <small>{{ selected.module }}</small></dd></div><div><dt>{{ t('操作') }}</dt><dd>{{ actionLabel(selected.action) }} <small>{{ selected.action }}</small></dd></div></dl><div class="detail-label">{{ t('完整记录') }}</div><pre class="record-detail">{{ selected.detail || t('无补充信息') }}</pre></template>
      </el-dialog>
      <el-pagination class="pager" :pager-count="5" background layout="total, sizes, prev, pager, next, jumper" :total="s.total" v-model:current-page="s.page" v-model:page-size="s.size" :page-sizes="[20, 50, 100, 200]" @current-change="load" @size-change="search" />
    </div>
  </div>
</template>

<style scoped>
.audit-page > .el-alert { margin-bottom: 16px; }
.audit-surface { min-width: 0; overflow: hidden; }
.toolbar .el-select { width: 145px; }
.toolbar .el-input { width: 270px; min-width: 0; }
.toolbar .el-button { margin-left: 0; }
.refresh-button { width: 34px; padding: 0; }
.audit-table { --el-table-header-bg-color: transparent; --el-table-border-color: var(--el-border-color-lighter); }
.audit-table :deep(td.el-table__cell) { padding: 11px 0; }
.record-time { color: var(--lc-text-soft); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.operation { display: flex; gap: 10px; align-items: center; }
.operation > .lc-icon { color: var(--lc-text-soft); }
.operation > div { min-width: 0; }
.record-link { border: 0; padding: 0; background: none; text-align: start; color: var(--lc-accent); font-weight: 550; cursor: pointer; overflow-wrap: anywhere; }
.record-link:hover { text-decoration: underline; }
.operation small, .actor small { display: block; font-size: 11px; color: var(--lc-text-soft); margin-top: 3px; }
.actor { display: flex; align-items: center; gap: 9px; color: var(--el-text-color-regular); }
.actor > .lc-icon { color: var(--lc-text-soft); }
.detail-summary { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--lc-text-soft); font-size: 12px; }
.record-meta { margin: 0 0 24px; }
.record-meta > div { display: flex; gap: 20px; padding: 9px 0; }
.record-meta dt { color: var(--lc-text-soft); width: 60px; flex-shrink: 0; }
.record-meta dd { margin: 0; overflow-wrap: anywhere; }
.record-meta small { color: var(--lc-text-soft); margin-left: 8px; }
.detail-label { font-size: 12px; color: var(--lc-text-soft); margin-bottom: 8px; }
.record-detail { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 45vh; overflow: auto; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-light); line-height: 1.8; font-size: 12px; }
.pager { padding-top: 18px; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
@media (max-width: 700px) {
  .toolbar { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 8px; margin-bottom: 12px; }
  .toolbar .el-select { width: 100%; grid-column: 1 / -1; }
  .toolbar .el-input { width: 100%; }
  .pager { justify-content: center; }
  .pager :deep(.el-pagination__sizes), .pager :deep(.el-pagination__jump) { display: none; }
  .record-meta > div { gap: 10px; }
  .record-meta dt { width: 48px; }
  .record-detail { padding: 10px; }
  .operation { gap: 8px; }
}
</style>
