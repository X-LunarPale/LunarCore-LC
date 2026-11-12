<script setup>
import SectionError from './SectionError.vue'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { api, resolveEndpoint } from '../../api'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { useAuth } from '../../stores/auth'
import CellValue from './CellValue.vue'
import ActionRunner from './ActionRunner.vue'
import { latestRequest, requireSuccess, rowActionVisible } from '../../utils/panel'
import { t } from '../../i18n'
const begin = latestRequest()

const props = defineProps({ section: Object, namespace: String, active: { type: Boolean, default: true } })
const auth = useAuth()
const runner = ref(null)

const s = reactive({
  items: [],
  total: 0,
  page: 1,
  size: props.section.pageSize || 20,
  q: '',
  sort: '',
  order: '',
  loading: false,
  error: null
})

const columns = computed(() => (props.section.columns || []).map(column => ({
  ...column,
  valueLabels: props.section.valueLabels?.[column.key] ?? column.valueLabels
})))
const rowActions = computed(() => (props.section.rowActions || []).filter((a) => auth.can(a.permission)))
const toolbarActions = computed(() => (props.section.toolbarActions || []).filter((a) => auth.can(a.permission)))
const searchable = computed(() => props.section.searchable !== false)
const rowKey = computed(() => props.section.rowKey || 'id')
const labelWidth = label => Array.from(t(label)).reduce((width, char) => width + (char.codePointAt(0) > 255 ? 12 : 7), 0)
const actionWidth = computed(() => Math.min(320, 24 + rowActions.value.reduce((w, a) => w + 24 + labelWidth(a.label), 0) + Math.max(0, rowActions.value.length - 1) * 6))

async function load() {
  const current = begin()
  s.loading = true
  try {
    const r = await api.get(resolveEndpoint(props.section.endpoint, props.namespace), {
      page: s.page, size: s.size, q: s.q, sort: s.sort, order: s.order
    })
    if (!current()) return
    requireSuccess(r)
    if (!Array.isArray(r) && !Array.isArray(r?.items)) throw new Error(t('表格接口返回格式错误'))
    if (Array.isArray(r)) {
      s.items = r
      s.total = r.length
    } else {
      s.items = r?.items || []
      s.total = r?.total ?? s.items.length
    }
    s.error = null
  } catch (e) {
    if (!current()) return
    s.error = { status: e.status, message: e.message }
    s.items = []
    s.total = 0
  } finally {
    if (current()) s.loading = false
  }
}

function actionDone() {
  load()
  window.dispatchEvent(new CustomEvent('lc:panel-changed', { detail: { namespace: props.namespace } }))
}

function search() {
  s.page = 1
  load()
}

function onSort({ prop, order }) {
  s.sort = order ? prop : ''
  s.order = order === 'ascending' ? 'asc' : order === 'descending' ? 'desc' : ''
  load()
}

const btnType = (style) => ({ primary: 'primary', danger: 'danger', warning: 'warning' })[style] || 'default'

useAutoRefresh(() => { if (!s.loading && !runner.value?.busy) return load() }, {
  seconds: () => Number(props.section.refreshSeconds) > 0 ? props.section.refreshSeconds : 5,
  namespace: props.namespace,
  active: () => props.active,
  immediate: true
})
onBeforeUnmount(() => {
  begin() // Ignore stale in-flight responses after leaving the panel.
})
defineExpose({ reload: load })
</script>

<template>
  <div class="card table-section">
    <header class="table-heading"><div><Icon class="heading-mark" name="table-2" :size="15" /><h3>{{ t(section.title || '数据列表') }}</h3><span class="record-count">{{ t('{count} 项', { count: s.total }) }}</span></div><span v-if="s.loading" class="loading-label" role="status">{{ t('正在更新…') }}</span></header>
    <div class="toolbar">
      <el-input v-if="searchable" v-model="s.q" :placeholder="t('输入关键词后按回车搜索')" :aria-label="t('{title}搜索', { title: t(section.title || '列表') })" clearable class="table-search" @keyup.enter="search" @clear="search">
        <template #prefix><Icon name="search" :size="14" /></template>
      </el-input>
      <el-button v-for="a in (s.error ? [] : toolbarActions)" :key="a.id" :type="btnType(a.style)" :plain="a.style === 'danger' || a.style === 'warning'" @click="runner.run(a, {})">{{ t(a.label) }}</el-button>
      <div class="spacer" />
      <el-button class="refresh-button" :disabled="s.loading" @click="load" :title="t('刷新列表')" :aria-label="t('刷新列表')"><Icon name="refresh-cw" :size="14" /></el-button>
    </div>
    <SectionError :error="s.error" />
    <el-table v-if="!s.error" :data="s.items" v-loading="s.loading" :row-key="rowKey" @sort-change="onSort">
      <el-table-column v-for="c in columns" :key="c.key" :prop="c.key" :label="t(c.label)" :sortable="c.sortable ? 'custom' : false"
        :min-width="c.type === 'player' ? 170 : c.type === 'datetime' ? 165 : 110" show-overflow-tooltip>
        <template #default="{ row }"><CellValue :column="c" :row="row" /></template>
      </el-table-column>
      <el-table-column v-if="rowActions.length" :label="t('操作')" :width="actionWidth" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button v-for="a in rowActions.filter(action => rowActionVisible(action, row))" :key="a.id" size="small" :type="btnType(a.style)" :plain="a.style !== 'default' && !!a.style" @click="runner.run(a, row)">{{ t(a.label) }}</el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty><div class="table-empty"><Icon name="inbox" :size="28" /><strong>{{ s.q ? t('没有匹配的记录') : t('暂无记录') }}</strong><span>{{ s.q ? t('请调整关键词后重新搜索。') : t('记录生成后将显示在这里。') }}</span></div></template>
    </el-table>
    <el-pagination v-if="!s.error" class="pager" background layout="total, sizes, prev, pager, next, jumper" :total="s.total" v-model:current-page="s.page"
      v-model:page-size="s.size" :page-sizes="[10, 20, 50, 100]" @current-change="load" @size-change="search" />
    <ActionRunner ref="runner" :namespace="namespace" :columns="columns" @done="actionDone" />
  </div>
</template>

<style scoped>
.table-section { padding: 16px; background: var(--lc-surface); border: 1px solid var(--el-border-color-light); border-radius: 9px; color: var(--lc-text); box-shadow: none; }
.table-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 11px; }
.table-heading > div { display: flex; align-items: center; gap: 8px; min-width: 0; flex-wrap: wrap; }
.heading-mark { color: var(--el-color-primary); flex-shrink: 0; }
h3 { font-size: 14px; font-weight: 650; margin: 0; }
.record-count { border-left: 1px solid var(--el-border-color); padding-left: 8px; font-size: 11px; color: var(--lc-text-soft); font-variant-numeric: tabular-nums; }
.loading-label { font-size: 11px; color: var(--lc-text-soft); white-space: nowrap; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; padding-bottom: 11px; }
.toolbar > :deep(.el-button) { margin: 0; height: 30px; padding: 0 11px; font-size: 12px; }
.table-search { width: min(240px, 100%); }
.table-search :deep(.el-input__wrapper) { min-height: 30px; }
.table-search :deep(.el-input__inner) { height: 28px; font-size: 12px; }
.spacer { flex: 1; }
.table-section :deep(.el-table) { --el-table-border-color: var(--el-border-color-lighter); --el-table-header-bg-color: var(--el-fill-color-light); --el-table-header-text-color: var(--el-text-color-regular); --el-table-row-hover-bg-color: var(--el-color-primary-light-9); font-size: 12px; border-radius: 6px; }
.table-section :deep(.el-table th.el-table__cell) { font-weight: 550; padding: 7px 0; }
.table-section :deep(.el-table td.el-table__cell) { padding: 7px 0; }
.table-section :deep(.el-button) { border-radius: 5px; transition: background-color 180ms, border-color 180ms; }
.table-section :deep(.el-button:focus-visible) { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.table-empty { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 22px 12px; color: var(--lc-text-soft); line-height: 1.4; }
.table-empty > :first-child { margin-bottom: 3px; opacity: .55; }
.table-empty strong { font-size: 12px; font-weight: 500; }
.table-empty span { font-size: 11px; }
.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.row-actions :deep(.el-button) { margin: 0; flex: 0 0 auto; height: 25px; padding: 0 8px; }
.pager { margin-top: 11px; justify-content: flex-end; flex-wrap: wrap; gap: 6px; }
.table-section :deep(.el-table__cell .cell) { overflow-wrap: anywhere; line-height: 1.55; }
@media (max-width: 700px) {
  .table-section { padding: 12px; }
  .toolbar { gap: 6px; }
  .pager :deep(.el-pagination__sizes), .pager :deep(.el-pagination__jump) { display: none; }
  .table-search { flex: 1; min-width: 150px; }
  .spacer { display: none; }
  .refresh-button { margin-left: auto !important; }
  .pager { justify-content: flex-start; overflow-x: auto; }
}
@media (prefers-reduced-motion: reduce) { .table-section :deep(*) { transition: none !important; animation: none !important; } }
</style>
