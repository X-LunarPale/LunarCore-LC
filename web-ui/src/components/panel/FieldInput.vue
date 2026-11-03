<script setup>
import { computed, inject, unref } from 'vue'
import { formContextKey } from 'element-plus'
import CodeEditor from '../CodeEditor.vue'
import MinecraftTextPreview from '../text-preview/MinecraftTextPreview.vue'
import { numberMetadata, shouldPreviewField } from '../text-preview/minecraft-text'
import { avatarUrl, formatDuration, parseDuration } from '../../utils/format'
import { t } from '../../i18n'

// WebPanel.Field 渲染：text | number | bool | select | textarea | password | player | duration | datetime | code
const props = defineProps({
  field: { type: Object, required: true },
  modelValue: { default: undefined }
})
const emit = defineEmits(['update:modelValue'])
const formContext = inject(formContextKey, null)
const formDisabled = computed(() => !!unref(formContext?.disabled))

const value = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const type = computed(() => (props.field.type || 'text').toLowerCase())
const options = computed(() => props.field.options || [])
const numeric = computed(() => numberMetadata(props.field))
const showTextPreview = computed(() => shouldPreviewField(props.field))
const previewVariables = computed(() => props.field.previewVariables || props.field.preview?.variables || {})
const sliderValue = computed(() => {
  const raw = value.value
  const number = Number(raw)
  if (raw === '' || raw == null || !Number.isFinite(number)) return numeric.value.min
  return Math.min(numeric.value.max, Math.max(numeric.value.min, number))
})
function setExactNumber(event) {
  const raw = event.target.value
  value.value = raw === '' ? '' : Number(raw)
}

const numberValue = computed({
  get: () => (value.value === '' || value.value === null || value.value === undefined ? undefined : Number(value.value)),
  set: (v) => (value.value = v)
})

const boolValue = computed({
  get: () => value.value === true || value.value === 'true' || value.value === 1 || value.value === '1',
  set: (v) => (value.value = v)
})

const dateValue = computed({
  get: () => {
    const v = value.value
    if (v === null || v === undefined || v === '' || Number(v) <= 0) return null
    return /^\d+$/.test(String(v)) ? Number(v) : new Date(v).getTime()
  },
  set: (v) => (value.value = v === null || v === undefined ? null : Number(v))
})

const durationPreview = computed(() => {
  if (value.value === null || value.value === undefined || value.value === '') return ''
  const ms = typeof value.value === 'number' ? value.value : parseDuration(value.value)
  if (ms === null) return t('格式无效（示例：30m、1d12h、2w，permanent 表示永久）')
  return '= ' + formatDuration(ms)
})

const textValue = computed({
  get: () => (value.value === null || value.value === undefined ? '' : String(value.value)),
  set: (v) => (value.value = v)
})
</script>

<template>
  <div v-if="type === 'number' && numeric.bounded" class="bounded-number">
    <div class="number-controls">
      <input class="number-slider" type="range" :disabled="formDisabled" :value="sliderValue" :min="numeric.min" :max="numeric.max" :step="numeric.step" :aria-label="t('{label}滑块', { label: t(field.label || '数值') })" @input="value = Number($event.target.value)" />
      <input class="exact-number" type="number" :disabled="formDisabled" :value="value ?? ''" :min="numeric.min" :max="numeric.max" :step="numeric.step" :placeholder="t(field.placeholder)" :aria-label="t(field.label || '精确数值')" @input="setExactNumber" />
    </div>
    <div class="hint">{{ t('范围 {min}–{max} · 步长 {step}；空值或越界输入不会自动修改。', { min: numeric.min, max: numeric.max, step: numeric.step }) }}</div>
  </div>
  <el-input-number v-else-if="type === 'number'" v-model="numberValue" :step="numeric.step" :placeholder="t(field.placeholder)" controls-position="right" style="width: 100%" />
  <el-switch v-else-if="type === 'bool' || type === 'boolean'" v-model="boolValue" :aria-label="t(field.label)" />
  <el-select v-else-if="type === 'select'" v-model="value" :placeholder="t(field.placeholder || '请选择')" filterable clearable style="width: 100%">
    <el-option v-for="o in options" :key="o.value" :label="t(o.label)" :value="o.value" />
  </el-select>
  <div v-else-if="type === 'textarea'" class="text-field">
    <el-input v-model="textValue" type="textarea" :rows="4" :placeholder="t(field.placeholder)" :aria-label="t(field.label)" />
    <MinecraftTextPreview v-if="showTextPreview" :text="textValue" :variables="previewVariables" />
  </div>
  <el-input v-else-if="type === 'password'" v-model="textValue" type="password" show-password :placeholder="t(field.placeholder)" autocomplete="new-password" />
  <el-input v-else-if="type === 'player'" v-model="textValue" :placeholder="t(field.placeholder || '玩家名或 UUID')">
    <template #prefix>
      <img v-if="textValue" :src="avatarUrl(textValue, 16)" style="width: 16px; height: 16px; border-radius: 2px" @error="(e) => (e.target.style.display = 'none')" @load="(e) => (e.target.style.display = '')" />
      <Icon v-else name="user" :size="14" />
    </template>
  </el-input>
  <div v-else-if="type === 'duration'" style="width: 100%">
    <el-input v-model="textValue" :placeholder="t(field.placeholder || '如 30m、1d12h、2w、permanent')">
      <template #prefix><Icon name="hourglass" :size="14" /></template>
    </el-input>
    <div class="hint">{{ durationPreview }}</div>
  </div>
  <el-date-picker v-else-if="type === 'datetime' || type === 'date'" v-model="dateValue" :type="type === 'date' ? 'date' : 'datetime'" value-format="x" :placeholder="t(field.placeholder || '选择时间')" style="width: 100%" />
  <CodeEditor v-else-if="type === 'code'" v-model="textValue" :height="260" :placeholder="t(field.placeholder)" style="width: 100%" />
  <div v-else class="text-field">
    <el-input v-model="textValue" :placeholder="t(field.placeholder)" :aria-label="t(field.label)" />
    <MinecraftTextPreview v-if="showTextPreview" :text="textValue" :variables="previewVariables" />
  </div>
</template>

<style scoped>
.text-field,.bounded-number { width:100%; min-width:0; }
.number-controls { display:flex; align-items:center; gap:14px; }
.number-slider { flex:1; min-width:0; accent-color:var(--el-color-primary); }
.exact-number { box-sizing:border-box; width:112px; max-width:45%; padding:8px 10px; border:1px solid var(--el-border-color); border-radius:5px; background:var(--lbr-surface); color:var(--lbr-text); font:inherit; font-size:12px; }
.exact-number:focus-visible,.number-slider:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:3px; }
.exact-number:invalid { border-color:var(--el-color-warning); }
.hint { font-size: 11px; color: var(--lbr-text-soft); line-height: 1.5; margin-top: 4px; }
:deep(.el-input__wrapper), :deep(.el-select__wrapper), :deep(.el-textarea__inner) { border-radius: 5px; background: var(--lbr-surface); box-shadow: 0 0 0 1px var(--el-border-color) inset; transition: box-shadow 180ms; }
:deep(.el-input__wrapper), :deep(.el-select__wrapper) { min-height: 32px; }
:deep(.el-input__inner), :deep(.el-select__wrapper) { font-size: 12px; }
:deep(.el-textarea__inner) { font-size: 12px; line-height: 1.6; padding: 8px 10px; }
:deep(.el-input__wrapper.is-focus), :deep(.el-select__wrapper.is-focused), :deep(.el-textarea__inner:focus) { box-shadow: 0 0 0 1px var(--el-color-primary) inset, 0 0 0 3px var(--el-color-primary-light-9); }
:deep(.el-input-number) { --el-input-border-radius: 5px; }
:deep(.el-switch) { --el-switch-on-color: var(--el-color-primary); --el-switch-off-color: var(--el-border-color-darker); }
:deep(.el-switch:focus-visible) { outline: 2px solid var(--el-color-primary); outline-offset: 3px; border-radius: 12px; }
@media (prefers-reduced-motion: reduce) { :deep(*) { transition: none !important; animation: none !important; } }
</style>
