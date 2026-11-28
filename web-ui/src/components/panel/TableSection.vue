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

const props = defineProps({ section: Object, namespace: String, active: { type: Boolean, default: true }, preview: Boolean })
const auth = useAuth()
const runner = ref(null)

const s = reactive({
  items: [],
  total: 0,
  page: 1,
  size: props.section.pageSize || 20,
  q: '',
  query: '',
  sort: '',
  order: '',
  loaded: false,
  loading: false,
  error: null
})

const columns = computed(() => (props.section.columns || []).map(column => ({
  ...column,
  valueLabels: props.section.valueLabels?.[column.key] ?? column.valueLabels
})))
const rowActions = computed(() => props.preview ? [] : (props.section.rowActions || []).filter((a) => auth.can(a.permission)))
const toolbarActions = computed(() => props.preview ? [] : (props.section.toolbarActions || []).filter((a) => auth.can(a.permission)))
const searchable = computed(() => !props.preview && props.section.searchable !== false &&
  Boolean(s.total || s.items.length || s.query || s.q || s.error))
const rowKey = computed(() => props.section.rowKey || 'id')
const labelWidth = label => Array.from(t(label)).reduce((width, char) => width + (char.codePointAt(0) > 255 ? 12 : 7), 0)
const actionWidth = computed(() => Math.min(320, 24 + rowActions.value.reduce((w, a) => w + 24 + labelWidth(a.label), 0) + Math.max(0, rowActions.value.length - 1) * 6))

async function load() {
  const current = begin()
  s.loading = true
  try {
    const r = await api.get(resolveEndpoint(props.section.endpoint, props.namespace), {
      page: s.page, size: s.size, q: s.query, sort: s.sort, order: s.order
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
    s.loaded = true
  } catch (e) {
    if (!current()) return
    s.error = { status: e.status, message: e.message }
  } finally {
    if (current()) s.loading = false
  }
}

function actionDone() {
  window.dispatchEvent(new CustomEvent('lc:panel-changed', { detail: { namespace: props.namespace } }))
}

function search() {
  s.query = s.q.trim()
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
  seconds: () => Number.isFinite(Number(props.section.refreshSeconds)) && Number(props.section.refreshSeconds) >= 0 ? Number(props.section.refreshSeconds) : 5,
  namespace: props.namespace,
  active: () => props.active && !runner.value?.busy,
  immediate: true
})
onBeforeUnmount(() => {
  begin() // Ignore stale in-flight responses after leaving the panel.
})
defineExpose({ reload: load })
</script>

<template>
  <section class="table-section" :aria-busy="s.loading">
    <header class="table-heading">
      <h2>{{ t(section.title || '数据列表') }}</h2>
      <div v-if="!preview || s.error" class="toolbar">
        <el-input v-if="searchable" v-model="s.q" :disabled="runner?.busy" :placeholder="t('搜索')" :aria-label="t('{title}搜索', { title: t(section.title || '列表') })" clearable class="table-search" @keyup.enter="search" @clear="search">
          <template #prefix><Icon name="search" :size="14" /></template>
        </el-input>
        <el-button v-for="a in toolbarActions" :key="a.id" :disabled="!!s.error || s.loading || runner?.busy" :type="btnType(a.style)" :plain="a.style === 'danger' || a.style === 'warning'" @click="runner.run(a, {})"><Icon v-if="a.icon" :name="a.icon" :size="14" />{{ t(a.label) }}</el-button>
        <el-button class="refresh-button" :disabled="s.loading || runner?.busy" @click="load" :title="t('刷新列表')" :aria-label="t('刷新列表')"><Icon name="refresh-cw" :size="14" /></el-button>
      </div>
    </header>
    <SectionError :error="s.error" />
    <p v-if="s.error && s.items.length" class="stale-note" role="status">{{ t('当前显示上次成功读取的数据') }}</p>
    <el-table v-show="s.items.length" :data="s.items" v-loading="s.loading" :row-key="rowKey" @sort-change="onSort">
      <el-table-column v-for="c in columns" :key="c.key" :prop="c.key" :label="t(c.label)" :sortable="c.sortable ? 'custom' : false"
        :min-width="c.type === 'player' ? 170 : c.type === 'datetime' ? 165 : 110" show-overflow-tooltip>
        <template #default="{ row }"><CellValue :column="c" :row="row" /></template>
      </el-table-column>
      <el-table-column v-if="rowActions.length" :label="t('操作')" :width="actionWidth" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button v-for="a in rowActions.filter(action => rowActionVisible(action, row))" :key="a.id" size="small" :disabled="!!s.error || s.loading || runner?.busy" :type="btnType(a.style)" :plain="a.style !== 'default' && !!a.style" @click="runner.run(a, row)">{{ t(a.label) }}</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!s.items.length && !s.error" class="table-empty" v-loading="s.loading">
      <span v-if="s.loaded">{{ s.query ? t('没有匹配的记录') : t('暂无记录') }}</span>
    </div>
    <el-pagination v-if="!preview && !s.error && s.total > 0" class="pager" :disabled="s.loading || runner?.busy" :pager-count="5" layout="total, sizes, prev, pager, next" :total="s.total" v-model:current-page="s.page"
      v-model:page-size="s.size" :page-sizes="[10, 20, 50, 100]" @current-change="load" @size-change="search" />
    <ActionRunner v-if="!preview" ref="runner" :namespace="namespace" :columns="columns" @done="actionDone" />
  </section>
</template>

<style scoped>
.table-section { padding:0; min-width:0; color:var(--lc-text); }
.table-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 12px; margin-bottom: 8px; }
h2 { font-size: 15px; font-weight: 600; margin: 0; }
.toolbar { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:8px; max-width:100%; }
.toolbar > :deep(.el-button) { margin:0; height:34px; padding:0 11px; font-size:13px; }
.table-search { width: min(240px, 100%); }
.table-search :deep(.el-input__wrapper) { min-height:34px; }
.table-search :deep(.el-input__inner) { height:32px; font-size:13px; }
.table-section :deep(.el-table) { --el-table-border-color:var(--el-border-color-light); --el-table-header-bg-color:var(--el-fill-color-light); --el-table-header-text-color:var(--el-text-color-regular); --el-table-row-hover-bg-color:var(--el-fill-color-light); font-size:13px; border-radius:0; border-top:1px solid var(--el-border-color-light); }
.table-section :deep(.el-table th.el-table__cell) { font-weight: 550; padding: 7px 0; }
.table-section :deep(.el-table td.el-table__cell) { padding: 7px 0; }
.table-empty { display: flex; align-items: center; min-height: 42px; padding: 9px 0; border-block: 1px solid var(--lc-line); color: var(--lc-text-soft); font-size: 13px; }
.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.row-actions :deep(.el-button) { margin: 0; flex: 0 0 auto; height: 25px; padding: 0 8px; }
.pager { margin-top: 11px; justify-content: flex-end; flex-wrap: wrap; gap: 6px; }
.stale-note { margin:0 0 10px; color:var(--el-color-warning); font-size:12px; }
.table-section :deep(.el-table__cell .cell) { overflow-wrap: anywhere; line-height: 1.55; }
@media (max-width: 700px) {
  .toolbar { gap: 6px; }
  .pager :deep(.el-pagination__sizes), .pager :deep(.el-pagination__jump) { display: none; }
  .table-search { flex: 1; min-width: min(130px, 100%); }
  .refresh-button { margin-left: auto !important; }
  .pager { justify-content: flex-start; overflow-x: auto; }
}
@media (prefers-reduced-motion: reduce) { .table-section :deep(*) { transition: none !important; animation: none !important; } }
</style>
