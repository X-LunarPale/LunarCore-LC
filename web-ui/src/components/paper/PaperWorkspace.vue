<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { useAuth } from '../../stores/auth'
import PaperField from './PaperField.vue'
import { SCOPES, fieldError, makePatch } from './paper'
import { t } from '../../i18n'

const emit = defineEmits(['dirty-change', 'busy-change'])
const auth = useAuth()
const scope = ref('global')
const search = ref('')
const group = ref('')
const changedOnly = ref(false)
const states = reactive(Object.fromEntries(SCOPES.map(item => [item.id, { loaded: false, loading: false, saving: false, error: '', conflict: false, uncertain: false, data: null, values: {}, original: {}, saved: false }])))
let active = true
const current = computed(() => states[scope.value])
const canEdit = computed(() => auth.can('core.config.edit') && current.value.data?.readOnly !== true && current.value.data?.readonly !== true && current.value.data?.editable !== false)
const fields = computed(() => current.value.data?.fields || [])
const patchFor = state => makePatch(state.data?.fields || [], state.values, state.original)
const changedCount = computed(() => Object.keys(patchFor(current.value)).length)
const dirty = computed(() => Object.values(states).some(state => Object.keys(patchFor(state)).length))
const busy = computed(() => Object.values(states).some(state => state.saving))
const groups = computed(() => [...new Set(fields.value.map(field => field.group || '其他设置'))])
const filtered = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return fields.value.filter(field => (!group.value || (field.group || '其他设置') === group.value)
    && (!changedOnly.value || !Object.is(current.value.values[field.key], current.value.original[field.key]))
    && (!query || [field.label, field.name, field.help, field.description, field.key, field.group].join(' ').toLocaleLowerCase().includes(query)))
})
const sections = computed(() => groups.value.map(name => ({ name, fields: filtered.value.filter(field => (field.group || '其他设置') === name) })).filter(section => section.fields.length))
const errors = computed(() => fields.value.filter(field => Object.hasOwn(patchFor(current.value), field.key)).map(field => fieldError(field, current.value.values[field.key])).filter(Boolean))
watch(dirty, value => emit('dirty-change', value), { immediate: true })
watch(busy, value => emit('busy-change', value), { immediate: true })
watch(scope, () => { group.value = ''; if (!current.value.loaded) load() })

function applyData(state, data, id) {
  if (!data || data.scope !== id || !Array.isArray(data.fields) || !data.values || !(data.revision || data.fingerprint)) throw new Error(t('服务器返回的 Paper 配置格式不完整，请重新加载。'))
  state.data = data
  // Only schema-listed values are retained. Unknown configuration values are never rendered.
  state.values = Object.fromEntries(data.fields.map(field => [field.key, data.values[field.key]]))
  state.original = { ...state.values }
  state.loaded = true
  state.conflict = false
  state.uncertain = false
  state.error = ''
}
async function load() {
  const id = scope.value
  const state = states[id]
  if (state.loading || state.saving) return
  if (Object.keys(patchFor(state)).length) {
    const discard = await ElMessageBox.confirm(t('重新加载将丢弃此作用域的未保存修改，并读取服务器最新配置。是否继续？'), t('重新加载配置'), { type: 'warning', confirmButtonText: t('重新加载'), cancelButtonText: t('保留修改') }).then(() => true).catch(() => false)
    if (!discard || !active) return
  }
  state.loading = true
  try {
    const data = await api.get(`/api/config/paper/${id}`)
    if (active) { applyData(state, data, id); state.saved = false }
  } catch (error) { if (active) state.error = error.message }
  finally { if (active) state.loading = false }
}
async function save() {
  const id = scope.value
  const state = states[id]
  if (!canEdit.value || state.saving || state.loading || !state.loaded || state.conflict || state.uncertain || errors.value.length) return
  const patch = patchFor(state)
  if (!Object.keys(patch).length) return
  state.saving = true
  const confirmed = await ElMessageBox.confirm(t('将保存 {count} 项{scope}修改。服务器会自动备份原配置，保存后需重启服务器生效。', { count: Object.keys(patch).length, scope: t(SCOPES.find(item => item.id === id).label) }), t('保存 Paper 配置'), { type: 'warning', confirmButtonText: t('保存配置'), cancelButtonText: t('取消') }).then(() => true).catch(() => false)
  if (!confirmed || !active) { state.saving = false; return }
  try {
    const result = await api.post(`/api/config/paper/${id}`, { fingerprint: state.data.fingerprint, patch })
    if (!active) return
    if (result?.ok !== true || !(result.fingerprint || result.revision)) throw new Error(t('保存结果未确认，请重新加载后核对。'))
    state.original = { ...state.original, ...patch }
    state.data = { ...state.data, fingerprint: result.fingerprint, revision: result.revision }
    state.saved = true
    state.error = ''
  } catch (error) {
    if (!active) return
    state.error = error.message
    state.conflict = error.status === 409
    state.uncertain = !error.status || error.status >= 500
  } finally { if (active) state.saving = false }
}
function updateField(field, value) {
  if (!canEdit.value || current.value.saving || current.value.loading || current.value.conflict || current.value.uncertain || field.editable === false || field.present === false) return
  current.value.values[field.key] = value
  current.value.saved = false
}
onMounted(load)
onBeforeUnmount(() => { active = false })
</script>

<template>
  <div class="paper-workspace">
    <div class="scope-selector workspace-tabs" role="group" :aria-label="t('配置作用域')"><button v-for="item in SCOPES" :key="item.id" type="button" :class="{ selected: scope === item.id }" :aria-pressed="scope === item.id" :title="t(item.description)" @click="scope = item.id">{{ t(item.label) }}<span v-if="Object.keys(patchFor(states[item.id])).length" class="scope-dot" :aria-label="t('有未保存修改')" /></button></div>
    <div v-if="current.loaded" class="paper-state">
      <span v-if="current.data.configVersion != null">{{ t('配置版本 {version}', { version: current.data.configVersion }) }}</span>
      <span v-if="!canEdit">{{ t('只读') }}</span>
      <span v-else-if="!current.saved"><Icon name="rotate-cw" :size="13" />{{ t('保存后需重启服务器生效') }}</span>
    </div>
    <el-alert v-if="current.error" class="paper-alert" type="error" :title="t(current.conflict ? '配置已发生变化，已停止保存' : current.uncertain ? '保存结果需要核对' : 'Paper 配置操作失败')" :description="current.error + (current.conflict || current.uncertain ? t(' 当前修改已保留，请重新加载最新配置，核对后重新编辑。') : '')" show-icon :closable="false" />
    <el-alert v-if="current.saved" class="paper-alert" type="success" :title="t('配置已保存，等待服务器重启后生效')" show-icon :closable="false" />
    <div class="paper-toolbar"><el-input v-model="search" :placeholder="t('搜索名称、说明或配置键')" clearable :aria-label="t('搜索 Paper 配置')" /><el-select v-model="group" clearable :placeholder="t('全部分组')" :aria-label="t('筛选配置分组')"><el-option v-for="name in groups" :key="name" :label="t(name)" :value="name" /></el-select><el-checkbox v-model="changedOnly">{{ t('仅看未保存') }}</el-checkbox><div class="toolbar-actions"><el-button :disabled="current.loading || current.saving" @click="load">{{ t('重新加载') }}</el-button><el-button v-if="canEdit" type="primary" :loading="current.saving" :disabled="!current.loaded || current.loading || !changedCount || !!errors.length || current.conflict || current.uncertain" @click="save">{{ changedCount ? t('保存 {count} 项修改', { count: changedCount }) : t('保存配置') }}</el-button></div></div>
    <div v-loading="current.loading" class="paper-content" :aria-busy="current.loading">
      <template v-if="current.loaded"><section v-for="section in sections" :key="section.name" class="paper-group"><h3>{{ t(section.name) }}</h3><PaperField v-for="field in section.fields" :key="`${scope}-${field.key}`" :field="field" :model-value="current.values[field.key]" :saved-value="current.original[field.key]" :disabled="!canEdit || current.saving || current.loading || current.conflict || current.uncertain" @update:model-value="updateField(field, $event)" /></section><el-empty v-if="!filtered.length" :description="t('没有匹配的设置')" :image-size="48" /></template>
      <el-empty v-else :description="t(current.loading ? '正在读取 Paper 配置…' : '配置尚未加载，请重新加载')" />
    </div>
    <p v-if="errors.length" class="validation-error" role="alert">{{ t('有 {count} 项修改尚未通过校验，请根据对应设置的提示调整后保存。', { count: errors.length }) }}</p>
  </div>
</template>

<style scoped>
.paper-alert { margin-bottom:14px; }.scope-selector { margin-bottom:14px; }
.scope-dot { width:6px; height:6px; border-radius:50%; background:var(--el-color-warning); }
.paper-state { display:flex; flex-wrap:wrap; gap:5px 16px; margin-bottom:12px; color:var(--lc-text-soft); font-size:12px; }
.paper-state span { display:inline-flex; align-items:center; gap:6px; }
.paper-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 20px 0 12px; }.paper-toolbar > :deep(.el-input) { flex: 1; min-width: 200px; }.paper-toolbar > :deep(.el-select) { width: 160px; }.toolbar-actions { display: flex; gap: 8px; }.toolbar-actions :deep(.el-button) { margin: 0; }
.paper-content { min-height:180px; }.paper-group { border-top:1px solid var(--lc-line); margin-bottom:20px; }h3 { margin:0; padding:14px 0 0; font-size:15px; font-weight:600; }.validation-error { color:var(--el-color-danger); font-size:12px; }
@media (max-width: 640px) { .paper-toolbar > :deep(.el-input) { min-width: min(200px, 100%); }.paper-toolbar > :deep(.el-select) { flex: 1; }.toolbar-actions { width: 100%; }.toolbar-actions :deep(.el-button) { flex: 1; } }
</style>
