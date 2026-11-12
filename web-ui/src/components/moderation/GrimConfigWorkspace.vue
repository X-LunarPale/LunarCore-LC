<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { useAuth } from '../../stores/auth'
import { requireSuccess } from '../../utils/panel'
import { copyConfigValue, grimChanges, grimFieldError, grimFieldGroups, parseGrimSnapshot } from '../../utils/grim-config'
import GrimConfigField from './GrimConfigField.vue'
import { t } from '../../i18n'

const props = defineProps({ active: { type: Boolean, default: true } })
const auth = useAuth()
const files = ref([])
const snapshot = ref(null)
const selectedId = ref('')
const draft = ref({})
const original = ref({})
const touchedSecrets = ref({})
const search = ref('')
const group = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const conflict = ref(false)
const uncertain = ref(false)
const reloadSubmitting = ref(false)
const reloadPending = ref(false)
const reloadStatus = ref('')
const reloadError = ref('')
const savedFiles = ref([])
const restartFiles = ref([])
let disposed = false
let generation = 0
let reloadGeneration = 0
let reloadReads = 0
let reloadReading = false
let reloadTimer
let discardPrompt
let initialized = false

const canManage = computed(() => auth.can('anticheat.manage'))
const fields = computed(() => snapshot.value?.fields || [])
const changes = computed(() => grimChanges(fields.value, draft.value, original.value, touchedSecrets.value))
const changedCount = computed(() => Object.keys(changes.value).length)
const dirty = computed(() => changedCount.value > 0)
const busy = computed(() => loading.value || saving.value || reloadSubmitting.value || reloadPending.value)
const disabled = computed(() => !canManage.value || busy.value || conflict.value || uncertain.value)
const groups = computed(() => [...new Set(fields.value.map(field => field.group || '其他设置'))])
const sections = computed(() => grimFieldGroups(fields.value, search.value, group.value))
const shownCount = computed(() => sections.value.reduce((count, section) => count + section.fields.length, 0))
const errors = computed(() => fields.value.filter(field => Object.hasOwn(changes.value, field.key))
  .map(field => grimFieldError(field, draft.value[field.key])).filter(Boolean))
const needsReload = computed(() => savedFiles.value.some(id => !restartFiles.value.includes(id)))

function clearReloadTimer() {
  clearTimeout(reloadTimer)
  reloadTimer = undefined
}
function scheduleReloadStatus() {
  clearReloadTimer()
  if (!disposed && canManage.value && props.active && reloadPending.value && !document.hidden && reloadReads < 30) {
    reloadTimer = setTimeout(readReloadStatus, 1500)
  }
}
async function readReloadStatus() {
  if (disposed || !canManage.value || !props.active || document.hidden || reloadReading || !reloadPending.value) return
  const current = reloadGeneration
  reloadReading = true
  try {
    const health = requireSuccess(await api.get('/api/ext/anticheat/health'))
    if (disposed || current !== reloadGeneration || !canManage.value) return
    reloadReads++
    reloadStatus.value = health.reloadStatus || ''
    reloadError.value = ''
    if (health.reloadStatus === '重载成功') {
      reloadPending.value = false
      savedFiles.value = savedFiles.value.filter(id => restartFiles.value.includes(id))
    } else if (health.reloadStatus === '重载失败' || health.connected === false) {
      reloadPending.value = false
      reloadError.value = t(health.reloadStatus === '重载失败' ? 'GrimAC 重载失败，配置文件已保留，请检查服务器日志。' : 'GrimAC 连接已断开，请检查插件状态。')
    } else if (reloadReads >= 30) {
      reloadPending.value = false
      reloadError.value = t('重载结果尚未确认，请查看运行状态后再操作。')
    }
  } catch (cause) {
    if (!disposed && current === reloadGeneration) {
      reloadPending.value = false
      reloadError.value = cause.message
    }
  } finally {
    reloadReading = false
    if (!disposed && current === reloadGeneration) scheduleReloadStatus()
  }
}
async function confirmDiscard() {
  if (saving.value || reloadSubmitting.value) {
    ElMessage.warning(t('正在保存，请等待保存完成'))
    return false
  }
  if (!dirty.value) return true
  if (!discardPrompt) {
    discardPrompt = ElMessageBox.confirm(t('有尚未保存的修改，确定放弃这些修改吗？'), t('未保存的修改'), {
      type: 'warning', confirmButtonText: t('放弃修改'), cancelButtonText: t('继续编辑'), distinguishCancelAndClose: true
    }).then(() => true, () => false).finally(() => { discardPrompt = null })
  }
  return discardPrompt
}
function applySnapshot(data, id) {
  const parsed = parseGrimSnapshot(data, id)
  snapshot.value = parsed
  selectedId.value = id
  original.value = Object.fromEntries(parsed.fields.map(field => [field.key, copyConfigValue(parsed.values[field.key])]))
  draft.value = Object.fromEntries(parsed.fields.map(field => [field.key, copyConfigValue(parsed.values[field.key])]))
  touchedSecrets.value = {}
  error.value = ''
  conflict.value = false
  uncertain.value = false
}
async function loadDirectory() {
  if (disposed || !canManage.value || loading.value || initialized) return
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = requireSuccess(await api.get('/api/ext/anticheat/grim/config'))
    if (disposed || current !== generation || !canManage.value) return
    if (!Array.isArray(result.files)) throw new Error(t('GrimAC 配置响应不完整，请重新读取。'))
    files.value = result.files
    if (result.selected) applySnapshot(result.selected, result.selected.id)
    initialized = true
  } catch (cause) {
    if (!disposed && current === generation) error.value = t(cause.message)
  } finally {
    if (!disposed && current === generation) loading.value = false
  }
}
async function loadFile(id = selectedId.value) {
  if (disposed || !canManage.value || busy.value || !id || !await confirmDiscard()) return
  if (disposed || !canManage.value || busy.value) return
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = requireSuccess(await api.get(`/api/ext/anticheat/grim/config/${encodeURIComponent(id)}`))
    if (disposed || current !== generation || !canManage.value) return
    applySnapshot(data, id)
    group.value = ''
    search.value = ''
  } catch (cause) {
    if (!disposed && current === generation) error.value = t(cause.message)
  } finally {
    if (!disposed && current === generation) loading.value = false
  }
}
function chooseFile(file) {
  if (file.id !== selectedId.value && file.available !== false) loadFile(file.id)
}
function reloadFile() {
  if (!initialized || !selectedId.value) {
    initialized = false
    loadDirectory()
  } else loadFile()
}
function updateField(field, value) {
  if (disabled.value || field.readOnly || field.secret && !touchedSecrets.value[field.key]) return
  draft.value[field.key] = copyConfigValue(value)
}
function editSecret(field) {
  if (disabled.value || field.readOnly) return
  touchedSecrets.value = { ...touchedSecrets.value, [field.key]: true }
  draft.value[field.key] = ''
}
function resetSecret(field) {
  if (disabled.value) return
  const next = { ...touchedSecrets.value }
  delete next[field.key]
  touchedSecrets.value = next
  draft.value[field.key] = ''
}
async function save() {
  if (disabled.value || !snapshot.value || !dirty.value || errors.value.length) return
  const id = selectedId.value
  const current = generation
  const patch = changes.value
  saving.value = true
  error.value = ''
  try {
    const result = requireSuccess(await api.post(`/api/ext/anticheat/grim/config/${encodeURIComponent(id)}`, {
      fingerprint: snapshot.value.fingerprint, changes: patch
    }), t('保存失败'))
    if (disposed || current !== generation || !canManage.value) return
    if (!result.snapshot) throw new Error(t('保存结果未确认，请重新读取后核对。'))
    applySnapshot(result.snapshot, id)
    savedFiles.value = [...new Set([...savedFiles.value, id])]
    if (result.snapshot.restartRequired) restartFiles.value = [...new Set([...restartFiles.value, id])]
    ElMessage.success(t(result.message || 'GrimAC 配置已保存'))
  } catch (cause) {
    if (disposed || current !== generation) return
    error.value = t(cause.message)
    conflict.value = cause.status === 409
    uncertain.value = !cause.status || cause.status >= 500
  } finally {
    if (!disposed && current === generation) saving.value = false
  }
}
async function reloadGrim() {
  if (!canManage.value || busy.value || dirty.value || conflict.value || uncertain.value) return
  const confirmed = await ElMessageBox.confirm(t('重载 GrimAC 将应用已保存的配置。数据库重载期间历史记录写入可能短暂中断。'), t('重载 GrimAC'), {
    type: 'warning', confirmButtonText: t('重载 GrimAC'), cancelButtonText: t('取消')
  }).then(() => true, () => false)
  if (!confirmed || disposed || !canManage.value || busy.value || dirty.value) return
  reloadSubmitting.value = true
  reloadError.value = ''
  const current = ++reloadGeneration
  try {
    requireSuccess(await api.post('/api/ext/anticheat/grim/reload'), t('GrimAC 重载请求失败'))
    if (disposed || current !== reloadGeneration || !canManage.value) return
    reloadPending.value = true
    reloadStatus.value = '正在重载'
    reloadReads = 0
    scheduleReloadStatus()
  } catch (cause) {
    if (!disposed && current === reloadGeneration) reloadError.value = t(cause.message)
  } finally {
    if (!disposed && current === reloadGeneration) reloadSubmitting.value = false
  }
}
function beforeUnload(event) {
  if (!dirty.value && !saving.value && !reloadSubmitting.value) return
  event.preventDefault()
  event.returnValue = ''
}
watch(() => props.active, active => {
  if (active && !initialized) loadDirectory()
  scheduleReloadStatus()
}, { immediate: true })
watch(canManage, allowed => {
  if (allowed) {
    if (props.active) loadDirectory()
    return
  }
  generation++
  reloadGeneration++
  clearReloadTimer()
  files.value = []
  snapshot.value = null
  draft.value = {}
  original.value = {}
  touchedSecrets.value = {}
  selectedId.value = ''
  loading.value = false
  saving.value = false
  reloadPending.value = false
  reloadSubmitting.value = false
  initialized = false
})
onBeforeRouteLeave(confirmDiscard)
onBeforeRouteUpdate((to, from) => to.fullPath === from.fullPath || confirmDiscard())
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  document.addEventListener('visibilitychange', scheduleReloadStatus)
})
onBeforeUnmount(() => {
  disposed = true
  generation++
  reloadGeneration++
  clearReloadTimer()
  window.removeEventListener('beforeunload', beforeUnload)
  document.removeEventListener('visibilitychange', scheduleReloadStatus)
})
</script>

<template>
  <div class="grim-config-workspace">
    <div class="config-intro"><div><h2>{{ t('GrimAC 配置') }}</h2><p>{{ t('反作弊检测、处罚规则与消息设置') }}</p></div><span v-if="dirty" class="unsaved" role="status">{{ t('{count} 项未保存', { count: changedCount }) }}</span></div>
    <el-alert v-if="!canManage" :title="t('当前账号没有管理 GrimAC 配置的权限')" type="info" :closable="false" />
    <template v-else>
      <div class="config-layout">
        <aside class="config-files" :aria-label="t('GrimAC 配置文件')">
          <button v-for="file in files" :key="file.id" type="button" :data-config-file="file.id" :class="{ selected: selectedId === file.id }" :aria-pressed="selectedId === file.id" :disabled="busy || file.available === false" @click="chooseFile(file)"><Icon name="file-cog" :size="16" /><span><strong>{{ t(file.label) }}</strong><small>{{ file.name }}</small></span><span v-if="savedFiles.includes(file.id)" class="file-pending" :title="t('配置已保存，尚未应用')" /></button>
        </aside>
        <div class="config-main" v-loading="loading" :aria-busy="busy">
          <el-alert v-if="error" class="config-alert" type="error" :title="t(conflict ? '配置文件已被修改，请重新读取' : uncertain ? '保存结果需要核对' : 'GrimAC 配置操作失败')" :description="error + (conflict || uncertain ? t('当前草稿已保留，重新读取前请核对修改。') : '')" :closable="false" show-icon />
          <el-alert v-if="reloadError" class="config-alert" type="error" :title="t('GrimAC 重载未完成')" :description="reloadError" :closable="false" show-icon />
          <div v-if="savedFiles.length || reloadStatus" class="apply-status" role="status"><Icon :name="reloadPending ? 'hourglass' : needsReload || restartFiles.length ? 'circle-alert' : 'circle-check'" :size="16" /><span>{{ reloadPending ? t('正在重载 GrimAC，请等待结果…') : needsReload ? t('配置已保存，重载 GrimAC 后生效。') : restartFiles.length ? t('部分配置已保存，需重启服务器后生效。') : reloadStatus ? t(reloadStatus) : '' }}</span><span v-if="needsReload && restartFiles.length" class="secondary-status">{{ t('另有设置需要重启服务器。') }}</span></div>
          <template v-if="snapshot">
            <el-alert v-if="snapshot.notice" class="config-alert" type="info" :description="t(snapshot.notice)" :closable="false" show-icon />
            <header class="file-heading"><div><h3>{{ t(snapshot.label) }}</h3><span>{{ snapshot.name }}</span></div><el-tooltip :content="t('重新读取配置文件')"><el-button class="icon-button" :disabled="busy" :aria-label="t('重新读取')" @click="reloadFile"><Icon name="rotate-ccw" :size="16" /></el-button></el-tooltip></header>
            <div class="config-toolbar"><el-input v-model="search" clearable :placeholder="t('搜索中文名称或配置键')" :aria-label="t('搜索 GrimAC 配置')"><template #prefix><Icon name="search" :size="14" /></template></el-input><el-select v-model="group" clearable :placeholder="t('全部分组')" :aria-label="t('GrimAC 配置分组')"><el-option v-for="name in groups" :key="name" :label="t(name)" :value="name" /></el-select><span class="result-count">{{ t('{shown} / {total} 项', { shown: shownCount, total: fields.length }) }}</span></div>
            <section v-for="section in sections" :key="section.name" class="config-group"><h4>{{ t(section.name) }}</h4><div class="config-fields"><GrimConfigField v-for="field in section.fields" :key="`${selectedId}-${field.key}`" :field="field" :model-value="draft[field.key]" :disabled="disabled" :secret-edited="!!touchedSecrets[field.key]" @update:model-value="updateField(field, $event)" @edit-secret="editSecret(field)" @reset-secret="resetSecret(field)" /></div></section>
            <el-empty v-if="!shownCount" :description="t('没有匹配的设置')" />
            <footer class="config-actions"><div><span v-if="errors.length" class="validation-error">{{ t('请修正 {count} 项无效配置。', { count: errors.length }) }}</span><span v-else>{{ t(snapshot.restartRequired ? '本文件的部分设置需要重启服务器。' : '保存配置后需重载 GrimAC；部分设置或旧版本可能需重启。') }}</span></div><div class="action-buttons"><el-button :loading="reloadSubmitting || reloadPending" :disabled="busy || dirty || conflict || uncertain" @click="reloadGrim"><Icon name="refresh-cw" :size="14" />{{ t('重载 GrimAC') }}</el-button><el-button type="primary" :loading="saving" :disabled="disabled || !dirty || !!errors.length" @click="save"><Icon name="save" :size="14" />{{ t('保存配置') }}</el-button></div></footer>
          </template>
          <div v-else class="config-empty"><Icon name="file-cog" :size="25" /><p>{{ t(loading ? '正在读取 GrimAC 配置…' : '暂无可读取的 GrimAC 配置') }}</p><el-button v-if="!loading" @click="reloadFile">{{ t('重新读取') }}</el-button></div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.grim-config-workspace { min-width: 0; }.config-intro { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }h2 { margin: 0 0 5px; font-size: 18px; line-height: 1.4; }.config-intro p { margin: 0; font-size: 13px; line-height: 1.7; color: var(--lc-text-soft); }.unsaved { color: var(--el-color-warning); font-size: 12px; white-space: nowrap; }
.config-layout { display: grid; grid-template-columns: 202px minmax(0, 1fr); gap: 25px; align-items: start; }.config-files { display: grid; gap: 4px; position: sticky; top: 12px; }.config-files button { display: flex; align-items: center; gap: 10px; min-width: 0; width: 100%; border: 0; border-left: 2px solid transparent; border-radius: 5px; padding: 12px 10px; text-align: left; background: transparent; color: var(--lc-text); cursor: pointer; transition: background-color 150ms, color 150ms; }.config-files button:disabled { opacity: .55; cursor: default; }.config-files button.selected { border-left-color: var(--el-color-primary); background: var(--el-color-primary-light-9); color: var(--el-color-primary); }.config-files button:hover:not(:disabled) { background: var(--el-fill-color-light); }.config-files button.selected:hover { background: var(--el-color-primary-light-9); }.config-files button > :deep(svg) { flex-shrink: 0; }.config-files button > span { min-width: 0; }.config-files strong { display: block; font-size: 13px; line-height: 1.5; font-weight: 600; overflow-wrap: anywhere; }.config-files small { display: block; margin-top: 4px; color: var(--lc-text-soft); font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }.file-pending { width: 6px; height: 6px; border-radius: 50%; margin-left: auto; background: var(--el-color-warning); flex-shrink: 0; }
.config-main { min-width: 0; }.config-alert { margin-bottom: 14px; }.file-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 17px; border-bottom: 1px solid var(--el-border-color); }h3 { margin: 0 0 4px; font-size: 16px; line-height: 1.5; }.file-heading span { display: block; color: var(--lc-text-soft); font-size: 11px; overflow-wrap: anywhere; }.icon-button { width: 34px; height: 34px; flex-shrink: 0; padding: 0; }
.config-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding-block: 17px; }.config-toolbar > :deep(.el-input) { flex: 1; min-width: 180px; }.config-toolbar > :deep(.el-select) { width: 170px; }.result-count { color: var(--lc-text-soft); font-size: 11px; white-space: nowrap; }
.config-group { margin-bottom: 25px; border-top: 1px solid var(--el-border-color-lighter); padding-top: 20px; }h4 { margin: 0 0 18px; font-size: 14px; line-height: 1.6; font-weight: 650; }.config-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px 28px; }
.config-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 15px; padding: 16px 0 10px; position: sticky; bottom: 0; z-index: 3; border-top: 1px solid var(--el-border-color); background: var(--lc-surface); }.config-actions > div:first-child { flex: 1; min-width: 180px; font-size: 12px; line-height: 1.7; color: var(--lc-text-soft); }.action-buttons { display: flex; flex-wrap: wrap; gap: 8px; }.validation-error { color: var(--el-color-danger); }.apply-status { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 11px 0 14px; color: var(--el-color-warning); font-size: 12px; line-height: 1.7; }.apply-status > :deep(svg) { flex-shrink: 0; }.secondary-status { color: var(--lc-text-soft); }
.config-empty { display: grid; justify-items: center; align-content: center; gap: 10px; min-height: 240px; color: var(--lc-text-soft); font-size: 13px; }.config-empty p { margin: 0; line-height: 1.7; }
:deep(.el-button) { gap: 6px; margin: 0; border-radius: 5px; }.config-files button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
@media (max-width: 1000px) { .config-layout { grid-template-columns: 170px minmax(0, 1fr); gap: 18px; }.config-fields { grid-template-columns: minmax(0, 1fr); }.config-toolbar > :deep(.el-select) { flex: 1; min-width: 140px; } }
@media (max-width: 700px) { .config-layout { grid-template-columns: minmax(0, 1fr); gap: 18px; }.config-files { position: static; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 8px; padding-bottom: 12px; border-bottom: 1px solid var(--el-border-color); }.config-files button { padding: 10px 8px; }.config-toolbar > :deep(.el-input) { width: 100%; flex-basis: 100%; }.config-toolbar > :deep(.el-select) { flex: 1; }.config-actions { gap: 10px; }.config-actions > div:first-child { flex-basis: 100%; }.action-buttons { width: 100%; }.action-buttons > :deep(.el-button) { flex: 1; min-width: 0; } }
@media (prefers-reduced-motion: reduce) { .config-files button { transition: none; } }
</style>
