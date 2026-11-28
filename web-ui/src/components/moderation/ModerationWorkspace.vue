<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
const route = useRoute()
const router = useRouter()
const app = useApp()
const defaultTab = props.namespace === 'anticheat' || props.id === 'anticheat' ? 'violations' : 'punishments'
const stats = ref([])
const statsError = ref('')
const connected = ref(socket.connected)
const commands = ref([])
const commandsLoading = ref(false)
const commandsError = ref('')
const command = ref(null)
const commandValues = ref({})
const commandBusy = ref(false)
const commandConfirming = ref(false)
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
let statsPending = false
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
const panelFor = namespace => app.panels.find(panel => panel.namespace === namespace)
  || (props.namespace === namespace ? props.panel : null)
const canViewBan = computed(() => !!panelFor('ban') && auth.can('ban.view'))
const canViewAnti = computed(() => !!panelFor('anticheat') && auth.can('anticheat.view'))
const canManageAnti = computed(() => canViewAnti.value && auth.can('anticheat.manage'))
const backend = computed(() => stats.value.find(item => item.label === '检测后端')?.value || 'GrimAC')
function backendText(message) { return t(message).replaceAll('GrimAC', backend.value) }
const allSections = computed(() => ['ban', 'anticheat'].flatMap(namespace => {
  return (panelFor(namespace)?.sections || []).map(s => ({ ...s, ownerNamespace: namespace }))
}))
const settingsSections = computed(() => allSections.value.filter(s => s.type === 'form' && auth.can(`${s.ownerNamespace}.view`)))
const violationSections = computed(() => allSections.value.filter(s => s.ownerNamespace === 'anticheat' && s.type === 'table').map(s => ({ ...s, toolbarActions: [] })))
const punishmentSections = computed(() => allSections.value.filter(s => s.ownerNamespace === 'ban' && s.type === 'table'))
const commandFields = computed(() => (command.value?.args || []).map(a => ({ ...a, key: a.name, label: t(({target:'玩家名称或 UUID',page:'页码',session:'会话编号',name:'检测名称',match:'匹配条件',grep:'详情筛选',detailed:'详细记录',verbose:'详细信息',delete:'完成后删除源数据',flagId:'违规日志编号',message:'告警内容',src:'源存储',dst:'目标存储',here:'保留当前位置'})[a.name] || a.label || a.name), type: a.type === 'int' ? 'number' : a.type === 'enum' ? 'select' : a.type === 'bool' ? 'bool' : 'text', options: (a.options || []).map(value => ({value,label:value})) })))
const visibleTabs = computed(() => [
  { id: 'punishments', label: '处罚记录', icon: 'shield-ban', visible: canViewBan.value },
  { id: 'violations', label: 'GrimAC 违规', icon: 'scan-search', visible: canViewAnti.value },
  { id: 'tools', label: '管理工具', icon: 'wrench', visible: canViewAnti.value },
  { id: 'grim-config', label: 'GrimAC 配置', icon: 'file-cog', visible: canManageAnti.value },
  { id: 'settings', label: '设置', icon: 'settings-2', visible: canViewAnti.value || canViewBan.value }
].filter(t => t.visible))
const tab = computed(() => {
  const available = visibleTabs.value
  const requested = route.query.tab || defaultTab
  return available.find(item => item.id === requested)?.id
    || available.find(item => item.id === defaultTab)?.id
    || available[0]?.id
})
const filteredCommands = computed(() => commands.value.filter(item =>
  `${backendText(item.label)} ${item.command} ${item.id}`.toLocaleLowerCase().includes(toolQuery.value.trim().toLocaleLowerCase())))
watch(filteredCommands, available => {
  if (available.length && !available.some(item => item.id === command.value?.id)) chooseCommand(available[0])
})
watch(tab, id => {
  syncOutputPolling()
  if (id === 'settings') settingsVisited.value = true
  if (id === 'grim-config') configVisited.value = true
  if (id === 'tools' && !commands.value.length) loadCommands()
}, { immediate: true })

async function loadStats() {
  if (!canViewAnti.value || disposed || statsPending) return
  statsPending = true
  try {
    const result = requireSuccess(await api.get('/api/ext/anticheat/stats'))
    if (disposed) return
    if (!Array.isArray(result) && !Array.isArray(result?.items)) throw new Error(t('统计接口返回格式错误'))
    stats.value = Array.isArray(result) ? result : result.items
    statsError.value = ''
  } catch (e) { if (!disposed) statsError.value = e.message }
  finally { statsPending = false }
}
async function loadCommands() {
  if (!canViewAnti.value || commandsLoading.value || disposed) return
  commandsLoading.value = true
  commandsError.value = ''
  try {
    const result = requireSuccess(await api.get('/api/ext/anticheat/commands'))
    if (disposed) return
    if (!Array.isArray(result?.commands)) throw new Error(t('工具列表接口返回格式错误'))
    commands.value = (result.commands || []).filter(op => auth.can(op.permission))
    if (!command.value && commands.value.length) chooseCommand(commands.value[0])
  } catch (e) {
    if (disposed) return
    commands.value = []
    commandsError.value = e.message
  } finally { if (!disposed) commandsLoading.value = false }
}
function chooseCommand(item) {
  resetOutputPolling()
  command.value = item
  commandMessage.value = ''
  commandValues.value = Object.fromEntries((item.args || []).map(f => [f.name, f.name === 'target' && item.id === 'list' ? 'players' : f.type === 'bool' ? false : null]))
}
async function runCommand() {
  if (disposed || !command.value || commandBusy.value || commandConfirming.value || !command.value.supported || !auth.can(command.value.permission)) return
  const selectedCommand = command.value
  const selectedGeneration = outputGeneration
  if (selectedCommand.permission === 'anticheat.manage') {
    commandConfirming.value = true
    const confirmed = await ElMessageBox.confirm(t('确定执行「{name}」？', { name: backendText(selectedCommand.label) }), t('确认操作'), { type: 'warning', confirmButtonText: t('执行'), cancelButtonText: t('取消') }).then(() => true, () => false)
    commandConfirming.value = false
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
function selectTab(id) {
  if (id !== tab.value) {
    return router.push({ path: route.path, query: { ...route.query, tab: id }, hash: route.hash })
  }
}
async function onTabKeydown(event, index) {
  const direction = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  if (!direction && event.key !== 'Home' && event.key !== 'End') return
  event.preventDefault()
  const last = visibleTabs.value.length - 1
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? last : (index + direction + last + 1) % (last + 1)
  const target = event.currentTarget.parentElement.children[next]
  const failure = await selectTab(visibleTabs.value[next].id)
  if (!failure) target?.focus()
}
function onStatus(value) { connected.value = value }
onMounted(() => {
  stopStatus = socket.onStatus(onStatus)
  document.addEventListener('visibilitychange', syncOutputPolling)
})
useAutoRefresh(loadStats, { seconds: () => 30, active: () => canViewAnti.value, immediate: true })
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
      <div class="title-lockup"><h1><Icon name="shield-check" :size="22" />{{ t('封禁与反作弊') }}</h1></div>
      <div class="connection-state" :class="{ offline: !connected }"><span class="state-dot" />{{ t(connected ? '服务已连接' : '服务未连接') }}</div>
    </header>
    <section v-if="canViewAnti" class="health-strip" :class="{ degraded: statsError }" :aria-label="backendText('GrimAC 运行状态')">
      <div class="health-label"><span class="health-mark" />{{ backend }} <small>{{ t('运行状态') }}</small></div>
      <div v-if="statsError" class="health-muted">{{ t('状态暂不可用') }}</div>
      <div v-else-if="stats.length" v-for="item in stats" :key="item.label" class="health-metric"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
      <div v-else class="health-muted">{{ t('等待状态数据') }}</div>
    </section>
    <nav class="workspace-tabs" :aria-label="t('封禁管理分区')" role="tablist">
      <button v-for="(item, index) in visibleTabs" :key="item.id" :class="{ active: tab === item.id }" :aria-selected="tab === item.id" :tabindex="tab === item.id ? 0 : -1" role="tab" type="button" @click="selectTab(item.id)" @keydown="onTabKeydown($event, index)"><Icon :name="item.icon" :size="15" />{{ backendText(item.label) }}</button>
    </nav>
    <main class="workspace-content">
      <section v-show="tab === 'punishments'" class="tab-panel" :class="{ 'lc-pane-in': tab === 'punishments' }">
        <div class="section-stack"><TableSection v-for="(section, index) in punishmentSections" :key="index" :section="section" :namespace="section.ownerNamespace" :active="tab === 'punishments'" /></div>
        <el-empty v-if="!punishmentSections.length" :description="t('暂无可查看的处罚内容')" />
      </section>
      <section v-show="tab === 'violations'" class="tab-panel" :class="{ 'lc-pane-in': tab === 'violations' }">
        <div class="section-stack"><TableSection v-for="(section, index) in violationSections" :key="index" :section="section" :namespace="section.ownerNamespace" :active="tab === 'violations'" /></div>
        <el-empty v-if="!violationSections.length" :description="t('暂无违规记录')" />
      </section>
      <section v-show="tab === 'tools'" class="tab-panel tools-panel">
        <div v-if="commandsLoading" class="inline-state">{{ t('正在读取可用工具…') }}</div>
        <div v-else-if="commandsError" class="inline-state error">{{ commandsError }} <el-button link @click="loadCommands">{{ t('重试') }}</el-button></div>
        <div v-else-if="!commands.length" class="inline-state">{{ t('当前服务未提供可用工具。') }}</div>
        <div v-else class="tools-layout">
          <aside class="tool-list" :aria-label="t('管理工具')">
            <el-input v-model="toolQuery" clearable :disabled="commandBusy || commandConfirming" :placeholder="t('搜索工具')" :aria-label="t('搜索工具')"><template #prefix><Icon name="search" :size="14" /></template></el-input>
            <div class="tool-options">
              <button v-for="item in filteredCommands" :key="item.id" type="button" :disabled="commandConfirming" :aria-pressed="command?.id === item.id" :class="{ selected: command?.id === item.id }" @click="chooseCommand(item)"><strong>{{ backendText(item.label) }}</strong><span>{{ item.command }}{{ item.supported ? '' : t(' · 使用受限') }}</span></button>
              <p v-if="!filteredCommands.length" class="tool-empty">{{ t('没有匹配的工具') }}</p>
            </div>
          </aside>
          <div v-if="command && filteredCommands.some(item => item.id === command.id)" class="tool-form">
            <h3>{{ backendText(command.label) }}</h3><p v-if="command.description">{{ backendText(command.description) }}</p>
            <el-form :disabled="commandBusy || commandConfirming" label-position="top" @submit.prevent="runCommand">
              <el-form-item v-for="field in commandFields" :key="field.key" :label="field.label" :required="field.required"><FieldInput v-model="commandValues[field.key]" :field="field" /></el-form-item>
              <el-alert v-if="!command.supported" :title="backendText(command.reason)" type="info" :closable="false" />
              <pre v-if="commandMessage" class="command-output">{{ commandMessage }}</pre>
              <div class="command-actions"><el-button type="primary" native-type="submit" :loading="commandBusy" :disabled="commandConfirming || !command.supported || !auth.can(command.permission)"><Icon name="play" :size="14" />{{ t('执行工具') }}</el-button><el-button v-if="executionId" @click="readOutput"><Icon name="refresh-cw" :size="14" />{{ t('刷新输出') }}</el-button></div>
            </el-form>
          </div>
        </div>
      </section>
      <section v-if="settingsVisited" v-show="tab === 'settings'" class="tab-panel">
        <div class="section-stack"><FormSection v-for="(section, index) in settingsSections" :key="index" :section="section" :namespace="section.ownerNamespace" :active="tab === 'settings'" /></div>
        <el-empty v-if="!settingsSections.length" :description="t('当前模块未声明设置项')" />
      </section>
      <GrimConfigWorkspace v-if="configVisited && canManageAnti" v-show="tab === 'grim-config'" :active="tab === 'grim-config'" :backend="backend" />
    </main>
  </div>
</template>

<style scoped>
.moderation-workspace { color: var(--lc-text); max-width: 1440px; margin: 0 auto; min-width: 0; }
.command-output { white-space: pre-wrap; overflow-wrap: anywhere; padding: 14px; max-height: 320px; overflow: auto; border: 1px solid var(--el-border-color-light); background: var(--el-fill-color-light); border-radius: 6px; font-size: 12px; line-height: 1.7; }
.command-actions { display:flex; gap:8px; margin-top:16px; }
.tools-layout,.tool-form,.section-stack { min-width:0; }
.tool-list { display:flex; flex-direction:column; gap:10px; min-width:0; }
.tool-options { display:grid; align-content:start; gap:5px; max-height:550px; overflow-y:auto; scrollbar-width:thin; padding-right:4px; }
.tool-empty { color:var(--lc-text-soft); font-size:12px; margin:8px 3px; }
.workspace-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; padding:4px 0 22px; }
h1 { display:flex; align-items:center; gap:10px; margin:0; font-size:22px; line-height:1.35; } h1 :deep(svg) { color:var(--el-color-primary); }
.connection-state { display:flex; align-items:center; gap:7px; font-size:12px; color:#25855b; white-space:nowrap; padding-top:8px; }.connection-state.offline { color:var(--lc-text-soft); }.state-dot,.health-mark { width:7px;height:7px;border-radius:50%;background:currentColor; }.health-mark { color:#31a66b; box-shadow:0 0 0 4px #31a66b18; }
.health-strip { display:flex; align-items:center; gap:22px; min-height:44px; padding:10px 0; border-top:1px solid var(--el-border-color-light); border-bottom:1px solid var(--el-border-color-light); overflow-x:auto; }.health-strip.degraded .health-mark { color:var(--el-color-warning); }.health-label { display:flex; align-items:center; gap:8px; font-size:13px; font-weight:600; white-space:nowrap; }.health-label small { color:var(--lc-text-soft); font-size:11px; font-weight:400; }.health-metric { display:flex; gap:8px; align-items:baseline; white-space:nowrap; font-size:11px; color:var(--lc-text-soft); }.health-metric strong { font-size:13px; color:var(--lc-text); font-weight:600; }.health-muted { color:var(--lc-text-soft); font-size:12px; }
.workspace-tabs { margin-top:18px; }
.tool-list button:focus-visible { outline:2px solid var(--lc-focus-ink); outline-offset:-2px; }
.tab-intro { margin-bottom:18px; }.tab-intro h2 { margin:0 0 4px; font-size:18px; line-height:1.35; }.section-stack { display:grid; gap:16px; }.section-stack :deep(.card) { border-radius:8px; }
.tools-layout { display:grid; grid-template-columns:minmax(200px,240px) minmax(0,1fr); gap:24px; align-items:start; }.tool-list button { display:grid; gap:4px; width:100%; text-align:left; border:1px solid transparent; background:transparent; color:var(--lc-text); border-radius:var(--lc-radius-control); padding:9px 11px; cursor:pointer; }.tool-list button:hover { background:var(--el-fill-color-light); }.tool-list button.selected { background:var(--lc-selected-bg); box-shadow:var(--lc-selection-shadow); }.tool-list button:disabled { opacity:.6; cursor:default; }.tool-list strong { font-size:13px; }.tool-list span { color:var(--lc-text-soft); font-size:11px; line-height:1.5; overflow-wrap:anywhere; }.tool-form { min-height:260px; padding:4px 0 0 24px; border-left:1px solid var(--el-border-color-light); }.tool-form h3 { margin:0 0 4px; font-size:16px; }.tool-form p { margin:0 0 18px; color:var(--lc-text-soft); font-size:12px; }.inline-state { padding:30px 0; color:var(--lc-text-soft); font-size:13px; }.inline-state.error { color:var(--el-color-danger); }
@media (max-width:700px) { .workspace-header { flex-direction:column; padding-bottom:16px; }.connection-state { padding-top:0; }.health-strip { gap:15px; }.workspace-tabs { margin-top:14px; }.tools-layout { grid-template-columns:1fr; }.tool-form { padding:18px 0 0; border-left:0; border-top:1px solid var(--el-border-color-light); }.tool-options { max-height:240px; } h1 { font-size:21px; } }
</style>
