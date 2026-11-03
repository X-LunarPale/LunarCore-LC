<script setup>
import { computed, ref, watch } from 'vue'
import PlaceholderPreview from './PlaceholderPreview.vue'
import { parseMinecraftText } from './minecraft-text'
import { t, locale } from '../../i18n'

const props = defineProps({
  text: { type: [String, Number], default: '' },
  lines: { type: Array, default: null },
  variables: { type: Object, default: () => ({}) },
  label: { type: String, default: '颜色文本预览' },
  legacy: { type: Boolean, default: true },
  atomicVariables: { type: Boolean, default: false },
  placeholderApi: { type: Boolean, default: true }
})
const source = computed(() => (props.lines || [props.text]).join('\n'))
const resolved = ref(null)
watch(source, () => { resolved.value = null })
const parsed = computed(() => (resolved.value !== null ? resolved.value.text.split('\n') : props.lines || [props.text]).map(line => parseMinecraftText(line, { ...props.variables, ...resolved.value?.variables }, { legacy: props.legacy, atomicVariables: props.atomicVariables })))
const warnings = computed(() => [...new Set(parsed.value.flatMap(line => line.warnings))])
const unresolved = computed(() => [...new Set(parsed.value.flatMap(line => line.unresolved))])
const unresolvedText = computed(() => unresolved.value.map(key => `{${key}}`).join(locale.value === 'en_US' ? ', ' : '、'))
</script>

<template>
  <div class="minecraft-preview" role="group" :aria-label="t(label)">
    <div class="preview-label">{{ t(label) }}<span v-if="resolved !== null"> · {{ t('已解析占位符') }}</span></div>
    <PlaceholderPreview v-if="placeholderApi" :text="source" :format="atomicVariables ? 'motd' : 'message'" @resolved="resolved = $event" />
    <div class="preview-surface">
      <div v-for="(line, index) in parsed" :key="index" class="preview-line"><template v-if="line.segments.length"><span v-for="(segment, part) in line.segments" :key="part" :style="segment.style" :class="{ 'preview-variable': segment.variable }">{{ segment.text }}</span></template><span v-else class="empty-line">{{ t('（空行）') }}</span></div>
    </div>
    <p class="preview-help">{{ t('支持常用 MiniMessage 颜色、渐变、彩虹与文字样式。') }}<template v-if="legacy">{{ t('支持 & / § 格式码。') }}</template><template v-else>{{ t('此处仅解析 MiniMessage，& / § 格式码按原文显示。') }}</template>{{ t('效果仅供参考，以游戏内显示为准。') }}</p>
    <p v-if="unresolved.length" class="preview-help">{{ t('未提供实际值的变量保留为示意：{variables}。', { variables: unresolvedText }) }}</p>
    <p v-for="warning in warnings" :key="warning" class="preview-warning" role="note">{{ t(warning) }}</p>
  </div>
</template>

<style scoped>
.minecraft-preview { min-width:0; margin-top:12px; }
.preview-label { color:var(--lbr-text-soft); font-size:11px; line-height:1.5; margin-bottom:6px; }
.preview-surface { background:#20242c; border:1px solid #414855; border-radius:7px; padding:12px 14px; color:#fff; font-family:ui-monospace,SFMono-Regular,Consolas,monospace; font-size:13px; line-height:1.7; overflow-wrap:anywhere; white-space:pre-wrap; tab-size:4; }
.preview-line { min-height:1.7em; }
.empty-line { color:#a3aab6; font-size:12px; }
.preview-variable { text-underline-offset:3px; }
.preview-help,.preview-warning { font-size:11px; line-height:1.65; margin:5px 0 0; color:var(--lbr-text-soft); }
.preview-warning { color:var(--el-color-warning); }
</style>
