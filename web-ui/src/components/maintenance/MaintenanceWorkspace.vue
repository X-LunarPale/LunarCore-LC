<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../../api'
import MinecraftTextPreview from '../text-preview/MinecraftTextPreview.vue'
import { useAuth } from '../../stores/auth'
import { requireSuccess } from '../../utils/panel'
import { checkSettings, checkStatus, controlKeys, motdKeys, normalKeys, normalizeWhitelist, pick, same, validateText } from './maintenance'
import { t, locale } from '../../i18n'

defineProps({ panel: { type: Object, required: true } })
const auth = useAuth()
const canManage = computed(() => auth.can('motd.manage'))
const savedNormal = ref(null)
const savedMaintenance = ref(null)
const normal = reactive({ enabled: false, line1: '', line2: '' })
const maintenance = reactive({ enabled: false, allowJoin: false, kickOnline: false, whitelist: '', joinMessage: '', kickMessage: '', line1: '', line2: '' })
const status = ref(null)
const loading = ref(false)
const pending = ref('')
const error = ref('')
const statusError = ref('')
const notice = ref('')
const uncertain = ref(false)
const updatedAt = ref('')
let disposed = false
const ready = computed(() => !!savedNormal.value && !!savedMaintenance.value)
const busy = computed(() => loading.value || !!pending.value)
const locked = computed(() => busy.value || !canManage.value || uncertain.value || !ready.value)
const normalDirty = computed(() => ready.value && !same(normal, savedNormal.value, normalKeys))
const controlDirty = computed(() => ready.value && !same(maintenance, savedMaintenance.value, controlKeys))
const motdDirty = computed(() => ready.value && !same(maintenance, savedMaintenance.value, motdKeys))
const dirty = computed(() => normalDirty.value || controlDirty.value || motdDirty.value)
const whitelistCount = computed(() => maintenance.whitelist.split(/\r\n|\n|\r/).filter(line => line.trim()).length)
const activeMotd = computed(() => savedMaintenance.value?.enabled ? savedMaintenance.value : savedNormal.value?.enabled ? savedNormal.value : null)
const previewVariables = computed(() => {
  if (!status.value || statusError.value) return {}
  const row = status.value
  return Object.fromEntries(['online', 'max', 'version'].filter(key =>
    (typeof row[key] === 'number' && Number.isFinite(row[key])) || (typeof row[key] === 'string' && row[key].length > 0)
  ).map(key => [key, row[key]]))
})
const allMaintenanceKeys = [...controlKeys, ...motdKeys]
const endpoint = name => `/api/ext/motd/${name}`
const message = cause => cause.status === 403 ? t('当前账号无权执行此操作。请联系管理员。') : cause.message || t('操作失败，请稍后重试')
async function confirm(text, title = t('确认操作'), danger = false) {
  try {
    await ElMessageBox.confirm(text, title, { confirmButtonText: t('确认执行'), cancelButtonText: t('取消'), type: danger ? 'warning' : 'info', closeOnClickModal: false, confirmButtonClass: danger ? 'el-button--danger' : '' })
    return true
  } catch { return false }
}
async function refreshStatus() {
  try {
    const row = checkStatus(requireSuccess(await api.get(endpoint('maintenance/status'))))
    if (disposed) return
    status.value = row
    statusError.value = ''
    updatedAt.value = new Date().toLocaleTimeString(locale.value === 'en_US' ? 'en-US' : 'zh-CN')
  } catch (cause) {
    if (!disposed) statusError.value = message(cause)
  }
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
  for (const key of isMaintenance ? ['joinMessage', 'kickMessage', 'line1', 'line2'] : motdKeys) validateText(body[key], t({ joinMessage: '入服拒绝提示', kickMessage: '踢出提示', line1: '简介第一行', line2: '简介第二行' }[key]), t)
  if (isMaintenance) body.whitelist = normalizeWhitelist(body.whitelist, t)
}
async function save(kind) {
  if (locked.value) return
  pending.value = kind
  error.value = ''
  notice.value = ''
  let submitted = false
  try {
    const isNormal = kind === 'normal'
    const keys = isNormal ? normalKeys : kind === 'control' ? controlKeys : motdKeys
    const baseline = isNormal ? savedNormal.value : savedMaintenance.value
    const body = isNormal ? pick(normal, normalKeys) : { ...savedMaintenance.value, ...pick(maintenance, keys) }
    validate(body, !isNormal)
    const current = checkSettings(requireSuccess(await api.get(endpoint(isNormal ? 'settings' : 'maintenance'))), !isNormal)
    if (!same(current, baseline, isNormal ? normalKeys : allMaintenanceKeys)) {
      uncertain.value = true
      throw new Error(t('服务器设置已被其他操作修改。请重新加载后再保存。'))
    }
    if (!isNormal) {
      const enabling = !current.enabled && body.enabled
      const kicking = body.enabled && body.kickOnline && (!current.enabled || !current.kickOnline)
      if (enabling || kicking) {
        const text = `${enabling ? t('将启用维护模式，服务器列表立即显示维护简介。') : ''}${t(body.allowJoin ? '非白名单玩家仍可加入。' : '非白名单玩家将无法加入。')}${t(kicking ? '保存后将立即向非白名单在线玩家发起一次踢出请求，使用本次保存的白名单及踢出提示。' : '本次不会自动踢出在线玩家。')}`
        if (!await confirm(text, t(kicking ? '保存并踢出在线玩家' : '启用维护模式'), kicking)) return
      }
    }
    if (!canManage.value) throw new Error(t('当前账号已无管理权限'))
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
async function kick() {
  if (locked.value || !savedMaintenance.value.enabled) return
  pending.value = 'kick'
  error.value = ''
  notice.value = ''
  let submitted = false
  try {
    const current = checkSettings(requireSuccess(await api.get(endpoint('maintenance'))), true)
    if (!same(current, savedMaintenance.value, allMaintenanceKeys)) {
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
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onBeforeUnmount(() => { disposed = true; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="maintenance-workspace" :aria-busy="busy">
    <header class="workspace-heading">
      <div><span class="eyebrow">{{ t('服务器管理') }}</span><h1><Icon name="server" :size="23" />{{ t('服务器展示与维护') }}</h1><p>{{ t('管理维护期间的加入规则、在线玩家与服务器列表简介。') }}</p></div>
      <el-button :loading="loading" :disabled="busy" @click="load"><Icon name="refresh-cw" :size="14" />{{ t('重新加载') }}</el-button>
    </header>
    <div v-if="error" class="feedback danger" role="alert">{{ error }}</div>
    <div v-if="notice" class="feedback success" role="status">{{ notice }}</div>
    <div v-if="!canManage" class="feedback" role="note">{{ t('当前账号仅可查看，无权保存设置或踢出玩家。') }}</div>
    <div v-if="uncertain" class="feedback warning" role="alert">{{ t('设置或执行结果需要核对。请先重新加载，再继续操作。') }}</div>
    <div v-if="!ready" class="loading-state" role="status">{{ t(loading ? '正在读取服务器设置…' : '设置尚未加载，请重新加载。') }}</div>
    <template v-else>
      <section class="live-state" :aria-label="t('当前生效状态')">
        <div class="live-heading"><span class="eyebrow">{{ t('当前生效 · 已保存设置') }}</span><span class="mode-badge" :class="{ maintaining: savedMaintenance.enabled }">{{ t(savedMaintenance.enabled ? '维护中' : '正常运行') }}</span></div>
        <div class="live-layout">
          <div><h2>{{ t(savedMaintenance.enabled ? (savedMaintenance.allowJoin ? '维护期间允许玩家加入' : '仅维护白名单可加入') : '服务器正常开放') }}</h2><p>{{ t(savedMaintenance.enabled ? '维护白名单不受维护入服限制与维护踢出影响。' : '维护入服限制与维护踢出均未启用。') }}</p><p>{{ t('自动踢出选项：{state} · 已保存白名单 {count} 项', { state: t(savedMaintenance.kickOnline ? '已开启' : '已关闭'), count: savedMaintenance.whitelist.split('\n').filter(Boolean).length }) }}</p></div>
          <dl class="player-counts"><div><dt>{{ t('在线玩家') }}</dt><dd>{{ status && !statusError ? status.online : '—' }}</dd></div><div><dt>{{ t('可发起维护踢出') }}</dt><dd>{{ status && !statusError ? status.eligible : '—' }}</dd></div></dl>
        </div>
        <MinecraftTextPreview :legacy="false" atomic-variables v-if="activeMotd" :lines="[activeMotd.line1, activeMotd.line2]" :variables="previewVariables" :label="t('{mode}简介 · 已保存设置预览', { mode: t(savedMaintenance.enabled ? '维护' : '正常') })" />
        <p v-else>{{ t('正常简介未启用，当前使用服务器原有简介。') }}</p>
        <p v-if="statusError" class="error-text" role="alert">{{ t('在线状态读取失败：{error}。请重新加载。', { error: statusError }) }}</p><p v-else class="timestamp">{{ t('人数更新于 {time}，执行时以实时在线玩家为准。', { time: updatedAt || '—' }) }}</p>
      </section>
      <div class="workspace-grid">
        <section class="workspace-card controls">
          <header class="card-heading"><div><span class="eyebrow">{{ t('维护控制') }}</span><h2>{{ t('维护与加入规则') }}</h2></div><span class="save-badge" :class="{ unsaved: controlDirty }">{{ t(controlDirty ? '未保存' : '已保存') }}</span></header>
          <fieldset :disabled="locked">
            <label class="switch-row"><span><strong>{{ t('启用维护模式') }}</strong><small>{{ t('保存后立即切换服务器列表简介与加入规则。') }}</small></span><input v-model="maintenance.enabled" type="checkbox" role="switch" /></label>
            <label class="switch-row"><span><strong>{{ t('允许非白名单玩家加入') }}</strong><small>{{ t('与踢出在线玩家独立控制，仅在维护期间生效。') }}</small></span><input v-model="maintenance.allowJoin" type="checkbox" role="switch" /></label>
            <label class="switch-row"><span><strong>{{ t('自动踢出非白名单在线玩家') }}</strong><small>{{ t('启用维护或维护中首次开启此选项时，执行一次踢出。') }}</small></span><input v-model="maintenance.kickOnline" type="checkbox" role="switch" /></label>
            <label class="field-label" for="maintenance-whitelist">{{ t('维护白名单') }} <span>{{ t('{count} / 500 项', { count: whitelistCount }) }}</span></label>
            <textarea id="maintenance-whitelist" v-model="maintenance.whitelist" rows="7" maxlength="20000" :placeholder="t('每行一个玩家名称或 UUID')" spellcheck="false" />
            <p class="field-help">{{ t('名称支持 3–16 位英文字母、数字和下划线，忽略大小写。支持完整 UUID；白名单玩家不会被维护踢出。') }}</p>
            <label class="field-label" for="maintenance-join-message">{{ t('入服拒绝提示') }}</label><input id="maintenance-join-message" v-model="maintenance.joinMessage" type="text" maxlength="512" />
            <MinecraftTextPreview :legacy="false" atomic-variables :text="maintenance.joinMessage" :variables="previewVariables" :label="t('入服拒绝提示 · 草稿预览')" />
            <label class="field-label" for="maintenance-kick-message">{{ t('踢出提示') }}</label><input id="maintenance-kick-message" v-model="maintenance.kickMessage" type="text" maxlength="512" />
            <MinecraftTextPreview :legacy="false" atomic-variables :text="maintenance.kickMessage" :variables="previewVariables" :label="t('踢出提示 · 草稿预览')" />
          </fieldset>
          <footer class="card-actions"><span>{{ t('仅保存维护控制与提示') }}</span><el-button type="primary" :loading="pending === 'control'" :disabled="locked || !controlDirty" @click="save('control')">{{ t('保存维护设置') }}</el-button></footer>
          <div class="manual-action"><div><h3>{{ t('立即踢出非白名单在线玩家') }}</h3><p>{{ t('使用已保存白名单及踢出提示，执行前需确认。') }}</p><p v-if="!savedMaintenance.enabled">{{ t('请先保存并启用维护模式。') }}</p><p v-else-if="dirty" class="warning-text">{{ t('此次操作不会使用未保存修改。') }}</p></div><el-button type="danger" plain :loading="pending === 'kick'" :disabled="locked || !savedMaintenance.enabled" @click="kick">{{ t('立即踢出') }}</el-button></div>
        </section>
        <div class="motd-stack">
          <section class="workspace-card">
            <header class="card-heading"><div><span class="eyebrow">{{ t('正常运行时显示') }}</span><h2>{{ t('正常 MOTD') }}</h2></div><span class="save-badge" :class="{ unsaved: normalDirty }">{{ t(normalDirty ? '未保存' : '已保存') }}</span></header>
            <fieldset :disabled="locked"><label class="switch-row"><span><strong>{{ t('启用正常简介') }}</strong><small>{{ t('关闭后使用服务器原有简介。') }}</small></span><input v-model="normal.enabled" type="checkbox" role="switch" /></label><label class="field-label" for="normal-line1">{{ t('第一行') }}</label><input id="normal-line1" v-model="normal.line1" maxlength="512" type="text" /><label class="field-label" for="normal-line2">{{ t('第二行') }}</label><input id="normal-line2" v-model="normal.line2" maxlength="512" type="text" /></fieldset>
            <p class="field-help">{{ t('支持 MiniMessage 样式与 {online}、{max}、{version} 变量，每行最多 512 字符。') }}</p>
            <MinecraftTextPreview :legacy="false" atomic-variables :lines="[normal.line1, normal.line2]" :variables="previewVariables" :label="t('正常 MOTD · 双行草稿预览')" />
            <footer class="card-actions"><span>{{ t(savedMaintenance.enabled ? '保存后将在退出维护时显示' : '保存后即时生效') }}</span><el-button type="primary" :loading="pending === 'normal'" :disabled="locked || !normalDirty" @click="save('normal')">{{ t('保存正常 MOTD') }}</el-button></footer>
          </section>
          <section class="workspace-card">
            <header class="card-heading"><div><span class="eyebrow">{{ t('维护期间始终显示') }}</span><h2>{{ t('维护 MOTD') }}</h2></div><span class="save-badge" :class="{ unsaved: motdDirty }">{{ t(motdDirty ? '未保存' : '已保存') }}</span></header>
            <fieldset :disabled="locked"><label class="field-label" for="maintenance-line1">{{ t('第一行') }}</label><input id="maintenance-line1" v-model="maintenance.line1" maxlength="512" type="text" /><label class="field-label" for="maintenance-line2">{{ t('第二行') }}</label><input id="maintenance-line2" v-model="maintenance.line2" maxlength="512" type="text" /></fieldset>
            <p class="field-help">{{ t('支持 MiniMessage 样式与 {online}、{max}、{version} 变量，每行最多 512 字符。') }}</p>
            <MinecraftTextPreview :legacy="false" atomic-variables :lines="[maintenance.line1, maintenance.line2]" :variables="previewVariables" :label="t('维护 MOTD · 双行草稿预览')" />
            <footer class="card-actions"><span>{{ t('独立保存，不应用维护控制草稿') }}</span><el-button type="primary" :loading="pending === 'motd'" :disabled="locked || !motdDirty" @click="save('motd')">{{ t('保存维护 MOTD') }}</el-button></footer>
          </section>
          <div v-if="dirty" class="draft-note" role="status"><Icon name="circle-alert" :size="16" />{{ t('存在未保存修改；当前生效状态仍以已保存设置为准。') }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.maintenance-workspace { max-width:1400px; margin:0 auto; color:var(--lc-text); }
.workspace-heading,.card-heading,.live-heading,.card-actions,.manual-action { display:flex; align-items:center; justify-content:space-between; gap:16px; }
.workspace-heading { align-items:flex-start; margin-bottom:22px; }.workspace-heading h1 { display:flex; align-items:center; gap:10px; font-size:24px; margin:7px 0; }.workspace-heading p,.live-state p { margin:6px 0 0; color:var(--lc-text-soft); font-size:13px; line-height:1.7; }.workspace-heading :deep(.el-button) { gap:7px; }
.eyebrow { font-size:11px; color:var(--el-color-primary); font-weight:650; letter-spacing:.04em; }
h2 { font-size:18px; line-height:1.5; margin:4px 0; }h3 { font-size:13px; margin:0 0 6px; }
.live-state,.workspace-card { border:1px solid var(--el-border-color-light); border-radius:12px; background:var(--lc-surface); padding:24px; min-width:0; }.live-state { border-top:3px solid var(--el-color-primary); margin-bottom:22px; }.live-layout { display:flex; align-items:center; justify-content:space-between; gap:20px; margin:13px 0 18px; }
.mode-badge,.save-badge { font-size:11px; border-radius:20px; padding:5px 10px; background:var(--el-fill-color-light); color:var(--lc-text-soft); white-space:nowrap; }.mode-badge { color:var(--el-color-success); background:var(--el-color-success-light-9); }.mode-badge.maintaining,.save-badge.unsaved { color:var(--el-color-warning); background:var(--el-color-warning-light-9); }
.player-counts { display:flex; gap:28px; margin:0; flex-shrink:0; }.player-counts dt { color:var(--lc-text-soft); font-size:12px; }.player-counts dd { font-size:28px; font-weight:650; margin:6px 0 0; font-variant-numeric:tabular-nums; }.active-motd { border:1px solid var(--el-border-color-lighter); border-radius:8px; padding:14px 16px; background:var(--el-fill-color-light); overflow-wrap:anywhere; font-family:monospace; line-height:1.7; font-size:13px; }.active-motd span { display:block; color:var(--lc-text-soft); font-size:11px; margin-bottom:7px; font-family:inherit; }.live-state .timestamp { font-size:11px; margin-top:10px; }
.workspace-grid { display:grid; grid-template-columns:minmax(0,1.05fr) minmax(0,1fr); gap:22px; align-items:start; }.motd-stack { display:grid; gap:22px; min-width:0; }.card-heading { margin-bottom:18px; }.card-heading h2 { margin-bottom:0; }
fieldset { border:0; margin:0; padding:0; min-width:0; }fieldset:disabled { opacity:.65; }.switch-row { display:flex; align-items:center; justify-content:space-between; gap:18px; padding:0 0 16px; margin-bottom:16px; border-bottom:1px solid var(--el-border-color-lighter); cursor:pointer; }.switch-row strong { display:block; font-size:13px; font-weight:600; }.switch-row small { display:block; color:var(--lc-text-soft); font-size:12px; line-height:1.65; margin-top:5px; }.switch-row input { accent-color:var(--el-color-primary); width:20px; height:20px; flex-shrink:0; cursor:pointer; }
.field-label { display:flex; justify-content:space-between; gap:10px; font-size:12px; margin:17px 0 8px; font-weight:600; }.field-label span { font-weight:400; color:var(--lc-text-soft); }input[type=text],textarea { box-sizing:border-box; display:block; width:100%; min-width:0; border:1px solid var(--el-border-color); border-radius:7px; background:var(--el-fill-color-blank); color:var(--lc-text); font:inherit; font-size:13px; line-height:1.65; padding:9px 11px; }textarea { resize:vertical; min-height:120px; }input:focus-visible,textarea:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }input:disabled,textarea:disabled { cursor:not-allowed; }
.field-help,.manual-action p { font-size:12px; line-height:1.7; color:var(--lc-text-soft); margin:8px 0 0; }.card-actions { border-top:1px solid var(--el-border-color-lighter); padding-top:17px; margin-top:20px; }.card-actions > span { font-size:11px; color:var(--lc-text-soft); line-height:1.6; }.manual-action { align-items:flex-start; border-top:1px solid var(--el-border-color-light); padding-top:20px; margin-top:24px; }.manual-action :deep(.el-button) { flex-shrink:0; }.manual-action .warning-text { color:var(--el-color-warning); }
.feedback,.draft-note,.loading-state { font-size:13px; line-height:1.7; border:1px solid var(--el-border-color-light); border-radius:8px; padding:12px 16px; margin-bottom:16px; overflow-wrap:anywhere; }.feedback.danger,.error-text { color:var(--el-color-danger) !important; }.feedback.success { color:var(--el-color-success); }.feedback.warning,.draft-note { color:var(--el-color-warning); }.draft-note { display:flex; gap:8px; align-items:flex-start; margin:0; }.draft-note :deep(svg) { flex-shrink:0; margin-top:3px; }.loading-state { padding:48px 16px; text-align:center; color:var(--lc-text-soft); }
@media (max-width:1000px) { .workspace-grid { grid-template-columns:minmax(0,1fr); }.motd-stack { grid-template-columns:repeat(2,minmax(0,1fr)); }.draft-note { grid-column:1/-1; }.card-actions { flex-wrap:wrap; } }
@media (max-width:650px) { .workspace-heading { flex-wrap:wrap; }.workspace-heading h1 { font-size:21px; }.live-state,.workspace-card { padding:18px; }.live-layout { flex-direction:column; align-items:flex-start; }.player-counts { gap:32px; }.player-counts dd { font-size:24px; }.motd-stack { grid-template-columns:minmax(0,1fr); }.manual-action { flex-direction:column; }.card-actions { align-items:flex-start; }.card-actions > span { flex-basis:100%; }.workspace-grid,.motd-stack { gap:16px; }.mode-badge,.save-badge { padding:4px 8px; } }
</style>
