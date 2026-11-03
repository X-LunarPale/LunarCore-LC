<script setup>
import { reactive, ref } from 'vue'
import { useAutoRefresh } from '../utils/autoRefresh'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { formatDateTime } from '../utils/format'
import { t } from '../i18n'

const s = reactive({ items: [], total: 0, page: 1, size: 20, q: '', module: '', modules: [], loading: false })

async function load() {
  s.loading = true
  try {
    const r = await api.get('/api/audit', { page: s.page, size: s.size, q: s.q, module: s.module })
    s.items = r.items
    s.total = r.total
    s.modules = (r.modules || []).filter(Boolean).sort()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    s.loading = false
  }
}

function search() {
  s.page = 1
  load()
}

const selected = ref(null)
const moduleNames = { web: '管理后台', performance: '性能优化', playertitle: '称号管理', permissions: '权限管理', chat: '聊天社交', economy: '经济系统', signin: '每日签到', land: '领地系统', anticheat: '反作弊', antixray: '反矿透', core: '核心服务', ban: '封禁管理', teleport: '传送系统', vote: '玩法投票', scoreboard: '计分板', world: '世界管理', essentials: '基础命令', qqauth: '账号认证' }
const actionNames = { 'console.exec': '执行控制台指令', 'embedded.switches': '更新优化开关', permissions: '调整权限', create: '创建记录', delete: '删除记录', update: '更新记录', enable: '启用模块', disable: '禁用模块', reload: '重载配置', 'config.save': '保存配置' }
const moduleLabel = value => t(moduleNames[value] || value || '系统')
const actionLabel = value => t(actionNames[value] || value || '管理操作')
const actorName = value => String(value || t('未知')).replace(/^web:/, '')
const actorSource = value => String(value).startsWith('web:') ? t('管理后台') : value === 'console' ? t('服务器控制台') : t('游戏内')
useAutoRefresh(() => { if (!s.loading && s.page === 1 && !selected.value) return load() }, { immediate: true })
</script>

<template>
  <div class="page audit-page">
    <div class="page-header">
      <div>
        <p class="eyebrow">{{ t('安全与追踪') }}</p><h1 class="page-title">{{ t('操作日志') }}</h1>
        <div class="page-sub">{{ t('查询游戏内、服务器控制台与管理后台的操作记录。') }}</div>
      </div>
      <span class="live-status"><i />{{ t(selected ? '查看记录详情' : s.page === 1 ? '自动更新' : '历史记录') }}</span>
    </div>
    <div class="card audit-surface">
      <div class="toolbar">
        <el-select v-model="s.module" :placeholder="t('全部模块')" clearable style="width: 160px" @change="search">
          <el-option v-for="m in s.modules" :key="m" :label="moduleLabel(m)" :value="m" />
        </el-select>
        <el-input v-model="s.q" :placeholder="t('搜索操作者 / 动作 / 详情')" clearable style="width: 280px" @keyup.enter="search" @clear="search">
          <template #prefix><Icon name="search" :size="14" /></template>
        </el-input>
        <el-button type="primary" @click="search">{{ t('搜索') }}</el-button>
        <div class="spacer" />
        <el-button @click="load"><Icon name="refresh-cw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
      </div>
      <div class="list-caption"><span>{{ t('管理操作记录') }} <strong>{{ s.total }}</strong></span><span>{{ t('按时间倒序排列') }}</span></div>
      <el-table :data="s.items" v-loading="s.loading && !s.items.length" class="audit-table" @row-click="selected = $event">
        <el-table-column :label="t('时间')" width="180"><template #default="{ row }"><time class="record-time">{{ formatDateTime(row.time) }}</time></template></el-table-column>
        <el-table-column :label="t('操作')" min-width="200"><template #default="{ row }"><div class="operation"><span class="operation-icon"><Icon :name="row.action?.includes('delete') ? 'trash-2' : row.action?.includes('console') ? 'terminal' : 'file-pen-line'" :size="16" /></span><div><strong>{{ actionLabel(row.action) }}</strong><small>{{ moduleLabel(row.module) }}</small></div></div></template></el-table-column>
        <el-table-column :label="t('操作者')" width="170"><template #default="{ row }"><div class="actor"><Icon name="user-round" :size="15" /><div><span>{{ actorName(row.actor) }}</span><small>{{ actorSource(row.actor) }}</small></div></div></template></el-table-column>
        <el-table-column :label="t('记录摘要')" min-width="260"><template #default="{ row }"><span class="detail-summary">{{ row.detail || t('无补充信息') }}</span></template></el-table-column>
        <el-table-column width="85" align="right"><template #default="{ row }"><el-button link type="primary" @click.stop="selected = row">{{ t('详情') }}<Icon name="chevron-right" :size="13" /></el-button></template></el-table-column>
        <template #empty><div class="audit-empty"><Icon name="scroll-text" :size="30" /><strong>{{ t('暂无操作记录') }}</strong><span>{{ t('新的管理操作将在此显示。') }}</span></div></template>
      </el-table>
      <el-dialog :model-value="!!selected" :title="t('操作记录详情')" width="640px" append-to-body @update:model-value="value => { if (!value) selected = null }">
        <template v-if="selected"><dl class="record-meta"><div><dt>{{ t('时间') }}</dt><dd>{{ formatDateTime(selected.time) }}</dd></div><div><dt>{{ t('操作者') }}</dt><dd>{{ actorName(selected.actor) }} · {{ actorSource(selected.actor) }}</dd></div><div><dt>{{ t('模块') }}</dt><dd>{{ moduleLabel(selected.module) }} <small>{{ selected.module }}</small></dd></div><div><dt>{{ t('操作') }}</dt><dd>{{ actionLabel(selected.action) }} <small>{{ selected.action }}</small></dd></div></dl><div class="detail-label">{{ t('完整记录') }}</div><pre class="record-detail">{{ selected.detail || t('无补充信息') }}</pre></template>
      </el-dialog>
      <el-pagination class="pager" :pager-count="5" background layout="total, sizes, prev, pager, next, jumper" :total="s.total" v-model:current-page="s.page" v-model:page-size="s.size" :page-sizes="[20, 50, 100, 200]" @current-change="load" @size-change="search" />
    </div>
  </div>
</template>

<style scoped>
.audit-page{color:var(--lbr-text);min-width:0}.page-header{margin-bottom:20px;gap:12px}.page-title{font-size:25px;letter-spacing:-.5px}.page-sub{margin:6px 0 0}.eyebrow { margin: 0 0 5px; font-size: 11px; color: var(--lbr-text-soft);letter-spacing:.08em; }
.live-status { display: flex; align-items: center; gap: 7px; color: var(--lbr-text-soft); font-size: 12px; }
.live-status i { width: 6px; height: 6px; background: var(--el-color-success); border-radius: 50%; }
.audit-surface { padding: 16px 18px; border-radius:8px; overflow:hidden; }
.toolbar { padding-bottom: 20px; border-bottom: 1px solid var(--el-border-color-light); }
.list-caption { display: flex; justify-content: space-between; color: var(--lbr-text-soft); font-size: 12px; padding: 4px 0 18px; }
.list-caption strong { color: var(--lbr-text); font-weight: 500; margin-left: 8px; }
.audit-table { --el-table-header-bg-color: transparent; --el-table-border-color: var(--el-border-color-lighter); }
.audit-table :deep(.el-table__row) { cursor: pointer; }
.audit-table :deep(td.el-table__cell) { padding: 11px 0; }
.record-time { color: var(--lbr-text-soft); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.operation { display: flex; gap: 12px; align-items: center; }
.operation-icon { width: 32px; height: 32px; display: grid; place-items: center; flex-shrink: 0; background: var(--el-fill-color-light); color: var(--lbr-text-soft); border-radius: 8px; }
.operation strong { display: block; font-size: 13px; font-weight: 550; color: var(--lbr-text); }
.operation small, .actor small { display: block; font-size: 11px; color: var(--lbr-text-soft); margin-top: 3px; }
.actor { display: flex; align-items: center; gap: 9px; color: var(--el-text-color-regular); }
.actor > .lbr-icon { color: var(--lbr-text-soft); }
.detail-summary { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--lbr-text-soft); font-size: 12px; }
.audit-empty { display: flex; align-items: center; flex-direction: column; gap: 10px; padding: 44px 0; color: var(--lbr-text-soft); }
.audit-empty strong { font-weight: 500; }
.audit-empty span { font-size: 12px; }
.record-meta { margin: 0 0 24px; }
.record-meta > div { display: flex; gap: 20px; padding: 9px 0; }
.record-meta dt { color: var(--lbr-text-soft); width: 60px; flex-shrink: 0; }
.record-meta dd { margin: 0; overflow-wrap: anywhere; }
.record-meta small { color: var(--lbr-text-soft); margin-left: 8px; }
.detail-label { font-size: 12px; color: var(--lbr-text-soft); margin-bottom: 8px; }
.record-detail { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 45vh; overflow: auto; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-light); line-height: 1.8; font-size: 12px; }
.pager { padding-top: 18px; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
@media (max-width: 700px) { .page-title{font-size:23px}.audit-surface { padding: 12px; }.toolbar{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;padding-bottom:14px;margin-bottom:12px}.toolbar > .el-select{width:100%!important;grid-column:1/-1}.toolbar > .el-input { width: 100% !important;min-width:0; }.toolbar>.spacer{display:none}.toolbar>.el-button:last-child{grid-column:1/-1;margin:0}.list-caption{padding-bottom:12px}.list-caption > span:last-child { display: none; }.pager{justify-content:center}.pager :deep(.el-pagination__sizes), .pager :deep(.el-pagination__jump),.pager :deep(.el-pagination__total) { display: none; }.record-meta>div{gap:10px}.record-meta dt{width:48px}.record-detail{padding:10px}.operation{gap:8px} }
</style>
