<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../../api'
import { t, locale } from '../../i18n'
const props = defineProps({ text: { type: String, default: '' }, format: { type: String, default: 'message' } })
const emit = defineEmits(['resolved'])
const status = ref(null), players = ref([]), player = ref(''), busy = ref(false), error = ref(''), result = ref(null)
let revision = 0, disposed = false
const hasTokens = computed(() => /%[^%\s]+%/.test(props.text))
watch(() => [props.text, player.value], () => { revision++; result.value = null; error.value = ''; emit('resolved', null) })
async function load() {
  error.value = ''
  try { const r = await api.get('/api/text/preview'); if (!disposed) { status.value = r; players.value = r.players || [] } }
  catch (e) { if (!disposed) error.value = e.message }
}
async function resolve() {
  if (busy.value) return
  const current = ++revision; busy.value = true; error.value = ''
  try {
    const r = await api.post('/api/text/preview', { text: props.text, format: props.format, ...(player.value ? { player: player.value } : {}) })
    if (!disposed && current === revision) { result.value = r; emit('resolved', r) }
  } catch (e) { if (!disposed && current === revision) { error.value = e.message; emit('resolved', null) } }
  finally { if (!disposed) busy.value = false }
}
onBeforeUnmount(() => { disposed = true; revision++ })
</script>
<template>
  <div v-if="hasTokens" class="placeholder-preview">
    <div class="placeholder-actions"><el-select v-model="player" :placeholder="t('无玩家上下文')" clearable :disabled="busy" :aria-label="t('占位符预览玩家')" @visible-change="visible => visible && load()"><el-option v-for="p in players" :key="p.uuid" :value="p.uuid" :label="p.name" /></el-select><el-button :loading="busy" @click="resolve">{{ t('解析占位符') }}</el-button><el-button text :disabled="busy" @click="load">{{ t('刷新玩家') }}</el-button></div>
    <p>{{ t('PlaceholderAPI 占位符使用所选在线玩家解析；不选择玩家时，仅使用服务器上下文。') }}</p>
    <p v-if="status && !status.placeholderApiAvailable" role="status">{{ t('未连接 PlaceholderAPI，占位符将保留原文。') }}</p>
    <p v-if="result" role="status">{{ result.message }}<template v-if="result.unresolved?.length"> {{ t('未解析：{tokens}', { tokens: result.unresolved.join(locale === 'en_US' ? ', ' : '、') }) }}</template></p>
    <p v-if="error" class="placeholder-error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.placeholder-preview{padding-top:10px;font-size:11px;color:var(--lc-text-soft)}.placeholder-actions{display:flex;flex-wrap:wrap;gap:8px}.placeholder-actions :deep(.el-select){width:210px;max-width:100%}.placeholder-actions :deep(.el-button){margin:0}.placeholder-preview p{margin:6px 0;line-height:1.7;overflow-wrap:anywhere}.placeholder-error{color:var(--el-color-danger)}
</style>
