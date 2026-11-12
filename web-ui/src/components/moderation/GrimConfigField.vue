<script setup>
import { computed, ref, watch } from 'vue'
import FieldInput from '../panel/FieldInput.vue'
import { grimFieldError, parsePunishmentCommand } from '../../utils/grim-config'
import { t } from '../../i18n'

const props = defineProps({
  field: { type: Object, required: true },
  modelValue: { default: undefined },
  disabled: Boolean,
  secretEdited: Boolean
})
const emit = defineEmits(['update:modelValue', 'edit-secret', 'reset-secret'])
const list = computed(() => Array.isArray(props.modelValue) ? props.modelValue : [])
const ruleRows = ref([])
watch(list, values => {
  ruleRows.value = values.map((raw, index) => {
    const previous = ruleRows.value[index]
    if (previous?.raw === raw) return previous
    const parsed = parsePunishmentCommand(raw)
    return { ...parsed, raw, structured: !!parsed }
  })
}, { immediate: true })
const error = computed(() => grimFieldError(props.field, props.modelValue))
const editable = computed(() => !props.disabled && !props.field.readOnly)
const wide = computed(() => ['textarea', 'string-list', 'punishment-commands'].includes(props.field.type))
const inputField = computed(() => {
  const field = { ...props.field, preview: false }
  if (field.type === 'number' && (field.max - field.min) / (field.step || 1) > 10_000) {
    delete field.min
    delete field.max
  }
  return field
})
function update(value) { if (editable.value) emit('update:modelValue', value) }
function editItem(index, value) { update(list.value.map((item, i) => i === index ? value : item)) }
function removeItem(index) { update(list.value.filter((_, i) => i !== index)) }
function addItem() { update([...list.value, props.field.type === 'punishment-commands' ? '1:1 [alert]' : '']) }
function commandPart(index, part, value) {
  if (!editable.value) return
  const row = ruleRows.value[index]
  row[part] = value
  row.raw = `${row.threshold ?? ''}:${row.interval ?? ''} ${row.command}`
  editItem(index, row.raw)
}
</script>

<template>
  <div class="grim-field" :class="{ wide, changed: secretEdited }" :data-field-key="field.key">
    <div class="field-heading"><label>{{ t(field.label) }}</label><span v-if="field.readOnly" class="readonly-label"><Icon name="lock-keyhole" :size="11" />{{ t('只读') }}</span></div>
    <code class="config-key">{{ field.key }}</code>
    <template v-if="field.secret">
      <div v-if="!secretEdited" class="secret-status"><span>{{ t(field.configured ? '已设置，原值不显示' : '尚未设置') }}</span><el-button size="small" :disabled="!editable" @click="emit('edit-secret')"><Icon name="file-pen-line" :size="13" />{{ t('修改') }}</el-button></div>
      <div v-else class="secret-editor"><el-input :model-value="modelValue" type="password" show-password autocomplete="new-password" :disabled="!editable" :aria-label="t(field.label)" :placeholder="t('输入新值，留空将清除此项')" @update:model-value="update" /><el-button size="small" :disabled="!editable" @click="emit('reset-secret')">{{ t('保留原值') }}</el-button></div>
    </template>
    <template v-else-if="field.type === 'string-list'">
      <div class="list-editor">
        <div v-for="(item, index) in list" :key="index" class="list-row"><el-input :model-value="item" :disabled="!editable" :aria-label="t('{label}第 {index} 项', { label: t(field.label), index: index + 1 })" @update:model-value="editItem(index, $event)" /><el-tooltip :content="t('删除此项')"><el-button class="icon-button" :disabled="!editable" :aria-label="t('删除此项')" @click="removeItem(index)"><Icon name="trash-2" :size="14" /></el-button></el-tooltip></div>
        <span v-if="!list.length" class="empty-list">{{ t('列表为空') }}</span>
        <el-button class="add-row" size="small" :disabled="!editable" @click="addItem"><Icon name="plus" :size="13" />{{ t('添加一项') }}</el-button>
      </div>
    </template>
    <template v-else-if="field.type === 'punishment-commands'">
      <div class="command-rules">
        <div v-if="list.length" class="rule-columns" aria-hidden="true"><span>{{ t('首次违规等级') }}</span><span>{{ t('重复间隔') }}</span><span>{{ t('执行命令') }}</span><span /></div>
        <div v-for="(item, index) in list" :key="index" class="rule-row">
          <template v-if="ruleRows[index]?.structured">
            <div class="rule-part"><span class="mobile-label">{{ t('首次违规等级') }}</span><el-input-number :model-value="ruleRows[index].threshold" :min="0" :max="2147483647" :step="1" :disabled="!editable" controls-position="right" :aria-label="t('首次违规等级')" @update:model-value="commandPart(index, 'threshold', $event)" /></div>
            <div class="rule-part"><span class="mobile-label">{{ t('重复间隔') }}</span><el-input-number :model-value="ruleRows[index].interval" :min="0" :max="2147483647" :step="1" :disabled="!editable" controls-position="right" :aria-label="t('重复间隔')" @update:model-value="commandPart(index, 'interval', $event)" /></div>
            <div class="rule-part rule-command"><span class="mobile-label">{{ t('执行命令') }}</span><el-input :model-value="ruleRows[index].command" :disabled="!editable" :aria-label="t('执行命令')" @update:model-value="commandPart(index, 'command', $event)" /></div>
          </template>
          <el-input v-else class="raw-rule" :model-value="item" :disabled="!editable" :aria-label="t('原始处罚规则')" @update:model-value="editItem(index, $event)" />
          <el-tooltip :content="t('删除此项')"><el-button class="icon-button" :disabled="!editable" :aria-label="t('删除此项')" @click="removeItem(index)"><Icon name="trash-2" :size="14" /></el-button></el-tooltip>
        </div>
        <span v-if="!list.length" class="empty-list">{{ t('暂无处罚命令') }}</span>
        <div class="rule-footer"><el-button size="small" :disabled="!editable" @click="addItem"><Icon name="plus" :size="13" />{{ t('添加处罚规则') }}</el-button><span>{{ t('重复间隔为 0 时只执行一次。') }}</span></div>
      </div>
    </template>
    <div v-else-if="field.readOnly" class="readonly-value">{{ typeof modelValue === 'boolean' ? t(modelValue ? '是' : '否') : modelValue ?? '-' }}</div>
    <el-form v-else :disabled="!editable" label-position="top" @submit.prevent><FieldInput :field="inputField" :model-value="modelValue" @update:model-value="update" /></el-form>
    <p v-if="field.help" class="field-help">{{ t(field.help) }}</p>
    <p v-if="error" class="field-error" role="alert">{{ t(error) }}</p>
  </div>
</template>

<style scoped>
.grim-field { min-width: 0; }.wide { grid-column: 1 / -1; }
.field-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 4px; }label { font-size: 13px; font-weight: 550; line-height: 1.6; overflow-wrap: anywhere; }
.config-key { display: block; margin-bottom: 10px; color: var(--lc-text-soft); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.readonly-label { display: inline-flex; align-items: center; gap: 4px; flex-shrink: 0; font-size: 11px; color: var(--lc-text-soft); }
.readonly-value { font-size: 13px; padding-block: 7px; overflow-wrap: anywhere; white-space: pre-wrap; }
.field-help,.field-error { margin: 7px 0 0; font-size: 12px; color: var(--lc-text-soft); line-height: 1.7; overflow-wrap: anywhere; }.field-error { color: var(--el-color-danger); }
.list-editor,.command-rules { display: grid; gap: 8px; }.list-row { display: grid; grid-template-columns: minmax(0, 1fr) 34px; gap: 8px; }
.rule-row,.rule-columns { display: grid; grid-template-columns: 128px 128px minmax(0, 1fr) 34px; gap: 10px; align-items: start; }.rule-columns { font-size: 11px; color: var(--lc-text-soft); }.raw-rule { grid-column: 1 / 4; }
.rule-part { min-width: 0; }.mobile-label { display: none; }
.rule-row :deep(.el-input-number) { width: 100%; }.rule-row :deep(.el-input-number .el-input__inner) { text-align: left; }.rule-row :deep(.el-input),.list-row :deep(.el-input) { min-width: 0; }
.icon-button { width: 34px; height: 34px; padding: 0; }.add-row { justify-self: start; }.empty-list { padding: 5px 0; color: var(--lc-text-soft); font-size: 12px; }
.rule-footer { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 3px; }.rule-footer span { font-size: 12px; color: var(--lc-text-soft); }
.secret-status,.secret-editor { display: flex; align-items: center; gap: 12px; min-height: 34px; }.secret-status > span { font-size: 12px; color: var(--lc-text-soft); }.secret-editor > :deep(.el-input) { min-width: 0; flex: 1; }.secret-editor > :deep(.el-button) { flex-shrink: 0; }
:deep(.el-button) { gap: 5px; margin: 0; border-radius: 5px; }:deep(.el-form) { width: 100%; }
@media (max-width: 760px) { .rule-columns { display: none; }.mobile-label { display: block; margin-bottom: 6px; color: var(--lc-text-soft); font-size: 11px; line-height: 1.6; }.rule-row { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 34px; padding-block: 9px; border-bottom: 1px solid var(--el-border-color-lighter); }.rule-command { grid-column: 1 / 3; grid-row: 2; }.rule-row > :deep(.icon-button) { grid-column: 3; grid-row: 1 / 3; }.rule-row > :deep(.raw-rule) { grid-column: 1 / 3; grid-row: 1 / 3; } }
</style>
