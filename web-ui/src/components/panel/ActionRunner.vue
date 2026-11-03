<script setup>
import { reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { request, resolveEndpoint } from '../../api'
import { fillTemplate, fillText, toDurationText } from '../../utils/format'
import FieldInput from './FieldInput.vue'
import { useAuth } from '../../stores/auth'
import { missingRequired, requireSuccess, selectValue, presentFields } from '../../utils/panel'
import { t } from '../../i18n'

// 执行 WebPanel.Action：confirm → 弹窗收集字段 → 请求 {row, ...字段}
const props = defineProps({ namespace: { type: String, required: true }, columns: { type: Array, default: () => [] } })
const emit = defineEmits(['done'])

const auth = useAuth()
const dlg = reactive({ visible: false, action: null, row: null, values: {}, loading: false, busy: false, error: '' })

/** 行值预填：duration 列（毫秒）填入文本/时长字段时转为 "1d2h" 形式。 */
function prefill(field, value) {
  const col = props.columns.find((c) => c.key === field.key)
  const ft = (field.type || 'text').toLowerCase()
  if (col && (col.type || '').toLowerCase() === 'duration' && (ft === 'text' || ft === 'duration') && typeof value === 'number') return toDurationText(value)
  return selectValue(field, value)
}

function initialValues(action, row) {
  const v = {}
  for (const f of action.fields || []) {
    // 行数据中同名字段优先（便于“修改”类操作预填当前值），其次 defaultValue
    if (row && row[f.key] !== undefined && row[f.key] !== null) v[f.key] = prefill(f, row[f.key])
    else if (f.defaultValue !== undefined && f.defaultValue !== null) v[f.key] = f.defaultValue
    else v[f.key] = f.type === 'bool' ? false : null
  }
  return v
}

async function run(action, row) {
  if (dlg.busy || dlg.visible) return
  if (!auth.can(action.permission)) return ElMessage.warning(t('当前账号没有执行此操作的权限'))
  dlg.busy = true
  dlg.error = ''
  try {
  if (action.confirm) {
    const text = fillText(action.confirm, row || {})
    const ok = await ElMessageBox.confirm(t(text), t(action.label), {
      type: action.style === 'danger' ? 'warning' : 'info',
      confirmButtonText: t(action.label),
      confirmButtonClass: action.style === 'danger' ? 'el-button--danger' : ''
    }).then(() => true).catch(() => false)
    if (!ok) return
  }
  if (action.fields && action.fields.length) {
    Object.assign(dlg, { visible: true, action, row: row || {}, values: initialValues(action, row), loading: false })
    return
  }
  await execute(action, row || {}, {})
  } finally { dlg.busy = false }
}

async function submit() {
  if (dlg.loading || !dlg.action) return
  const missing = missingRequired(dlg.action.fields, dlg.values)
  if (missing) return ElMessage.warning(t('请填写「{label}」', { label: t(missing.label) }))
  dlg.loading = true
  const ok = await execute(dlg.action, dlg.row, dlg.values)
  dlg.loading = false
  if (ok) dlg.visible = false
}

async function execute(action, row, values) {
  if (!auth.can(action.permission)) { dlg.error = t('当前账号没有执行此操作的权限'); return false }
  const method = (action.method || 'POST').toUpperCase()
  // 未填写（null/undefined）的字段不提交，后端可用 containsKey 判断“保持原值”
  values = presentFields(values)
  try {
    if (!action.endpoint) throw new Error(t('操作未配置接口地址'))
    const path = fillTemplate(resolveEndpoint(action.endpoint, props.namespace), row)
    let res
    if (method === 'GET') {
      res = await request('GET', path, null, { query: values })
    } else {
      res = await request(method, path, { row, ...values })
    }
    requireSuccess(res)
    const msg = res && typeof res === 'object' ? res.message : null
    dlg.error = ''
    ElMessage.success(msg || t('{label} 成功', { label: t(action.label) }))
    emit('done', res)
    return true
  } catch (e) {
    dlg.error = e.message
    ElMessage.error(e.message)
    return false
  }
}

defineExpose({ run })
</script>

<template>
  <el-dialog v-model="dlg.visible" :title="t(dlg.action?.label)" class="panel-action-dialog" width="min(560px, 94vw)" :close-on-click-modal="!dlg.loading" :close-on-press-escape="!dlg.loading" :show-close="!dlg.loading" append-to-body destroy-on-close>
    <template #header><div class="dialog-heading"><span><Icon :name="dlg.action?.style === 'danger' ? 'triangle-alert' : 'sliders-horizontal'" :size="20" /></span><div><h3>{{ t(dlg.action?.label) }}</h3><p>{{ t('请确认以下内容后提交操作。') }}</p></div></div></template>
    <el-alert v-if="dlg.error" :title="dlg.error" type="error" show-icon :closable="false" style="margin-bottom: 16px" />
    <el-form :disabled="dlg.loading" v-if="dlg.action" label-position="top" class="action-form" @submit.prevent="submit">
      <el-form-item v-for="f in dlg.action.fields" :key="f.key" :label="t(f.label)" :required="f.required" :class="{ 'field-wide': ['textarea', 'code'].includes(f.type) }">
        <FieldInput v-model="dlg.values[f.key]" :field="f" />
        <div v-if="f.help" class="help">{{ t(f.help) }}</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="dlg.loading" @click="dlg.visible = false">{{ t('取消') }}</el-button>
      <el-button :type="dlg.action?.style === 'danger' ? 'danger' : 'primary'" :loading="dlg.loading" @click="submit">{{ t(dlg.action?.label || '确定') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.panel-action-dialog) { padding: 20px; border: 1px solid var(--el-border-color-light); border-radius: 9px; background: var(--lbr-surface); --el-dialog-padding-primary: 0px; --el-dialog-border-radius: 9px; }
.dialog-heading { display: flex; align-items: flex-start; gap: 9px; padding-bottom: 14px; color: var(--lbr-text); }
.dialog-heading > span { display: flex; padding-top: 1px; color: var(--el-color-primary); }
.dialog-heading h3 { margin: 0; font-size: 15px; font-weight: 650; line-height: 1.5; }
.dialog-heading p { margin: 2px 0 0; font-size: 11px; color: var(--lbr-text-soft); }
.action-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 16px; }
.action-form :deep(.el-form-item) { min-width: 0; margin-bottom: 0; }
.action-form .field-wide { grid-column: 1 / -1; }
.action-form :deep(.el-form-item__content) { gap: 4px; }
:global(.panel-action-dialog .el-dialog__footer) { padding-top: 13px; margin-top: 16px; border-top: 1px solid var(--el-border-color-light); }
:global(.panel-action-dialog .el-button) { border-radius: 5px; font-size: 12px; }
:global(.panel-action-dialog .el-button:focus-visible) { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
@media (max-width: 600px) { .action-form { grid-template-columns: minmax(0, 1fr); } :global(.panel-action-dialog) { padding: 16px; } }
@media (prefers-reduced-motion: reduce) { :global(.panel-action-dialog), .action-form :deep(*) { transition: none !important; animation: none !important; } }
.action-form :deep(.el-form-item__label) { height: auto; font-size: 12px; line-height: 1.5; white-space: normal; overflow-wrap: anywhere; padding-bottom: 5px; }
.action-form :deep(.el-form-item__content) { min-width: 0; }
.help { font-size: 11px; color: var(--lbr-text-soft); line-height: 1.5; width: 100%; }
</style>
