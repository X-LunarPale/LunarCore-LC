<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, socket, resolveEndpoint } from '../../api'
import { useAuth } from '../../stores/auth'
import TableSection from '../panel/TableSection.vue'
import FormSection from '../panel/FormSection.vue'
import FieldInput from '../panel/FieldInput.vue'
import GrimConfigWorkspace from './GrimConfigWorkspace.vue'
import { requireSuccess } from '../../utils/panel'
import { useApp } from '../../stores/app'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { ElMessageBox } from 'element-plus'
import { t } from '../../i18n'

const props = defineProps({ panel: { type: Object, required: true }, namespace: { type: String, required: true }, id: { type: String, required: true } })
const auth = useAuth()
const tab = ref(props.namespace === 'anticheat' || props.id === 'anticheat' ? 'violations' : 'punishments')
const stats = ref([])
const statsError = ref('')
const connected = ref(socket.connected)
const commands = ref([])
const commandsLoading = ref(false)
const commandsError = ref('')
const command = ref(null)
const commandValues = ref({})
const commandBusy = ref(false)
const commandMessage = ref('')
const toolQuery = ref('')
const settingsVisited = ref(false)
const configVisited = ref(false)
let stopStatus
let outputTimer
let outputReads = 0
let outputGeneration = 0
let outputPending = false
let outputPolling = false
let disposed = false
const executionId = ref('')
function clearOutputTimer() {
  clearInterval(outputTimer)
  outputTimer = undefined
}
function stopOutputPolling() {
  outputPolling = false
  clearOutputTimer()
}
function syncOutputPolling() {
  clearOutputTimer()
  if (!disposed && outputPolling && tab.value === 'tools' && !document.hidden) {
    outputTimer = setInterval(readOutput, 2000)
  }
}
function resetOutputPolling() {
  outputGeneration++
  stopOutputPolling()
  outputReads = 0
  executionId.value = ''
}
async function readOutput() {
  if (disposed || outputPending || !executionId.value || document.hidden || tab.value !== 'tools') return
  const generation = outputGeneration
  const currentExecution = executionId.value
  outputPending = true
  try {
    const result = await api.get(`/api/ext/anticheat/commands/runs/${currentExecution}`)
    if (disposed || generation !== outputGeneration) return
    commandMessage.value = (result.output || []).join('\n') || t('命令已提交，暂无输出。')
    if (!result.pending || ++outputReads >= 60) stopOutputPolling()
  } catch (e) {
    if (disposed || generation !== outputGeneration) return
    stopOutputPolling()
    commandMessage.value = e.message
  } finally { outputPending = false }
}
function startOutputPolling() {
  if (disposed || !executionId.value) return
  outputReads = 0
  outputPolling = true
  syncOutputPolling()
}
watch(tab, syncOutputPolling)

const canViewBan = computed(() => auth.can('ban.view'))
const canManageBan = computed(() => auth.can('ban.manage'))
const canViewAnti = computed(() => auth.can('anticheat.view'))
const canManageAnti = computed(() => auth.can('anticheat.manage'))
const app = useApp()
const canManage = computed(() => canViewAnti.value)
const allSections = computed(() => ['ban', 'anticheat'].flatMap(namespace => {
  const panel = app.panels.find(p => p.namespace === namespace) || (props.namespace === namespace ? props.panel : null)
  return (panel?.sections || []).map(s => ({ ...s, ownerNamespace: namespace }))
}))
const settingsSections = computed(() => allSections.value.filter(s => s.type === 'form' && auth.can(s.savePermission) && auth.can(`${s.ownerNamespace}.view`)))
const violationSections = computed(() => allSections.value.filter(s => s.ownerNamespace === 'anticheat' && s.type === 'table').map(s => ({ ...s, toolbarActions: [] })))
const punishmentSections = computed(() => allSections.value.filter(s => s.ownerNamespace === 'ban' && s.type === 'table'))
const primarySections = violationSections
const commandFields = computed(() => (command.value?.args || []).map(a => ({ ...a, key: a.name, label: t(({target:'玩家名称或 UUID',page:'页码',session:'会话编号',name:'检测名称',match:'匹配条件',grep:'详情筛选',detailed:'详细记录',verbose:'详细信息',delete:'完成后删除源数据',flagId:'违规日志编号',message:'告警内容',src:'源存储',dst:'目标存储',here:'保留当前位置'})[a.name] || a.label || a.name), type: a.type === 'int' ? 'number' : a.type === 'enum' ? 'select' : a.type === 'bool' ? 'bool' : 'text', options: (a.options || []).map(value => ({value,label:value})) })))
const visibleTabs = computed(() => [
  { id: 'punishments', label: '处罚记录', icon: 'shield-ban', visible: canViewBan.value },
  { id: 'violations', label: 'GrimAC 违规', icon: 'scan-search', visible: canViewAnti.value },
  { id: 'tools', label: '管理工具', icon: 'wrench', visible: canManage.value },
  { id: 'grim-config', label: 'GrimAC 配置', icon: 'file-cog', visible: canManageAnti.value },
  { id: 'settings', label: '设置', icon: 'settings-2', visible: canViewAnti.value || canViewBan.value }
].filter(t => t.visible))
const filteredCommands = computed(() => commands.value.filter(item =>
  `${item.label} ${item.command} ${item.id}`.toLocaleLowerCase().includes(toolQuery.value.trim().toLocaleLowerCase())))
watch(filteredCommands, available => {
  if (available.length && !available.some(item => item.id === command.value?.id)) chooseCommand(available[0])
})
watch(visibleTabs, available => {
  if (available.length && !available.some(item => item.id === tab.value)) tab.value = available[0].id
}, { immediate: true })

async function loadStats() {
  if (!canViewAnti.value) return
  try {
    const result = await api.get('/api/ext/anticheat/stats')
    stats.value = Array.isArray(result) ? result : result?.items || []
    statsError.value = ''
  } catch (e) { statsError.value = e.message }
}
async function loadCommands() {
  if (!canManage.value) return
  commandsLoading.value = true
  commandsError.value = ''
  try {
    const result = await api.get('/api/ext/anticheat/commands')
    commands.value = (result.commands || []).filter(op => auth.can(op.permission))
    if (!command.value && commands.value.length) chooseCommand(commands.value[0])
  } catch (e) {
    commands.value = []
    commandsError.value = e.message
  } finally { commandsLoading.value = false }
}
function chooseCommand(item) {
  resetOutputPolling()
  command.value = item
  commandMessage.value = ''
  commandValues.value = Object.fromEntries((item.args || []).map(f => [f.name, f.name === 'target' && item.id === 'list' ? 'players' : f.type === 'bool' ? false : null]))
}
async function runCommand() {
  if (disposed || !command.value || commandBusy.value || !command.value.supported || !auth.can(command.value.permission)) return
  const selectedCommand = command.value
  const selectedGeneration = outputGeneration
  if (selectedCommand.permission === 'anticheat.manage') {
    const confirmed = await ElMessageBox.confirm(t('确定执行「{name}」？', { name: selectedCommand.label }), t('确认操作'), { type: 'warning', confirmButtonText: t('执行'), cancelButtonText: t('取消') }).then(() => true, () => false)
    if (!confirmed) return
  }
  if (disposed || selectedGeneration !== outputGeneration || selectedCommand !== command.value || commandBusy.value || !auth.can(selectedCommand.permission)) return
  resetOutputPolling()
  const generation = outputGeneration
  commandBusy.value = true
  commandMessage.value = ''
  try {
    const args = Object.fromEntries(Object.entries(commandValues.value).filter(([,v]) => v !== null && v !== ''))
    const result = requireSuccess(await api.post('/api/ext/anticheat/commands/run', { id: selectedCommand.id, args }), t('命令执行失败'))
    if (disposed || generation !== outputGeneration) return
    executionId.value = result.executionId
    commandMessage.value = (result.output || []).join('\n') || t('命令已提交，等待输出。')
    startOutputPolling()
  } catch (e) {
    if (!disposed && generation === outputGeneration) commandMessage.value = e.message
  } finally {
    if (!disposed) commandBusy.value = false
  }
}
function sectionTitle(s) { return s.title || t(({ table: '数据列表', form: '配置设置' }[s.type] || '管理内容')) }
function onTab(id) {
  tab.value = id
  if (id === 'settings') settingsVisited.value = true
  if (id === 'grim-config') configVisited.value = true
  if (id === 'tools' && !commands.value.length) loadCommands()
}
function onStatus(value) { connected.value = value }
onMounted(() => {
  stopStatus = socket.onStatus(onStatus)
  document.addEventListener('visibilitychange', syncOutputPolling)
})
useAutoRefresh(loadStats, { active: () => canViewAnti.value, immediate: true })
onBeforeUnmount(() => {
  disposed = true
  resetOutputPolling()
  stopStatus?.()
  document.removeEventListener('visibilitychange', syncOutputPolling)
})
</script>

<template>
  <div class="moderation-workspace">
    <header class="workspace-header">
      <div class="title-lockup"><h1><Icon name="shield-check" :size="22" />{{ t('封禁与反作弊') }}</h1><p>{{ t('集中处理处罚记录、检测事件与 GrimAC 联动设置。') }}</p></div>
      <div class="connection-state" :class="{ offline: !connected }"><span class="state-dot" />{{ t(connected ? '服务已连接' : '服务未连接') }}</div>
    </header>
    <section v-if="canViewAnti" class="health-strip" :class="{ degraded: statsError }" :aria-label="t('GrimAC 运行状态')">
      <div class="health-label"><span class="health-mark" />GrimAC <small>{{ t('运行状态') }}</small></div>
      <div v-if="statsError" class="health-muted">{{ t('状态暂不可用') }}</div>
      <div v-else-if="stats.length" v-for="item in stats" :key="item.label" class="health-metric"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
      <div v-else class="health-muted">{{ t('等待状态数据') }}</div>
    </section>
    <nav class="workspace-tabs" :aria-label="t('封禁管理分区')" role="tablist">
      <button v-for="item in visibleTabs" :key="item.id" :class="{ active: tab === item.id }" :aria-selected="tab === item.id" role="tab" type="button" @click="onTab(item.id)"><Icon :name="item.icon" :size="15" />{{ t(item.label) }}</button>
    </nav>
    <main class="workspace-content">
      <section v-if="tab === 'punishments'" class="tab-panel"><div class="tab-intro"><h2>{{ t('处罚执行与历史记录') }}</h2><p>{{ t('查看封禁记录并执行账号处置操作。') }}</p></div><div class="section-stack"><component v-for="(s, i) in punishmentSections" :is="s.type === 'table' ? TableSection : FormSection" :key="`${s.type}-${i}`" :section="s" :namespace="s.ownerNamespace" /></div><el-empty v-if="!punishmentSections.length" :description="t('暂无可查看的处罚内容')" /></section>
      <section v-else-if="tab === 'violations'" class="tab-panel"><div class="tab-intro"><h2>{{ t('检测事件与违规记录') }}</h2><p>{{ t('查看近期检测事件，按玩家或检测名称筛选。') }}</p></div><div class="section-stack"><component v-for="(s, i) in (violationSections.length ? violationSections : primarySections)" :is="s.type === 'table' ? TableSection : FormSection" :key="`${s.type}-${i}`" :section="s" :namespace="s.ownerNamespace" /></div><el-empty v-if="!primarySections.length" :description="t('暂无违规记录')" /></section>
      <section v-else-if="tab === 'tools'" class="tab-panel tools-panel"><div class="tab-intro"><h2>{{ t('GrimAC 管理工具') }}</h2><p>{{ t('查询检测状态与玩家历史，执行经过授权的管理操作。') }}</p></div><div v-if="commandsLoading" class="inline-state">{{ t('正在读取可用工具…') }}</div><div v-else-if="commandsError" class="inline-state error">{{ commandsError }}</div><div v-else-if="!commands.length" class="inline-state">{{ t('当前服务未提供可用工具。') }}</div><div v-else class="tools-layout"><aside class="tool-list" :aria-label="t('管理工具')"><el-input v-model="toolQuery" clearable :placeholder="t('搜索工具')" :aria-label="t('搜索工具')"><template #prefix><Icon name="search" :size="14" /></template></el-input><div class="tool-options"><button v-for="item in filteredCommands" :key="item.id" type="button" :aria-pressed="command?.id === item.id" :class="{ selected: command?.id === item.id }" @click="chooseCommand(item)"><strong>{{ item.label }}</strong><span>{{ item.command }}{{ item.supported ? '' : t(' · 使用受限') }}</span></button><p v-if="!filteredCommands.length" class="tool-empty">{{ t('没有匹配的工具') }}</p></div></aside><div v-if="command && filteredCommands.some(item => item.id === command.id)" class="tool-form"><h3>{{ command.label }}</h3><p v-if="command.description">{{ command.description }}</p><el-form label-position="top" @submit.prevent="runCommand"><el-form-item v-for="field in commandFields" :key="field.key" :label="field.label" :required="field.required"><FieldInput v-model="commandValues[field.key]" :field="field" /></el-form-item><el-alert v-if="!command.supported" :title="command.reason" type="info" :closable="false" /><pre v-if="commandMessage" class="command-output">{{ commandMessage }}</pre><div class="command-actions"><el-button type="primary" native-type="submit" :loading="commandBusy" :disabled="!command.supported || !auth.can(command.permission)">{{ t('执行工具') }}</el-button><el-button v-if="executionId" @click="readOutput">{{ t('刷新输出') }}</el-button></div></el-form></div></div></section>
      <section v-if="settingsVisited" v-show="tab === 'settings'" class="tab-panel"><div class="tab-intro"><h2>{{ t('联动与记录设置') }}</h2><p>{{ t('调整桥接采集开关和近期记录容量。') }}</p></div><div class="section-stack"><FormSection v-for="(s, i) in settingsSections" :key="i" :section="s" :namespace="s.ownerNamespace" :active="tab === 'settings'" /></div><el-empty v-if="!settingsSections.length" :description="t('当前模块未声明设置项')" /></section>
      <GrimConfigWorkspace v-if="configVisited && canManageAnti" v-show="tab === 'grim-config'" :active="tab === 'grim-config'" />
    </main>
  </div>
</template>

<style scoped>
.moderation-workspace { color: var(--lbr-text); max-width: 1440px; margin: 0 auto; min-width: 0; }
.command-output { white-space: pre-wrap; overflow-wrap: anywhere; padding: 14px; max-height: 320px; overflow: auto; border: 1px solid var(--el-border-color-light); background: var(--el-fill-color-light); border-radius: 6px; font-size: 12px; line-height: 1.7; }
.command-actions { display:flex; gap:8px; margin-top:16px; }
.tools-layout,.tool-form,.section-stack { min-width:0; }
.tool-list { display:flex; flex-direction:column; gap:10px; min-width:0; }
.tool-options { display:grid; align-content:start; gap:5px; max-height:550px; overflow-y:auto; scrollbar-width:thin; padding-right:4px; }
.tool-empty { color:var(--lbr-text-soft); font-size:12px; margin:8px 3px; }
.workspace-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; padding:4px 0 22px; }
h1 { display:flex; align-items:center; gap:10px; margin:0 0 6px; font-size:23px; line-height:1.25; } h1 :deep(svg) { color:var(--el-color-primary); } .title-lockup p,.tab-intro p { margin:0; color:var(--lbr-text-soft); font-size:13px; line-height:1.65; }
.connection-state { display:flex; align-items:center; gap:7px; font-size:12px; color:#25855b; white-space:nowrap; padding-top:8px; }.connection-state.offline { color:var(--lbr-text-soft); }.state-dot,.health-mark { width:7px;height:7px;border-radius:50%;background:currentColor; }.health-mark { color:#31a66b; box-shadow:0 0 0 4px #31a66b18; }
.health-strip { display:flex; align-items:center; gap:22px; min-height:48px; padding:8px 14px; border:1px solid var(--el-border-color-light); border-left:3px solid var(--el-color-success); background:var(--lbr-surface); border-radius:6px; overflow-x:auto; }.health-strip.degraded { border-left-color:var(--el-color-warning); }.health-strip.degraded .health-mark { color:var(--el-color-warning); }.health-label { display:flex; align-items:center; gap:8px; font-size:13px; font-weight:650; white-space:nowrap; }.health-label small { color:var(--lbr-text-soft); font-size:11px; font-weight:400; }.health-metric { display:flex; gap:8px; align-items:baseline; white-space:nowrap; font-size:11px; color:var(--lbr-text-soft); }.health-metric strong { font-size:13px; color:var(--lbr-text); font-weight:650; }.health-muted { color:var(--lbr-text-soft); font-size:12px; }
.workspace-tabs { display:flex; gap:3px; margin:18px 0 24px; border-bottom:1px solid var(--el-border-color-light); overflow-x:auto; }.workspace-tabs button { display:flex; align-items:center; gap:8px; border:0; border-bottom:2px solid transparent; background:transparent; color:var(--lbr-text-soft); padding:11px 14px; margin-bottom:-1px; white-space:nowrap; cursor:pointer; font-size:13px; transition:color 120ms,border-color 120ms; }.workspace-tabs button.active { color:var(--el-color-primary); border-color:var(--el-color-primary); font-weight:650; }.workspace-tabs button:focus-visible,.tool-list button:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }
.tab-intro { margin-bottom:18px; }.tab-intro h2 { margin:0 0 4px; font-size:18px; line-height:1.35; }.section-stack { display:grid; gap:16px; }.section-stack :deep(.card) { border-radius:8px; }
.tools-layout { display:grid; grid-template-columns:minmax(210px, 260px) minmax(0, 1fr); gap:18px; align-items:start; }.tool-list button { display:grid; gap:4px; width:100%; text-align:left; border:1px solid transparent; border-left:2px solid transparent; background:transparent; color:var(--lbr-text); border-radius:5px; padding:9px 11px; cursor:pointer; }.tool-list button:hover { background:var(--el-fill-color-light); }.tool-list button.selected { border-left-color:var(--el-color-primary); background:var(--el-color-primary-light-9); }.tool-list strong { font-size:13px; }.tool-list span { color:var(--lbr-text-soft); font-size:11px; line-height:1.5; overflow-wrap:anywhere; }.tool-form { min-height:260px; padding:22px; border:1px solid var(--el-border-color-light); border-radius:8px; background:var(--lbr-surface); }.tool-form h3 { margin:0 0 4px; font-size:16px; }.tool-form p { margin:0 0 18px; color:var(--lbr-text-soft); font-size:12px; }.inline-state { padding:30px 0; color:var(--lbr-text-soft); font-size:13px; }.inline-state.error { color:var(--el-color-danger); }
@media (max-width:700px) { .workspace-header { flex-direction:column; padding-bottom:16px; }.connection-state { padding-top:0; }.health-strip { gap:15px; }.workspace-tabs { margin-top:14px; }.tools-layout { grid-template-columns:1fr; }.tool-options { max-height:240px; } h1 { font-size:21px; } }
@media (prefers-reduced-motion:reduce) { .workspace-tabs button { transition:none; } }
</style>
