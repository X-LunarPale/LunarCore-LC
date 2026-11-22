<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../../api'
import MinecraftTextPreview from '../text-preview/MinecraftTextPreview.vue'
import WorkspaceTabs from '../panel/WorkspaceTabs.vue'
import { useAuth } from '../../stores/auth'
import { requireSuccess } from '../../utils/panel'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { checkSettings, checkStatus, controlKeys, whitelistKeys, messageKeys, maintenanceKeys, motdKeys, normalKeys, normalizeWhitelist, pick, same, validateText, validateSchedule, effectiveEnabled, toLocalTime, fromLocalTime } from './maintenance'
import { t, localeTag } from '../../i18n'

defineProps({ panel: { type: Object, required: true } })
const auth = useAuth()
const canManage = computed(() => auth.can('motd.manage'))
const savedNormal = ref(null)
const savedMaintenance = ref(null)
const scheduleSupported = ref(true)
const normal = reactive({ enabled: false, line1: '', line2: '' })
const maintenance = reactive({ enabled: false, allowJoin: false, kickOnline: false, scheduleEnabled: false, scheduleStart: '', scheduleEnd: '', whitelist: '', joinMessage: '', kickMessage: '', line1: '', line2: '' })
const status = ref(null)
const activeTab = ref('control')
const motdMode = ref('normal')
const loading = ref(false)
const pending = ref('')
const error = ref('')
const statusError = ref('')
const notice = ref('')
const uncertain = ref(false)
const updatedAt = ref(null)
const updatedTime = computed(() => updatedAt.value?.toLocaleTimeString(localeTag.value))
let disposed = false
let refreshingStatus = false
let statusReadAt = 0
const ready = computed(() => !!savedNormal.value && !!savedMaintenance.value)
const busy = computed(() => loading.value || !!pending.value)
const locked = computed(() => busy.value || !canManage.value || uncertain.value || !ready.value)
const normalDirty = computed(() => ready.value && !same(normal, savedNormal.value, normalKeys))
const controlDirty = computed(() => ready.value && !same(maintenance, savedMaintenance.value, controlKeys))
const whitelistDirty = computed(() => ready.value && !same(maintenance, savedMaintenance.value, whitelistKeys))
const messagesDirty = computed(() => ready.value && !same(maintenance, savedMaintenance.value, messageKeys))
const motdDirty = computed(() => ready.value && !same(maintenance, savedMaintenance.value, motdKeys))
const dirty = computed(() => normalDirty.value || controlDirty.value || whitelistDirty.value || messagesDirty.value || motdDirty.value)
const tabs = computed(() => [
  { id: 'control', label: t('维护控制'), panelId: 'maintenance-control', dirty: controlDirty.value },
  { id: 'whitelist', label: t('白名单'), panelId: 'maintenance-whitelist-panel', dirty: whitelistDirty.value },
  { id: 'messages', label: t('提示消息'), panelId: 'maintenance-messages', dirty: messagesDirty.value },
  { id: 'motd', label: t('服务器简介'), panelId: 'maintenance-motd', dirty: normalDirty.value || motdDirty.value }
])
const keysByKind = { normal: normalKeys, control: controlKeys, whitelist: whitelistKeys, messages: messageKeys, motd: motdKeys }
const saveKind = computed(() => activeTab.value === 'motd' ? motdMode.value === 'normal' ? 'normal' : 'motd' : activeTab.value)
const currentDirty = computed(() => ({ normal: normalDirty.value, control: controlDirty.value, whitelist: whitelistDirty.value, messages: messagesDirty.value, motd: motdDirty.value }[saveKind.value]))
const whitelistCount = computed(() => maintenance.whitelist.split(/\r\n|\n|\r/).filter(line => line.trim()).length)
const liveActive = computed(() => status.value?.enabled ?? effectiveEnabled(savedMaintenance.value || maintenance))
const localStart = computed({ get: () => toLocalTime(maintenance.scheduleStart), set: value => { maintenance.scheduleStart = fromLocalTime(value) } })
const localEnd = computed({ get: () => toLocalTime(maintenance.scheduleEnd), set: value => { maintenance.scheduleEnd = fromLocalTime(value) } })
const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
const scheduleExpired = computed(() => maintenance.scheduleEnabled && maintenance.scheduleEnd && Date.parse(maintenance.scheduleEnd) <= Date.now())
const previewVariables = computed(() => statusError.value ? {} : pick(status.value || {}, ['online', 'max', 'version']))
const scheduleLabel = computed(() => t({
  disabled: '手动控制', pending: '等待开始', active: '计划进行中', completed: '计划已结束'
}[status.value?.schedulePhase] || (savedMaintenance.value?.scheduleEnabled ? '等待更新' : '手动控制')))
const formatTime = value => {
  const date = new Date(value)
  return !value || Number.isNaN(date.getTime()) ? t('未设置') : date.toLocaleString(localeTag.value, { dateStyle: 'short', timeStyle: 'short' })
}
const endpoint = name => `/api/ext/motd/${name}`
const message = cause => cause.status === 403 ? t('当前账号无权执行此操作。请联系管理员。') : t(cause.message || '操作失败，请稍后重试')

async function confirm(text, title = t('确认操作'), danger = false) {
  try {
    await ElMessageBox.confirm(text, title, { confirmButtonText: t('确认执行'), cancelButtonText: t('取消'), type: danger ? 'warning' : 'info', closeOnClickModal: false, confirmButtonClass: danger ? 'el-button--danger' : '' })
    return true
  } catch { return false }
}
async function refreshStatus() {
  if (refreshingStatus || disposed) return
  refreshingStatus = true
  try {
    const row = checkStatus(requireSuccess(await api.get(endpoint('maintenance/status'))))
    if (disposed) return
    status.value = row
    statusError.value = ''
    updatedAt.value = new Date()
    statusReadAt = updatedAt.value.getTime()
  } catch (cause) {
    if (!disposed) statusError.value = message(cause)
  } finally { refreshingStatus = false }
}
async function load() {
  if (busy.value) return
  loading.value = true
  try {
    if (dirty.value && !await confirm(t('重新加载将放弃本页所有未保存修改。是否继续？'), t('放弃未保存修改'))) return
    const [n, m] = await Promise.all([api.get(endpoint('settings')), api.get(endpoint('maintenance'))])
    const nextNormal = checkSettings(requireSuccess(n))
    const nextMaintenance = checkSettings(requireSuccess(m), true)
    if (disposed) return
    savedNormal.value = nextNormal
    savedMaintenance.value = nextMaintenance
    scheduleSupported.value = typeof m?.scheduleEnabled === 'boolean'
    Object.assign(normal, nextNormal)
    Object.assign(maintenance, nextMaintenance)
    uncertain.value = false
    error.value = ''
    notice.value = ''
    await refreshStatus()
  } catch (cause) { if (!disposed) error.value = message(cause) }
  finally { if (!disposed) loading.value = false }
}
function validate(body, isMaintenance) {
  for (const key of isMaintenance ? ['joinMessage', 'kickMessage', 'line1', 'line2'] : motdKeys)
    validateText(body[key], t({ joinMessage: '入服拒绝提示', kickMessage: '踢出提示', line1: '简介第一行', line2: '简介第二行' }[key]), t)
  if (isMaintenance) {
    body.whitelist = normalizeWhitelist(body.whitelist, t)
    validateSchedule(body, t)
  }
}
async function save(kind) {
  if (locked.value) return
  pending.value = kind
  error.value = ''
  notice.value = ''
  let submitted = false
  try {
    const isNormal = kind === 'normal'
    const keys = keysByKind[kind]
    if (!keys) throw new Error(t('无效的保存分区'))
    const baseline = isNormal ? savedNormal.value : savedMaintenance.value
    const body = isNormal ? pick(normal, normalKeys) : { ...savedMaintenance.value, ...pick(maintenance, keys) }
    validate(body, !isNormal)
    const current = checkSettings(requireSuccess(await api.get(endpoint(isNormal ? 'settings' : 'maintenance'))), !isNormal)
    if (!same(current, baseline, isNormal ? normalKeys : maintenanceKeys)) {
      uncertain.value = true
      throw new Error(t('服务器设置已被其他操作修改。请重新加载后再保存。'))
    }
    if (!isNormal) {
      const now = status.value?.serverTime ? Date.parse(status.value.serverTime) + Date.now() - statusReadAt : Date.now()
      const activeBefore = effectiveEnabled(current, now)
      const activeAfter = effectiveEnabled(body, now)
      const enabling = !activeBefore && activeAfter
      const kicking = activeAfter && body.kickOnline && (!activeBefore || !current.kickOnline)
      if (enabling || kicking) {
        const text = `${enabling ? t('将启用维护模式，服务器列表立即显示维护简介。') : ''}${t(body.allowJoin ? '非白名单玩家仍可加入。' : '非白名单玩家将无法加入。')}${t(kicking ? '保存后将立即向非白名单在线玩家发起一次踢出请求，使用本次保存的白名单及踢出提示。' : '本次不会自动踢出在线玩家。')}`
        if (!await confirm(text, t(kicking ? '保存并踢出在线玩家' : '启用维护模式'), kicking)) return
      }
    }
    if (!canManage.value) throw new Error(t('当前账号已无管理权限'))
    if (!isNormal && !scheduleSupported.value) {
      for (const key of ['scheduleEnabled', 'scheduleStart', 'scheduleEnd']) delete body[key]
    }
    submitted = true
    const result = requireSuccess(await api.post(endpoint(isNormal ? 'settings' : 'maintenance'), body))
    const verified = checkSettings(requireSuccess(await api.get(endpoint(isNormal ? 'settings' : 'maintenance'))), !isNormal)
    if (disposed) return
    if (isNormal) { savedNormal.value = verified; Object.assign(normal, verified) }
    else { savedMaintenance.value = verified; Object.assign(maintenance, pick(verified, keys)) }
    notice.value = result?.message || t('设置已保存并生效')
    await refreshStatus()
  } catch (cause) {
    if (disposed) return
    error.value = message(cause)
    if (submitted) { uncertain.value = true; error.value += t(' 请重新加载核对实际生效设置后再继续操作。') }
  } finally { if (!disposed) pending.value = '' }
}
function resetCurrent() {
  if (locked.value) return
  const isNormal = saveKind.value === 'normal'
  Object.assign(isNormal ? normal : maintenance, pick(isNormal ? savedNormal.value : savedMaintenance.value, keysByKind[saveKind.value]))
  notice.value = ''
}
async function kick() {
  if (locked.value || !liveActive.value || statusError.value) return
  pending.value = 'kick'
  error.value = ''
  notice.value = ''
  let submitted = false
  try {
    const current = checkSettings(requireSuccess(await api.get(endpoint('maintenance'))), true)
    if (!same(current, savedMaintenance.value, maintenanceKeys)) {
      uncertain.value = true
      throw new Error(t('服务器维护设置已变化。请重新加载核对后再执行踢出。'))
    }
    const count = current.whitelist.split('\n').filter(Boolean).length
    if (!await confirm(t('将立即向非白名单在线玩家发起踢出请求。使用已保存白名单（{count} 项）及踢出提示：{message}。{draft}允许加入及自动踢出开关不影响本次手动操作。', { count, message: current.kickMessage || t('（空提示）'), draft: dirty.value ? t('本页未保存修改不会参与此次操作。') : '' }), t('立即踢出非白名单玩家'), true)) return
    if (!canManage.value) throw new Error(t('当前账号已无管理权限'))
    submitted = true
    const result = requireSuccess(await api.post(endpoint('maintenance/kick'), { row: {} }))
    if (!disposed) { notice.value = result?.message || t('已发起维护踢出请求'); await refreshStatus() }
  } catch (cause) {
    if (disposed) return
    error.value = message(cause)
    if (submitted) { uncertain.value = true; error.value += t(' 请重新加载核对状态，避免重复执行。') }
  } finally { if (!disposed) pending.value = '' }
}
async function leave() {
  if (busy.value) { await ElMessageBox.alert(t('当前操作尚未结束，请完成后再离开。'), t('操作进行中')); return false }
  return !dirty.value || await confirm(t('本页存在未保存修改，离开后将丢失。是否继续？'), t('离开维护工作台'))
}
function beforeUnload(event) {
  if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' }
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
useAutoRefresh(refreshStatus, { seconds: () => 5, namespace: 'motd', active: () => ready.value && !busy.value })
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onBeforeUnmount(() => { disposed = true; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="maintenance-workspace" :aria-busy="busy">
    <header class="page-header">
      <h1 class="page-title">{{ t('服务器展示与维护') }}</h1>
      <el-button :loading="loading" :disabled="busy" :title="t('重新加载')" :aria-label="t('重新加载')" @click="load"><Icon name="refresh-cw" :size="15" /></el-button>
    </header>
    <p v-if="error" class="feedback danger" role="alert">{{ error }}</p>
    <p v-if="notice" class="feedback success" role="status">{{ notice }}</p>
    <p v-if="!canManage" class="feedback" role="note">{{ t('当前账号仅可查看，无权保存设置或踢出玩家。') }}</p>
    <p v-if="uncertain" class="feedback warning" role="alert">{{ t('设置或执行结果需要核对。请先重新加载，再继续操作。') }}</p>
    <div v-if="!ready" class="loading-state" role="status">{{ t(loading ? '正在读取服务器设置…' : '设置尚未加载，请重新加载。') }}</div>
    <template v-else>
      <section class="live-bar" :aria-label="t('当前生效状态')">
        <div class="live-status" :class="{ maintaining: liveActive, unknown: statusError }"><span class="status-dot" /><strong>{{ t(statusError ? '状态读取失败' : liveActive ? '维护中' : '正常开放') }}</strong></div>
        <div class="live-detail">{{ scheduleLabel }}<span v-if="status?.nextTransitionAt"> · {{ formatTime(status.nextTransitionAt) }}</span></div>
        <div class="live-count"><span>{{ t('在线玩家') }}</span><strong>{{ status && !statusError ? status.online : '—' }}</strong></div>
      </section>
      <p v-if="statusError" class="status-error" role="alert">{{ statusError }} <button type="button" @click="refreshStatus">{{ t('重试') }}</button></p>
      <WorkspaceTabs id="maintenance" v-model="activeTab" :items="tabs" label="维护设置分区" />
      <div class="maintenance-main">
        <section v-show="activeTab === 'control'" id="maintenance-control" role="tabpanel" aria-labelledby="maintenance-tab-control">
          <div class="control-layout">
            <fieldset :disabled="locked">
              <div class="mode-selector" role="radiogroup" :aria-label="t('控制方式')">
                <label><input v-model="maintenance.scheduleEnabled" type="radio" :value="false" />{{ t('手动控制') }}</label>
                <label v-if="scheduleSupported"><input v-model="maintenance.scheduleEnabled" type="radio" :value="true" />{{ t('定时维护') }}</label>
              </div>
              <p v-if="!scheduleSupported" class="field-help">{{ t('更新维护模块后可使用定时维护。') }}</p>
              <template v-if="!maintenance.scheduleEnabled">
                <div class="setting-row"><label for="manual-enabled">{{ t('启用维护模式') }}</label><el-switch id="manual-enabled" v-model="maintenance.enabled" :disabled="locked" :aria-label="t('启用维护模式')" /></div>
              </template>
              <div v-else class="schedule-fields">
                <label class="field-label" for="maintenance-schedule-start">{{ t('开始时间') }}</label>
                <input id="maintenance-schedule-start" v-model="localStart" type="datetime-local" />
                <label class="field-label" for="maintenance-schedule-end">{{ t('结束时间') }}<span>{{ t('可选') }}</span></label>
                <input id="maintenance-schedule-end" v-model="localEnd" type="datetime-local" :min="localStart || undefined" />
                <p class="field-help">{{ t('时区：{zone}。不设结束时间则持续维护。', { zone: timeZone }) }}</p>
                <p v-if="scheduleExpired" class="warning-text" role="note">{{ t('此计划已结束，保存后不会进入维护。') }}</p>
              </div>
              <div class="setting-row"><div><label for="maintenance-allow">{{ t('允许非白名单玩家加入') }}</label></div><el-switch id="maintenance-allow" v-model="maintenance.allowJoin" :disabled="locked" :aria-label="t('允许非白名单玩家加入')" /></div>
              <div class="setting-row"><div><label for="maintenance-auto-kick">{{ t('进入维护时踢出在线玩家') }}</label><p>{{ t('仅执行一次，白名单除外；启动和重载不踢人。') }}</p></div><el-switch id="maintenance-auto-kick" v-model="maintenance.kickOnline" :disabled="locked" :aria-label="t('进入维护时踢出在线玩家')" /></div>
            </fieldset>
            <aside class="maintenance-actions">
              <h2>{{ t('在线玩家操作') }}</h2>
              <dl :title="updatedTime ? t('更新于 {time}', { time: updatedTime }) : undefined"><div><dt>{{ t('可踢出人数') }}</dt><dd>{{ status && !statusError ? status.eligible : '—' }}</dd></div><div><dt>{{ t('已保存白名单') }}</dt><dd>{{ savedMaintenance.whitelist.split('\n').filter(Boolean).length }}</dd></div></dl>
              <el-button type="danger" plain :loading="pending === 'kick'" :disabled="locked || !liveActive || !!statusError" @click="kick"><Icon name="log-out" :size="15" />{{ t('立即踢出') }}</el-button>
              <p v-if="!liveActive" class="field-help">{{ t('维护开启后可执行。') }}</p>
              <p v-else-if="dirty" class="warning-text">{{ t('此次操作不会使用未保存修改。') }}</p>
            </aside>
          </div>
        </section>
        <section v-show="activeTab === 'whitelist'" id="maintenance-whitelist-panel" role="tabpanel" aria-labelledby="maintenance-tab-whitelist" class="editor-section">
          <fieldset :disabled="locked">
            <div class="section-heading"><label class="field-label" for="maintenance-whitelist">{{ t('玩家名称或 UUID') }}</label><span class="field-help">{{ t('{count} / 500 项', { count: whitelistCount }) }}</span></div>
            <textarea id="maintenance-whitelist" v-model="maintenance.whitelist" rows="10" maxlength="20000" :placeholder="t('每行一个玩家名称或 UUID')" spellcheck="false" />
            <p class="field-help">{{ t('白名单玩家可在维护期间加入，且不会被维护踢出。每行一项，名称忽略大小写。') }}</p>
          </fieldset>
        </section>
        <section v-show="activeTab === 'messages'" id="maintenance-messages" role="tabpanel" aria-labelledby="maintenance-tab-messages" class="editor-section">
          <fieldset :disabled="locked">
            <label class="field-label" for="maintenance-join-message">{{ t('入服拒绝提示') }}</label>
            <input id="maintenance-join-message" v-model="maintenance.joinMessage" type="text" maxlength="512" />
            <MinecraftTextPreview :legacy="false" :show-help="false" atomic-variables :text="maintenance.joinMessage" :variables="previewVariables" :label="t('入服拒绝提示预览')" />
            <label class="field-label" for="maintenance-kick-message">{{ t('踢出提示') }}</label>
            <input id="maintenance-kick-message" v-model="maintenance.kickMessage" type="text" maxlength="512" />
            <MinecraftTextPreview :legacy="false" :show-help="false" atomic-variables :text="maintenance.kickMessage" :variables="previewVariables" :label="t('踢出提示预览')" />
          </fieldset>
        </section>
        <section v-show="activeTab === 'motd'" id="maintenance-motd" role="tabpanel" aria-labelledby="maintenance-tab-motd" class="editor-section">
          <el-radio-group v-model="motdMode" :aria-label="t('简介类型')" class="motd-selector"><el-radio-button value="normal">{{ t('正常运行') }}</el-radio-button><el-radio-button value="maintenance">{{ t('维护期间') }}</el-radio-button></el-radio-group>
          <fieldset v-if="motdMode === 'normal'" :disabled="locked">
            <div class="setting-row"><div><label for="normal-enabled">{{ t('启用正常简介') }}</label><p>{{ t('关闭后使用服务器原有简介。') }}</p></div><el-switch id="normal-enabled" v-model="normal.enabled" :disabled="locked" :aria-label="t('启用正常简介')" /></div>
            <label class="field-label" for="normal-line1">{{ t('第一行') }}</label><input id="normal-line1" v-model="normal.line1" maxlength="512" type="text" />
            <label class="field-label" for="normal-line2">{{ t('第二行') }}</label><input id="normal-line2" v-model="normal.line2" maxlength="512" type="text" />
            <MinecraftTextPreview :legacy="false" :show-help="false" atomic-variables :lines="[normal.line1, normal.line2]" :variables="previewVariables" :label="t('正常简介预览')" />
          </fieldset>
          <fieldset v-else :disabled="locked">
            <p class="field-help">{{ t('维护期间始终显示，不受正常简介开关影响。') }}</p>
            <label class="field-label" for="maintenance-line1">{{ t('第一行') }}</label><input id="maintenance-line1" v-model="maintenance.line1" maxlength="512" type="text" />
            <label class="field-label" for="maintenance-line2">{{ t('第二行') }}</label><input id="maintenance-line2" v-model="maintenance.line2" maxlength="512" type="text" />
            <MinecraftTextPreview :legacy="false" :show-help="false" atomic-variables :lines="[maintenance.line1, maintenance.line2]" :variables="previewVariables" :label="t('维护简介预览')" />
          </fieldset>
          <p class="field-help">{{ t('支持 MiniMessage 颜色和 {online}、{max}、{version}，每行最多 512 字符。') }}</p>
        </section>
      </div>
      <footer class="maintenance-savebar">
        <span v-if="currentDirty" class="warning-text" role="status">{{ t('尚未保存') }}</span>
        <div><el-button :disabled="locked || !currentDirty" @click="resetCurrent">{{ t('撤销修改') }}</el-button><el-button type="primary" :loading="pending === saveKind" :disabled="locked || !currentDirty" @click="save(saveKind)"><Icon name="save" :size="15" />{{ t('保存当前分区') }}</el-button></div>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.maintenance-workspace { min-width:0; max-width:1280px; margin:0 auto; }
.page-header { margin-bottom:20px; }
.page-header :deep(.el-button),.maintenance-actions :deep(.el-button),.maintenance-savebar :deep(.el-button) { gap:7px; }
.live-bar { display:flex; align-items:center; gap:20px; padding:14px 0; margin-bottom:12px; border-top:1px solid var(--el-border-color); border-bottom:1px solid var(--el-border-color); flex-wrap:wrap; }
.live-status { display:flex; align-items:center; gap:8px; color:var(--el-color-success); font-size:14px; }
.live-status.maintaining,.live-status.unknown { color:var(--el-color-warning); }
.status-dot,.draft-dot { width:7px; height:7px; border-radius:50%; background:currentColor; flex:none; }
.draft-dot { display:inline-block; color:var(--el-color-warning); margin-left:7px; }
.live-detail { font-size:12px; color:var(--lc-text-soft); flex:1; min-width:160px; overflow-wrap:anywhere; }
.live-count { display:flex; gap:12px; align-items:center; font-size:12px; color:var(--lc-text-soft); }
.live-count strong { color:var(--lc-text); font-size:18px; font-variant-numeric:tabular-nums; }
.maintenance-main { padding:0 0 24px; min-width:0; }
.maintenance-main h2 { font-size:15px; line-height:1.5; font-weight:600; margin:0 0 16px; }
.control-layout { display:grid; grid-template-columns:minmax(0,2fr) minmax(220px,1fr); gap:40px; }
fieldset { border:0; margin:0; padding:0; min-width:0; }
fieldset:disabled { opacity:.7; }
.mode-selector { display:flex; align-items:center; flex-wrap:wrap; gap:24px; padding:0 0 20px; }
.mode-selector label { display:flex; gap:8px; align-items:center; font-size:13px; cursor:pointer; }
.mode-selector input { margin:0; width:16px; height:16px; accent-color:var(--el-color-primary); }
.setting-row { min-height:64px; display:flex; justify-content:space-between; align-items:center; gap:20px; padding:15px 0; border-top:1px solid var(--el-border-color-light); }
.setting-row label { font-size:13px; font-weight:500; cursor:pointer; }
.setting-row p,.field-help { color:var(--lc-text-soft); font-size:12px; line-height:1.6; margin:5px 0 0; }
.setting-row :deep(.el-switch) { flex:none; }
.schedule-fields { padding:0 0 20px; max-width:520px; }
.field-label { display:flex; align-items:center; gap:12px; font-size:13px; font-weight:500; margin:20px 0 8px; }
.field-label span { color:var(--lc-text-soft); font-size:12px; font-weight:400; }
input[type=text],input[type=datetime-local],textarea { width:100%; min-width:0; max-width:100%; border:1px solid var(--el-border-color); border-radius:6px; color:var(--lc-text); background:var(--el-fill-color-blank); padding:8px 11px; font:inherit; font-size:13px; line-height:1.6; }
input[type=datetime-local] { min-height:38px; color-scheme:inherit; }
textarea { resize:vertical; min-height:180px; }
.maintenance-actions { border-left:1px solid var(--el-border-color-light); padding-left:28px; min-width:0; }
.maintenance-actions dl { margin:0 0 20px; }
.maintenance-actions dl>div { display:flex; justify-content:space-between; align-items:center; gap:12px; margin:12px 0; font-size:13px; }
.maintenance-actions dt { color:var(--lc-text-soft); }
.maintenance-actions dd { margin:0; font-variant-numeric:tabular-nums; font-weight:600; }
.editor-section { max-width:900px; min-width:0; }
.section-heading { display:flex; justify-content:space-between; align-items:baseline; gap:16px; }
.section-heading .field-label { margin-top:0; }
.motd-selector { margin-bottom:20px; }
.maintenance-savebar { position:sticky; bottom:0; z-index:5; display:flex; align-items:center; justify-content:space-between; gap:12px; padding:14px 0; border-top:1px solid var(--el-border-color); background:var(--lc-bg); }
.maintenance-savebar>span { color:var(--lc-text-soft); font-size:12px; }
.maintenance-savebar>div { display:flex; gap:8px; margin-inline-start:auto; }
.maintenance-savebar :deep(.el-button+.el-button) { margin-left:0; }
.feedback { font-size:13px; line-height:1.7; padding:10px 12px; background:var(--el-fill-color-light); border-left:3px solid var(--el-border-color); margin:0 0 12px; overflow-wrap:anywhere; }
.feedback.danger,.status-error { color:var(--el-color-danger); border-color:var(--el-color-danger); }
.feedback.success { color:var(--el-color-success); border-color:var(--el-color-success); }
.feedback.warning,.warning-text { color:var(--el-color-warning) !important; }
.status-error { font-size:12px; margin:8px 0 16px; }
.status-error button { border:0; color:var(--el-color-primary); background:none; text-decoration:underline; cursor:pointer; }
.loading-state { padding:48px 0; color:var(--lc-text-soft); text-align:center; }
@media(max-width:850px) { .control-layout { grid-template-columns:minmax(0,1fr); gap:24px; }.maintenance-actions { border-left:0; border-top:1px solid var(--el-border-color-light); padding:20px 0 0; }.maintenance-actions dl { max-width:400px; } }
@media(max-width:520px) { .live-bar { gap:10px 16px; }.live-detail { order:3; flex-basis:100%; }.live-count { margin-left:auto; }.maintenance-savebar { align-items:flex-start; flex-direction:column; }.maintenance-savebar>div { width:100%; justify-content:flex-end; }.page-header { align-items:flex-start; }.page-title { flex:1; min-width:180px; } }
</style>
