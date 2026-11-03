<script setup>
import { computed, ref } from 'vue'

// 轻量代码编辑器：行号 + Tab 缩进（插入两个空格）+ 等宽字体
const props = defineProps({
  modelValue: { type: String, default: '' },
  height: { type: [Number, String], default: 360 },
  readonly: Boolean,
  placeholder: String
})
const emit = defineEmits(['update:modelValue', 'save'])
const ta = ref(null)
const gutter = ref(null)

const lineCount = computed(() => (props.modelValue || '').split('\n').length)
const h = computed(() => (typeof props.height === 'number' ? props.height + 'px' : props.height))

function onInput(e) {
  emit('update:modelValue', e.target.value)
}

function onKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    emit('save')
    return
  }
  if (e.key === 'Tab' && !props.readonly) {
    e.preventDefault()
    const el = e.target
    const start = el.selectionStart
    const end = el.selectionEnd
    const v = el.value
    if (e.shiftKey) {
      const lineStart = v.lastIndexOf('\n', start - 1) + 1
      if (v.substr(lineStart, 2) === '  ') {
        const nv = v.slice(0, lineStart) + v.slice(lineStart + 2)
        emit('update:modelValue', nv)
        requestAnimationFrame(() => el.setSelectionRange(Math.max(lineStart, start - 2), Math.max(lineStart, end - 2)))
      }
      return
    }
    const nv = v.slice(0, start) + '  ' + v.slice(end)
    emit('update:modelValue', nv)
    requestAnimationFrame(() => el.setSelectionRange(start + 2, start + 2))
  }
}

function syncScroll() {
  if (gutter.value && ta.value) gutter.value.scrollTop = ta.value.scrollTop
}
</script>

<template>
  <div class="code-editor-wrap" :style="{ height: h }">
    <div ref="gutter" class="gutter"><div v-for="n in lineCount" :key="n">{{ n }}</div></div>
    <textarea ref="ta" class="code" spellcheck="false" :value="modelValue" :readonly="readonly" :placeholder="placeholder"
      @input="onInput" @keydown="onKeydown" @scroll="syncScroll" wrap="off" />
  </div>
</template>

<style scoped>
.code-editor-wrap {
  display: flex; border: 1px solid var(--el-border-color); border-radius: 8px; overflow: hidden;
  background: var(--el-fill-color-blank);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, 'Courier New', monospace; font-size: 13px; line-height: 20px;
}
.code-editor-wrap:focus-within { border-color: var(--el-color-primary); }
.gutter {
  overflow: hidden; padding: 8px 8px 8px 6px; text-align: right; min-width: 44px; user-select: none;
  color: var(--lbr-text-soft); background: var(--el-fill-color-light); border-right: 1px solid var(--el-border-color-lighter);
}
.code {
  flex: 1; border: none; outline: none; resize: none; padding: 8px 12px; margin: 0;
  font: inherit; line-height: inherit; color: var(--el-text-color-primary); background: transparent;
  white-space: pre; overflow: auto; tab-size: 2;
}
</style>
