<script setup>
import { computed } from 'vue'
import { fieldError, formatValue, isNumeric, optionsFor, sentinelEntries } from './paper'
import { t } from '../../i18n'

const props = defineProps({ field: { type: Object, required: true }, modelValue: {}, savedValue: {}, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const changed = computed(() => !Object.is(props.modelValue, props.savedValue))
const sentinels = computed(() => sentinelEntries(props.field))
const currentSentinel = computed(() => sentinels.value.find(item => Object.is(item.value, props.modelValue)))
const error = computed(() => changed.value ? fieldError(props.field, props.modelValue) : '')
const errorText = computed(() => {
  if (error.value.startsWith('不得小于 ')) return t('不得小于 {value}', { value: props.field.min })
  if (error.value.startsWith('不得大于 ')) return t('不得大于 {value}', { value: props.field.max })
  return t(error.value)
})
const readonly = computed(() => props.disabled || props.field.editable === false || props.field.present === false)
// Never feed an out-of-range persisted value into a slider or a bounded input.
const sliderVisible = computed(() => isNumeric(props.field) && Number.isFinite(props.field.min) && Number.isFinite(props.field.max) && props.field.max > props.field.min && typeof props.modelValue === 'number' && props.modelValue >= props.field.min && props.modelValue <= props.field.max && !currentSentinel.value)
const options = computed(() => optionsFor(props.field))
const knownOption = computed(() => options.value.some(option => Object.is(option.value, props.modelValue)))
const fieldId = computed(() => `paper-field-${props.field.key.replace(/[^a-z0-9-]/gi, '-')}`)
function numberInput(event) {
  const value = event.target.value
  const special = sentinels.value.find(item => String(item.value) === value.trim())
  emit('update:modelValue', special ? special.value : value.trim() === '' ? null : Number(value))
}
</script>

<template>
  <article class="paper-field" :class="{ changed, invalid: error }">
    <div class="field-copy">
      <div class="field-title"><label :for="fieldId">{{ field.label || field.name || field.key }}</label><el-tag v-if="changed" type="warning" size="small">{{ t('未保存') }}</el-tag><el-tag v-if="field.present === false || field.editable === false" type="info" size="small">{{ t('只读') }}</el-tag></div>
      <p class="field-help">{{ field.help || field.description }}</p>
      <code>{{ field.key }}</code>
      <div class="field-meta">{{ t('已保存：{value}', { value: t(formatValue(savedValue)) }) }}<span>{{ t('参考默认值：{value}', { value: t(formatValue(field.default ?? field.defaultValue)) }) }}</span><span v-if="field.unit">{{ t('单位：{unit}', { unit: field.unit }) }}</span></div>
      <p v-if="field.present === false" class="field-note">{{ t('配置文件未声明此项，参考默认值不代表当前运行值。') }}</p>
    </div>
    <div class="field-control">
      <el-switch v-if="field.type === 'boolean' && typeof modelValue === 'boolean'" :id="fieldId" :model-value="modelValue" :disabled="readonly" :active-text="t('开启')" :inactive-text="t('关闭')" :aria-label="field.label || field.name" @update:model-value="emit('update:modelValue', $event)" />
      <template v-else-if="isNumeric(field)">
        <div class="numeric-control"><el-slider v-if="sliderVisible" :model-value="modelValue" :min="field.min" :max="field.max" :step="field.step || 1" :disabled="readonly" :aria-label="t('{name}滑块', { name: field.label || field.name })" @update:model-value="emit('update:modelValue', $event)" /><input :id="fieldId" class="exact-input" :type="typeof modelValue === 'string' ? 'text' : 'number'" :value="modelValue" :step="field.step || 'any'" :disabled="readonly" :aria-invalid="!!error" :aria-describedby="`${fieldId}-detail`" @input="numberInput" /></div>
        <div :id="`${fieldId}-detail`" class="range-help"><span v-if="field.min != null || field.max != null">{{ t('常规范围：{min} 至 {max}', { min: field.min ?? t('不限'), max: field.max ?? t('不限') }) }}<template v-if="field.step">{{ t('，步长 {step}', { step: field.step }) }}</template>。</span><span v-if="currentSentinel">{{ t('当前特殊值：{value}（{label}）。', { value: t(formatValue(currentSentinel.value)), label: currentSentinel.label }) }}</span><span v-else-if="!sliderVisible && modelValue != null">{{ t('当前值保持原样，可使用精确输入调整。') }}</span></div>
        <div v-if="sentinels.length" class="sentinel-options"><el-button v-for="item in sentinels" :key="String(item.value)" size="small" :disabled="readonly" :type="Object.is(item.value, modelValue) ? 'primary' : 'default'" plain @click="emit('update:modelValue', item.value)">{{ item.label }}（{{ item.value }}）</el-button></div>
      </template>
      <el-select v-else-if="options.length" :id="fieldId" :model-value="modelValue" :disabled="readonly" :aria-label="field.label || field.name" @update:model-value="emit('update:modelValue', $event)"><el-option v-if="!knownOption && modelValue != null" :value="modelValue" :label="t('当前值：{value}', { value: t(formatValue(modelValue)) })" disabled /><el-option v-for="option in options" :key="String(option.value)" :label="option.label" :value="option.value" /></el-select>
      <span v-else class="readonly-value">{{ t(formatValue(modelValue)) }} · {{ t('只读') }}</span>
      <p v-if="error" class="field-error" role="alert">{{ errorText }}</p>
      <el-button v-if="changed" text size="small" :disabled="readonly" @click="emit('update:modelValue', savedValue)">{{ t('撤销此项修改') }}</el-button>
    </div>
  </article>
</template>

<style scoped>
.paper-field { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(250px, 1fr); gap: 28px; padding: 22px 24px; border-bottom: 1px solid var(--el-border-color-lighter); }
.paper-field:last-child { border-bottom: 0; }
.paper-field.changed { box-shadow: inset 3px 0 var(--el-color-warning); background: var(--el-color-warning-light-9); }
.field-title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-weight: 600; font-size: 14px; }
.field-help { margin: 8px 0 7px; color: var(--lc-text-soft); font-size: 13px; line-height: 1.7; }
code { font-size: 11px; color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
.field-meta { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 10px; font-size: 11px; color: var(--lc-text-soft); }
.field-control { align-self: center; min-width: 0; }
.numeric-control { display: flex; align-items: center; gap: 22px; }
.numeric-control :deep(.el-slider) { flex: 1; min-width: 80px; margin-left: 8px; }
.exact-input { width: 126px; max-width: 100%; box-sizing: border-box; border: 1px solid var(--el-border-color); background: var(--el-fill-color-blank); border-radius: 5px; color: var(--el-text-color-primary); padding: 8px 10px; font: inherit; font-size: 13px; }
.exact-input:focus { outline: 2px solid var(--el-color-primary-light-5); outline-offset: 2px; }
.exact-input:disabled { opacity: .65; cursor: not-allowed; }
.range-help, .field-note { font-size: 11px; line-height: 1.7; color: var(--lc-text-soft); margin: 8px 0 0; }
.range-help span { display: block; }
.sentinel-options { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.sentinel-options :deep(.el-button) { margin: 0; height: auto; white-space: normal; line-height: 1.6; }
.field-error { color: var(--el-color-danger); font-size: 12px; margin: 8px 0 0; }
.readonly-value { font-size: 13px; color: var(--lc-text-soft); }
@media (max-width: 780px) { .paper-field { grid-template-columns: 1fr; gap: 16px; padding: 18px 16px; } }
</style>
