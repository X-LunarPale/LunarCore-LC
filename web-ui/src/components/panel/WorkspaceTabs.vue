<script setup>
import { t } from '../../i18n'

const props = defineProps({
  modelValue: { type: String, required: true },
  items: { type: Array, required: true },
  label: { type: String, default: '内容分区' },
  id: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

function move(event, index) {
  const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  if (!delta && event.key !== 'Home' && event.key !== 'End') return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? props.items.length - 1
    : (index + delta + props.items.length) % props.items.length
  emit('update:modelValue', props.items[next].id)
  event.currentTarget.parentElement.querySelectorAll('[role="tab"]')[next]?.focus()
}
</script>

<template>
  <nav class="workspace-tabs" role="tablist" :aria-label="t(label)">
    <button v-for="(item, index) in items" :id="id ? `${id}-tab-${item.id}` : undefined" :key="item.id"
      type="button" role="tab" :aria-selected="modelValue === item.id" :aria-controls="item.panelId || undefined"
      :tabindex="modelValue === item.id ? 0 : -1" :class="{ active: modelValue === item.id }"
      @click="emit('update:modelValue', item.id)" @keydown="move($event, index)">
      <Icon v-if="item.icon" :name="item.icon" :size="15" />
      <span>{{ t(item.label) }}</span>
      <span v-if="item.count != null" class="tab-count">{{ item.count }}</span>
      <span v-if="item.dirty" class="tab-dirty" :aria-label="t('尚未保存')" />
    </button>
  </nav>
</template>

<style scoped>
.workspace-tabs { display:flex; align-items:stretch; gap:4px; max-width:100%; margin:18px 0 22px; border-bottom:1px solid var(--el-border-color-light); overflow-x:auto; scrollbar-width:thin; }
button { display:flex; flex:0 0 auto; align-items:center; gap:7px; min-height:42px; padding:10px 13px; border:0; border-bottom:2px solid transparent; border-radius:0; background:transparent; color:var(--el-text-color-regular); font-size:13px; white-space:nowrap; cursor:pointer; transition:color 160ms,border-color 160ms; }
button.active { color:var(--el-color-primary); border-bottom-color:var(--el-color-primary); font-weight:600; }
button:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:-3px; }
.tab-count { padding:0 5px; min-width:18px; border-radius:4px; background:var(--el-fill-color-light); color:var(--lc-text-soft); font-size:11px; text-align:center; font-variant-numeric:tabular-nums; font-weight:400; }
.tab-dirty { width:5px; height:5px; border-radius:50%; background:var(--el-color-warning); }
@media (hover:hover) { button:hover { color:var(--el-color-primary); background:var(--el-fill-color-light); } }
@media (max-width:600px) { .workspace-tabs { margin:14px 0 18px; } button { padding-inline:10px; } }
@media (prefers-reduced-motion:reduce) { button { transition:none; } }
</style>
