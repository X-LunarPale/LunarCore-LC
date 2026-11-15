<script setup>
import SectionError from './SectionError.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuth } from '../../stores/auth'
import { api, resolveEndpoint } from '../../api'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { groupFields, latestRequest, missingRequired, requireSuccess } from '../../utils/panel'
import FieldInput from './FieldInput.vue'
import ChatAppearance from '../chat/ChatAppearance.vue'
import { t } from '../../i18n'

const props = defineProps({ section: Object, namespace: String, active: { type: Boolean, default: true } })
const model = reactive({})
const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const error = ref(null)
const saveError = ref(null)
const begin = latestRequest()
const auth = useAuth()
const baseline = ref('{}')
const dirty = computed(() => loaded.value && JSON.stringify(model) !== baseline.value)
const canSave = computed(() => auth.can(props.section.savePermission))
const fieldGroups = computed(() => groupFields((props.section.fields || []).filter(field => field.type !== 'world-aliases')))
const activeGroup = ref('')
const hasAppearance = computed(() => props.namespace === 'chat' && Array.isArray(model.worldAliases))
const editorGroups = computed(() => [
  ...fieldGroups.value.map((group, index) => ({ id: `fields-${index}`, title: group.title || '基本设置', fields: group.fields })),
  ...(hasAppearance.value ? [{ id: 'appearance', title: '世界与预览' }] : [])
])
watch(editorGroups, groups => {
  if (!groups.some(group => group.id === activeGroup.value)) activeGroup.value = groups[0]?.id || ''
}, { immediate: true })
let discardPrompt
let refreshing = false

async function confirmDiscard() {
  if (saving.value) {
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

function beforeUnload(event) {
  if (!dirty.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}

async function load(background = false) {
  background = background === true
  if (refreshing || loading.value || (background && (dirty.value || saving.value)) || (!background && !await confirmDiscard())) return
  const current = begin()
  refreshing = true
  if (!background || !loaded.value) loading.value = true
  try {
    const data = props.section.loadEndpoint
      ? requireSuccess(await api.get(resolveEndpoint(props.section.loadEndpoint, props.namespace))) : {}
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error(t('表单接口返回格式错误'))
    if (!current() || (background && (dirty.value || saving.value))) return
    // Only replace the model after a successful load. Failed loads cannot save defaults.
    for (const key of Object.keys(model)) delete model[key]
    for (const f of props.section.fields || []) {
      model[f.key] = data[f.key] !== undefined ? data[f.key] : f.defaultValue !== undefined ? f.defaultValue : ['bool', 'boolean'].includes(f.type) ? false : null
    }
    if (!current()) return
    error.value = null
    saveError.value = null
    baseline.value = JSON.stringify(model)
    loaded.value = true
  } catch (e) {
    if (current()) error.value = { status: e.status, message: e.message }
  } finally {
    refreshing = false
    if (current()) loading.value = false
  }
}

async function save() {
  if (saving.value || loading.value || !loaded.value || !props.section.saveEndpoint || !canSave.value) return
  const missing = missingRequired(props.section.fields, model)
  if (missing) {
    activeGroup.value = editorGroups.value.find(group => group.fields?.some(field => field.key === missing.key))?.id || activeGroup.value
    return ElMessage.warning(t('请填写「{label}」', { label: t(missing.label) }))
  }
  saving.value = true
  saveError.value = null
  try {
    const body = Object.fromEntries(Object.entries(model).filter(([, v]) => v !== null && v !== undefined))
    const saved = JSON.stringify(model)
    const r = requireSuccess(await api.post(resolveEndpoint(props.section.saveEndpoint, props.namespace), body), t('保存失败'))
    baseline.value = saved
    ElMessage.success(r?.message || t('已保存'))
  } catch (e) {
    saveError.value = { status: e.status, message: e.message }
  } finally { saving.value = false }
  if (!saveError.value) {
    window.dispatchEvent(new CustomEvent('lc:panel-changed', { detail: { namespace: props.namespace } }))
  }
}

useAutoRefresh(() => load(true), {
  namespace: props.namespace,
  active: () => props.active && !dirty.value && !saving.value,
  immediate: true
})
onBeforeRouteLeave(confirmDiscard)
onBeforeRouteUpdate((to, from) => to.fullPath === from.fullPath || confirmDiscard())
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  begin() // Ignore responses arriving after this form has been removed.
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="form-surface" v-loading="loading" :aria-busy="loading || saving">
    <header class="surface-header">
      <div class="heading-block"><Icon class="heading-mark" name="sliders-horizontal" :size="16" /><h3>{{ t(section.title || '设置') }}</h3></div>
      <span v-if="dirty" class="dirty-state" role="status"><span />{{ t('尚未保存') }}</span>
    </header>
    <div v-if="section.saveEndpoint && !canSave" class="read-only"><Icon name="lock-keyhole" :size="14" />{{ t('当前账号仅可查看此配置。') }}</div>
    <SectionError :error="error || saveError" />
    <el-button v-if="error" @click="load" :disabled="loading">{{ t('重新加载') }}</el-button>
    <el-form v-if="loaded" :disabled="saving || loading" label-position="top" @submit.prevent="save" class="panel-form">
      <div v-if="editorGroups.length > 1" class="form-groups" :aria-label="t('设置分组')">
        <button v-for="group in editorGroups" :key="group.id" type="button" :class="{ selected: activeGroup === group.id }" :aria-pressed="activeGroup === group.id" @click="activeGroup = group.id">{{ t(group.title) }}</button>
      </div>
      <div v-for="group in editorGroups.filter(item => item.fields)" v-show="activeGroup === group.id" :key="group.id" class="field-group" role="group" :aria-label="t(group.title)">
        <div class="fields-grid">
          <el-form-item v-for="f in group.fields" :key="f.key" :data-field-key="f.key" :label="t(f.label)" :required="f.required" :class="{ 'field-wide': ['textarea', 'code'].includes(f.type), 'field-switch': ['bool', 'boolean'].includes(f.type) }">
            <FieldInput v-if="canSave" v-model="model[f.key]" :field="f" />
            <span v-else class="read-value">{{ f.type === 'password' ? '••••••' : ['bool', 'boolean'].includes(f.type) ? t([true, 'true', 1, '1'].includes(model[f.key]) ? '是' : '否') : model[f.key] ?? '-' }}</span>
            <div v-if="f.help" class="help">{{ t(f.help) }}</div>
          </el-form-item>
        </div>
      </div>
      <ChatAppearance v-if="hasAppearance" v-show="activeGroup === 'appearance'" :model="model" :disabled="!canSave || saving || loading" />
      <footer v-if="section.saveEndpoint" class="form-actions">
        <div class="action-note" role="status">{{ saving ? t('正在保存，请稍候…') : dirty ? t('修改尚未生效') : '' }}</div>
        <div class="action-buttons"><el-button @click="load" :disabled="saving"><Icon name="rotate-ccw" :size="14" />&nbsp;{{ t('重新加载') }}</el-button>
        <el-button v-if="canSave" type="primary" native-type="submit" :loading="saving" :disabled="!dirty || loading"><Icon name="save" :size="14" />&nbsp;{{ t(section.submitLabel || '保存设置') }}</el-button></div>
      </footer>
    </el-form>
    <div v-else-if="loading" class="initial-loading" role="status">{{ t('正在加载当前配置…') }}</div>
  </section>
</template>

<style scoped>
.form-surface { padding:0; color:var(--lc-text); min-width:0; container-type:inline-size; }
.surface-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom:14px; margin-bottom:20px; border-bottom:1px solid var(--el-border-color-light); }
.heading-block { display: flex; align-items: center; gap: 9px; min-width: 0; }
.heading-mark { color: var(--el-color-primary); flex-shrink: 0; }
h3 { font-size: 15px; font-weight: 600; line-height: 1.5; margin: 0; }
.dirty-state { font-size: 12px; flex-shrink: 0; color: var(--lc-text-soft); }
.dirty-state { display: flex; gap: 6px; align-items: center; color: var(--el-color-warning); padding: 3px 7px; border-radius: 4px; background: var(--el-color-warning-light-9); }
.dirty-state span { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.read-only { display: flex; align-items: center; gap: 7px; padding: 8px 10px; background: var(--el-fill-color-light); border-radius: 6px; font-size: 12px; margin-bottom: 12px; color: var(--lc-text-soft); }
.initial-loading { min-height: 72px; display: grid; place-items: center; color: var(--lc-text-soft); font-size: 12px; }
.help { font-size: 12px; color: var(--lc-text-soft); line-height: 1.6; width: 100%; overflow-wrap: anywhere; }
.read-value { min-height: 36px; display: flex; align-items: center; overflow-wrap: anywhere; }
.action-note { font-size: 12px; color: var(--lc-text-soft); margin-right: auto; }
.action-buttons { display: flex; gap: 8px; flex-shrink: 0; }
.action-buttons :deep(.el-button + .el-button) { margin-left: 0; }
.form-surface :deep(.el-button) { border-radius: 5px; font-size: 13px; transition: background-color 180ms, border-color 180ms, transform 320ms var(--motion-spring); }
.form-surface :deep(.el-button:focus-visible) { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.panel-form { width: 100%; min-width: 0; }
.form-groups { display:flex; flex-wrap:wrap; gap:5px; margin:0 0 24px; }
.form-groups button { min-height:34px; padding:6px 11px; border:1px solid transparent; border-radius:5px; background:transparent; color:var(--el-text-color-regular); font:inherit; font-size:12px; cursor:pointer; }
.form-groups button.selected { border-color:var(--el-border-color); background:var(--lc-surface); color:var(--el-color-primary); font-weight:600; }
.form-groups button:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }
.field-group { min-width:0; }
.fields-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px 28px; min-width: 0; max-width:1080px; }
.panel-form :deep(.el-form-item) { min-width: 0; margin-bottom: 0; align-self: start; }
.panel-form :deep(.el-form-item__label) { height: auto; font-size: 13px; line-height: 1.6; padding: 0 0 7px; margin: 0; white-space: normal; overflow-wrap: anywhere; }
.panel-form :deep(.el-form-item__content) { min-width: 0; line-height: 1.6; gap: 6px; }
.panel-form :deep(.el-input__wrapper), .panel-form :deep(.el-select__wrapper) { min-height: 36px; }
.panel-form :deep(.el-input__inner), .panel-form :deep(.el-select__wrapper) { font-size: 13px; }
.panel-form :deep(.el-input-number .el-input__inner) { text-align: left; }
.field-switch :deep(.el-switch) { height: 36px; margin: 0; }
.field-wide { grid-column: 1 / -1; }
.form-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding:16px 0; margin-top:28px; border-top:1px solid var(--el-border-color-light); }
@container (max-width: 520px) { .fields-grid { grid-template-columns: minmax(0, 1fr); gap: 18px; } .action-note { width: 100%; } .action-buttons { width: 100%; } .action-buttons .el-button { flex: 1; min-width: 0; } }
@media (max-width: 760px) { .surface-header { align-items: flex-start; flex-wrap: wrap; margin-bottom:18px; } .form-groups { margin-bottom:20px; } }
@media (prefers-reduced-motion: reduce) { .form-surface :deep(*) { transition: none !important; animation: none !important; } }
</style>
