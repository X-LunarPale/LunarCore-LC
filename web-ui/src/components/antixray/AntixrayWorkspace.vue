<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { api, resolveEndpoint } from '../../api'
import { useAuth } from '../../stores/auth'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { latestRequest, requireSuccess } from '../../utils/panel'
import { renderMarkdown } from '../../utils/markdown'
import TableSection from '../panel/TableSection.vue'
import FormSection from '../panel/FormSection.vue'
import WorkspaceTabs from '../panel/WorkspaceTabs.vue'
import { t, localeTag } from '../../i18n'

const props = defineProps({ panel: { type: Object, required: true } })
const namespace = 'antixray'
const auth = useAuth()
const tab = ref('overview')
const recordTab = ref('alerts')
const configTab = ref('detection')
const configVisited = ref(false)
const stats = ref([])
const statsError = ref('')
const statsLoading = ref(false)
const updatedAt = ref(null)
const updatedTime = computed(() => updatedAt.value?.toLocaleTimeString(localeTag.value, { hour: '2-digit', minute: '2-digit', second: '2-digit' }))
const begin = latestRequest()

const sections = computed(() => props.panel.sections || [])
const endpointName = section => String(section.endpoint || '').replace(/[?#].*$/, '').replace(/\/+$/, '').split('/').pop()
const statSection = computed(() => sections.value.find(s => s.type === 'stats'))
const paperSections = computed(() => sections.value.filter(s => s.type === 'table' && endpointName(s) === 'paper'))
const alertSections = computed(() => sections.value.filter(s => s.type === 'table' && endpointName(s) === 'alerts'))
const suspectSections = computed(() => sections.value.filter(s => s.type === 'table' && endpointName(s) === 'suspects'))
const otherTables = computed(() => sections.value.filter(s => s.type === 'table' && !['paper', 'alerts', 'suspects'].includes(endpointName(s))))
const textSections = computed(() => sections.value.filter(s => s.type === 'text'))
const configGroups = [
  { id: 'detection', prefix: 'detection.', label: '行为检测', icon: 'scan-search', description: '设置采样窗口、矿脉权重和可疑行为阈值。检测结果用于辅助人工复核。' },
  { id: 'fakeore', prefix: 'fake-ore.', label: '假矿补充', icon: 'layers', description: '配置假矿伪装及计算预算。此方案存在原始区块数据先到达客户端的窗口，仅作为补充防护。' },
  { id: 'actions', prefix: 'actions.', label: '告警联动', icon: 'workflow', description: '设置告警触发条件与自动执行命令。告警仅表示行为可疑，请谨慎配置自动处置。' }
]
const forms = computed(() => sections.value.filter(s => s.type === 'form' && auth.can(s.savePermission || 'antixray.manage')))
const groupedForms = computed(() => configGroups.map(group => ({
  ...group,
  sections: forms.value.map((s, index) => ({
    ...s,
    title: t(group.label),
    savePermission: s.savePermission || 'antixray.manage',
    fields: (s.fields || []).filter(field => field.key?.startsWith(group.prefix)),
    workspaceKey: `${index}-${group.id}`
  })).filter(s => s.fields.length)
})).filter(group => group.sections.length))
const otherForms = computed(() => forms.value.map((s, index) => ({
  ...s,
  savePermission: s.savePermission || 'antixray.manage',
  fields: (s.fields || []).filter(field => !configGroups.some(group => field.key?.startsWith(group.prefix))),
  workspaceKey: `other-${index}`
})).filter(s => s.fields.length))
const activeConfigGroup = computed(() => groupedForms.value.find(group => group.id === configTab.value)?.id || groupedForms.value[0]?.id)
const paperAvailability = computed(() => stats.value.find(item => item.label === 'Paper anti-xray')?.value)
const tabs = [
  { id: 'overview', label: '概览与告警', icon: 'activity' },
  { id: 'paper', label: 'Paper 防矿透', icon: 'shield' },
  { id: 'config', label: '行为监测配置', icon: 'sliders-horizontal' }
].map(item => ({ ...item, panelId: `antixray-pane-${item.id}` }))

async function loadStats() {
  if (statsLoading.value || !statSection.value?.endpoint) return
  const current = begin()
  statsLoading.value = true
  try {
    const result = requireSuccess(await api.get(resolveEndpoint(statSection.value.endpoint, namespace)))
    if (!Array.isArray(result) && !Array.isArray(result?.items)) throw new Error(t('统计接口返回格式错误'))
    if (!current()) return
    stats.value = Array.isArray(result) ? result : result.items
    statsError.value = ''
    updatedAt.value = new Date()
  } catch (error) {
    if (current()) statsError.value = error.message
  } finally {
    if (current()) statsLoading.value = false
  }
}
function chooseTab(id) {
  tab.value = id
  if (id === 'config') configVisited.value = true
}
useAutoRefresh(loadStats, { namespace, seconds: () => Number(statSection.value?.refreshSeconds) > 0 ? Number(statSection.value.refreshSeconds) : 30, immediate: true })
onBeforeUnmount(() => begin())
</script>

<template>
  <div class="antixray-workspace">
    <header class="page-header"><h1 class="page-title">{{ t('反矿透') }}</h1></header>

    <section v-if="statSection" class="health-strip" :aria-label="t('反矿透运行概览')" :aria-busy="statsLoading">
      <div class="health-label"><Icon name="activity" :size="16" /><span>{{ t('运行概览') }}</span></div>
      <dl v-if="stats.length" class="health-metrics" :class="{ stale: statsError }">
        <div v-for="(item, index) in stats" :key="`${item.label}-${index}`" class="health-metric"><dt>{{ item.label }}</dt><dd>{{ item.value ?? '—' }}</dd></div>
      </dl>
      <span v-else class="empty-health">{{ t(statsLoading ? '正在读取运行状态…' : statsError ? '运行状态暂不可用' : '暂无运行数据') }}</span>
      <button class="icon-button" type="button" :disabled="statsLoading" :aria-label="t('刷新运行概览')" :title="updatedTime ? `${t('刷新运行概览')}: ${updatedTime}` : t('刷新运行概览')" @click="loadStats"><Icon name="refresh-cw" :size="15" /></button>
    </section>
    <div v-if="statsError" class="stats-error" role="alert">{{ statsError }}<span v-if="stats.length"> · {{ t('当前显示上次成功读取的数据') }}</span></div>

    <WorkspaceTabs :model-value="tab" :items="tabs" label="反矿透分区" id="antixray" @update:model-value="chooseTab" />

    <div class="workspace-content">
      <section id="antixray-pane-overview" v-show="tab === 'overview'" class="tab-panel" role="tabpanel" aria-labelledby="antixray-tab-overview">
        <div class="review-notice"><Icon name="info" :size="16" /><p>{{ t('可疑分与告警仅表示挖矿行为异常，不能单独作为作弊证明。请核实记录与实际情况后再作处理。') }}</p></div>
        <div class="record-switch" :aria-label="t('记录视图')"><button type="button" :class="{ selected: recordTab === 'alerts' }" :aria-pressed="recordTab === 'alerts'" @click="recordTab = 'alerts'">{{ t('告警记录') }}</button><button type="button" :class="{ selected: recordTab === 'suspects' }" :aria-pressed="recordTab === 'suspects'" @click="recordTab = 'suspects'">{{ t('可疑玩家排行') }}</button></div>
        <div v-show="recordTab === 'alerts'" class="section-stack"><TableSection v-for="(section, index) in alertSections" :key="`alerts-${index}`" :section="section" :namespace="namespace" :active="tab === 'overview' && recordTab === 'alerts'" /><div v-if="!alertSections.length" class="empty-state">{{ t('当前面板未提供告警记录。') }}</div></div>
        <div v-show="recordTab === 'suspects'" class="section-stack"><TableSection v-for="(section, index) in suspectSections" :key="`suspects-${index}`" :section="section" :namespace="namespace" :active="tab === 'overview' && recordTab === 'suspects'" /><div v-if="!suspectSections.length" class="empty-state">{{ t('当前面板未提供可疑玩家排行。') }}</div></div>
        <div v-if="otherTables.length" class="section-stack additional-sections"><TableSection v-for="(section, index) in otherTables" :key="`other-${index}`" :section="section" :namespace="namespace" :active="tab === 'overview'" /></div>
      </section>

      <section id="antixray-pane-paper" v-show="tab === 'paper'" class="tab-panel" role="tabpanel" aria-labelledby="antixray-tab-paper">
        <div v-if="paperAvailability !== '不可用'" class="restart-notice" role="note"><Icon name="rotate-cw" :size="16" /><strong>{{ t('Paper 设置保存后，需重启服务器生效') }}</strong></div>
        <div v-if="paperAvailability === '不可用'" class="empty-state">{{ t('当前服务端不支持 Paper 内置防矿透，世界设置不可用。') }}</div>
        <div class="section-stack"><TableSection v-for="(section, index) in paperSections" :key="`paper-${index}`" :section="section" :namespace="namespace" :active="tab === 'paper'" /><div v-if="!paperSections.length" class="empty-state">{{ t('当前面板未提供 Paper 世界设置。') }}</div></div>
        <details v-if="textSections.length" class="protection-notes"><summary><Icon name="book-open" :size="15" />{{ t('防护方案与适用范围') }}</summary><div v-for="(section, index) in textSections" :key="index" class="markdown" v-html="renderMarkdown(section.markdown)" /></details>
      </section>

      <section id="antixray-pane-config" v-show="tab === 'config'" class="tab-panel" role="tabpanel" aria-labelledby="antixray-tab-config">
        <template v-if="configVisited">
        <template v-if="groupedForms.length || otherForms.length">
          <div class="config-layout">
            <aside class="config-navigation" :aria-label="t('监测配置分组')"><button v-for="group in groupedForms" :key="group.id" type="button" :class="{ selected: configTab === group.id }" :aria-pressed="configTab === group.id" @click="configTab = group.id"><Icon :name="group.icon" :size="16" /><span>{{ t(group.label) }}</span></button></aside>
            <div class="config-content"><section v-for="group in groupedForms" v-show="activeConfigGroup === group.id" :key="group.id" class="config-group" :aria-label="t(group.label)"><p class="group-description">{{ t(group.description) }}</p><FormSection v-for="section in group.sections" :key="section.workspaceKey" :section="section" :namespace="namespace" :active="tab === 'config' && activeConfigGroup === group.id" /></section><FormSection v-for="section in otherForms" :key="section.workspaceKey" :section="section" :namespace="namespace" :active="tab === 'config'" /></div>
          </div>
        </template>
        <div v-else class="empty-state"><Icon name="lock-keyhole" :size="20" /><span>{{ t(auth.can('antixray.manage') ? '当前面板未提供可配置项。' : '当前账号无权管理反矿透配置。') }}</span></div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.antixray-workspace { width:100%; max-width:1440px; min-width:0; margin:0 auto; color:var(--lc-text); }
.health-strip { display:flex; align-items:center; gap:18px; min-width:0; padding:12px 0; border-top:1px solid var(--el-border-color-light); border-bottom:1px solid var(--el-border-color-light); }
.health-label { display:flex; align-items:center; gap:7px; flex-shrink:0; font-size:12px; font-weight:650; color:var(--el-color-primary); }.health-metrics { flex:1; display:flex; flex-wrap:wrap; gap:10px 22px; margin:0; min-width:0; }.health-metric { display:flex; gap:8px; align-items:baseline; min-width:0; }.health-metric dt { font-size:11px; color:var(--lc-text-soft); }.health-metric dd { margin:0; font-size:13px; font-weight:650; font-variant-numeric:tabular-nums; overflow-wrap:anywhere; }.health-metrics.stale { opacity:.65; }.empty-health { font-size:12px; color:var(--lc-text-soft); flex:1; }
.icon-button { display:grid; place-items:center; width:28px; height:28px; flex-shrink:0; margin-left:auto; padding:0; border:0; border-radius:var(--lc-radius-control); background:transparent; color:var(--lc-text-soft); cursor:pointer; }.icon-button:disabled { opacity:.45; cursor:default; }.stats-error { padding:8px 0 0; color:var(--el-color-danger); font-size:12px; overflow-wrap:anywhere; }
.workspace-content,.tab-panel,.section-stack,.config-content,.config-group { min-width:0; }
.review-notice { display:flex; align-items:flex-start; gap:9px; margin-bottom:18px; padding:10px 12px; background:var(--el-fill-color-light); border-radius:6px; color:var(--lc-text-soft); }.review-notice > :first-child { flex-shrink:0; margin-top:2px; }.review-notice p { margin:0; font-size:12px; line-height:1.7; }
.record-switch { display:flex; gap:6px; margin:0 0 12px; padding:3px; overflow-x:auto; }.record-switch button { flex-shrink:0; padding:7px 12px; border:1px solid transparent; border-radius:6px; background:transparent; color:var(--lc-text-soft); font-size:12px; cursor:pointer; }.record-switch button.selected { color:var(--lc-text); background:var(--lc-selected-bg); box-shadow:var(--lc-selection-shadow); font-weight:600; }
.section-stack { display:grid; gap:20px; }.additional-sections { margin-top:20px; }.section-stack :deep(.table-section) { min-width:0; max-width:100%; }.section-stack :deep(.el-table) { width:100%; }.section-stack :deep(.pager) { max-width:100%; }.section-stack :deep(.toolbar) { min-width:0; }
.restart-notice { display:flex; align-items:center; gap:8px; padding:10px 12px; border-left:2px solid var(--el-color-warning); background:var(--el-color-warning-light-9); margin-bottom:16px; }.restart-notice > :first-child { color:var(--el-color-warning); flex-shrink:0; }.restart-notice strong { font-size:13px; font-weight:600; }
.protection-notes { margin-top:18px; padding:14px 0; border-top:1px solid var(--el-border-color-light); font-size:12px; color:var(--lc-text-soft); }.protection-notes summary { display:flex; align-items:center; gap:7px; width:fit-content; color:var(--lc-text); cursor:pointer; font-weight:600; }.protection-notes summary::after { content:'＋'; margin-left:5px; color:var(--lc-text-soft); }.protection-notes[open] summary::after { content:'−'; }.markdown { margin-top:12px; line-height:1.85; overflow-wrap:anywhere; }.markdown :deep(ul) { padding-left:20px; }.markdown :deep(li + li) { margin-top:8px; }.markdown :deep(pre) { max-width:100%; overflow:auto; }.markdown :deep(code) { white-space:pre-wrap; }
.config-layout { display:grid; grid-template-columns:164px minmax(0,1fr); gap:24px; align-items:start; }.config-navigation { min-width:0; display:grid; gap:5px; padding-top:4px; }.config-navigation button { display:flex; align-items:center; gap:9px; width:100%; padding:11px 12px; border:0; border-radius:5px; background:transparent; color:var(--lc-text-soft); font-size:12px; text-align:left; cursor:pointer; }.config-navigation button.selected { background:var(--lc-selected-bg); color:var(--lc-text); box-shadow:var(--lc-selection-shadow); font-weight:600; }.group-description { margin:0 0 18px; font-size:12px; line-height:1.8; color:var(--lc-text-soft); }.config-content :deep(.form-surface) { min-width:0; max-width:100%; padding:0; }.config-content :deep(.form-surface + .form-surface) { margin-top:24px; }.config-content :deep(.surface-header) { margin-bottom:18px; }.config-content :deep(.el-input-number),.config-content :deep(.el-select) { max-width:100%; }
.empty-state { display:flex; align-items:center; justify-content:center; gap:10px; padding:36px 16px; color:var(--lc-text-soft); font-size:13px; line-height:1.7; text-align:center; }
button:focus-visible,summary:focus-visible { outline:2px solid var(--lc-focus-ink); outline-offset:2px; }
@media (hover:hover) { .record-switch button:hover,.icon-button:hover,.config-navigation button:hover { color:var(--el-color-primary); background:var(--el-fill-color-light); } }
@media (max-width:900px) { .health-strip { flex-wrap:wrap; gap:12px; }.health-label { width:calc(100% - 44px); }.health-metrics { order:2; flex-basis:100%; gap:10px 20px; }.icon-button { margin-left:0; }.config-layout { grid-template-columns:142px minmax(0,1fr); gap:16px; } }
@media (max-width:700px) { .config-layout { grid-template-columns:minmax(0,1fr); gap:14px; }.config-navigation { display:flex; flex-wrap:wrap; gap:4px; }.config-navigation button { width:auto; padding:9px 10px; }.section-stack :deep(.pager .el-pagination__total) { display:none; }.section-stack :deep(.pager .el-pager li) { min-width:24px; }.section-stack :deep(.pager) { gap:3px; }.health-metric { flex-basis:calc(50% - 10px); flex-wrap:wrap; gap:3px 7px; } }
</style>
