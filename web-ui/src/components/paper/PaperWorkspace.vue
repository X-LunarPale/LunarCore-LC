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
    <div class="paper-intro"><div><h3>{{ t('Paper 可视化设置') }}</h3><p>{{ t('使用中文说明调整服务器参数，保存前可核对已保存值与本次修改。') }}</p></div><el-tag v-if="current.loaded" :type="changedCount ? 'warning' : 'success'" effect="plain">{{ changedCount ? t('{count} 项未保存', { count: changedCount }) : t('已与配置文件同步') }}</el-tag></div>
    <el-alert class="paper-alert" type="info" :title="t('保存后需重启服务器生效')" :description="t('每次保存会自动备份原配置文件；仅写入本次修改的设置。世界默认配置可能被各世界的独立配置覆盖。')" show-icon :closable="false" />
    <div class="scope-selector" role="group" :aria-label="t('配置作用域')"><button v-for="item in SCOPES" :key="item.id" type="button" :class="{ selected: scope === item.id }" :aria-pressed="scope === item.id" @click="scope = item.id"><strong>{{ t(item.label) }}<span v-if="Object.keys(patchFor(states[item.id])).length" class="scope-dot" :aria-label="t('有未保存修改')" /></strong><span>{{ t(item.description) }}</span></button></div>
    <el-alert v-if="current.loaded && !canEdit" class="paper-alert" type="info" :title="t('当前为只读模式')" :description="t('此账号或当前配置仅允许查看，无法保存修改。')" show-icon :closable="false" />
    <el-alert v-if="current.error" class="paper-alert" type="error" :title="t(current.conflict ? '配置已发生变化，已停止保存' : current.uncertain ? '保存结果需要核对' : 'Paper 配置操作失败')" :description="current.error + (current.conflict || current.uncertain ? t(' 当前修改已保留，请重新加载最新配置，核对后重新编辑。') : '')" show-icon :closable="false" />
    <el-alert v-if="current.saved" class="paper-alert" type="success" :title="t('配置已保存，等待服务器重启后生效')" :description="t('已保存到配置文件，当前运行参数尚未刷新。')" show-icon :closable="false" />
    <div class="paper-toolbar"><el-input v-model="search" :placeholder="t('搜索名称、说明或配置键')" clearable :aria-label="t('搜索 Paper 配置')" /><el-select v-model="group" clearable :placeholder="t('全部分组')" :aria-label="t('筛选配置分组')"><el-option v-for="name in groups" :key="name" :label="t(name)" :value="name" /></el-select><el-checkbox v-model="changedOnly">{{ t('仅看未保存') }}</el-checkbox><div class="toolbar-actions"><el-button :disabled="current.loading || current.saving" @click="load">{{ t('重新加载') }}</el-button><el-button v-if="canEdit" type="primary" :loading="current.saving" :disabled="!current.loaded || current.loading || !changedCount || !!errors.length || current.conflict || current.uncertain" @click="save">{{ changedCount ? t('保存 {count} 项修改', { count: changedCount }) : t('保存配置') }}</el-button></div></div>
    <div v-loading="current.loading" class="paper-content" :aria-busy="current.loading">
      <template v-if="current.loaded"><div class="result-summary">{{ t('显示 {shown} / {total} 项设置', { shown: filtered.length, total: fields.length }) }}<span v-if="current.data.configVersion != null">{{ t('配置版本 {version}', { version: current.data.configVersion }) }}</span></div><section v-for="section in sections" :key="section.name" class="paper-group"><h4>{{ t(section.name) }}<span>{{ t('{count} 项', { count: section.fields.length }) }}</span></h4><PaperField v-for="field in section.fields" :key="`${scope}-${field.key}`" :field="field" :model-value="current.values[field.key]" :saved-value="current.original[field.key]" :disabled="!canEdit || current.saving || current.loading || current.conflict || current.uncertain" @update:model-value="updateField(field, $event)" /></section><el-empty v-if="!filtered.length" :description="t('没有匹配的设置')" /></template>
      <el-empty v-else :description="t(current.loading ? '正在读取 Paper 配置…' : '配置尚未加载，请重新加载')" />
    </div>
    <p class="paper-footnote">{{ t('参考默认值仅用于说明，不代表继承值或服务器当前运行值。未收录的配置保持原样，不展示其内容。') }}</p>
    <p v-if="errors.length" class="validation-error" role="alert">{{ t('有 {count} 项修改尚未通过校验，请根据对应设置的提示调整后保存。', { count: errors.length }) }}</p>
  </div>
</template>

<style scoped>
.paper-intro { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin: 6px 0 18px; }
h3 { margin: 0 0 7px; font-size: 17px; }.paper-intro p { margin: 0; font-size: 13px; color: var(--lc-text-soft); line-height: 1.7; }
.paper-alert { margin-bottom: 14px; }.scope-selector { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 18px 0; }
.scope-selector button { padding: 17px 18px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--lc-surface); color: var(--el-text-color-primary); text-align: left; cursor: pointer; }
.scope-selector button.selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }.scope-selector strong { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; font-size: 14px; }.scope-selector button > span { font-size: 12px; line-height: 1.7; color: var(--lc-text-soft); }.scope-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--el-color-warning); }
.paper-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 20px 0 12px; }.paper-toolbar > :deep(.el-input) { flex: 1; min-width: 200px; }.paper-toolbar > :deep(.el-select) { width: 160px; }.toolbar-actions { display: flex; gap: 8px; }.toolbar-actions :deep(.el-button) { margin: 0; }
.paper-content { min-height: 180px; }.result-summary { display: flex; gap: 20px; color: var(--lc-text-soft); font-size: 12px; margin-bottom: 12px; }.paper-group { border: 1px solid var(--el-border-color); border-radius: 9px; background: var(--lc-surface); overflow: hidden; margin-bottom: 18px; }h4 { display: flex; align-items: center; justify-content: space-between; margin: 0; padding: 15px 24px; border-bottom: 1px solid var(--el-border-color); font-size: 14px; background: var(--el-fill-color-light); }h4 span { font-size: 11px; color: var(--lc-text-soft); font-weight: normal; }.paper-footnote { font-size: 12px; line-height: 1.8; color: var(--lc-text-soft); }.validation-error { color: var(--el-color-danger); font-size: 12px; }
@media (max-width: 640px) { .scope-selector { grid-template-columns: 1fr; }.paper-toolbar > :deep(.el-select) { flex: 1; }.toolbar-actions { width: 100%; }.toolbar-actions :deep(.el-button) { flex: 1; }h4 { padding-inline: 16px; } }
</style>
