<script setup>
import SectionError from './SectionError.vue'
import { t } from '../../i18n'
import { onUnmounted, ref } from 'vue'
import { api, resolveEndpoint } from '../../api'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { latestRequest, panelText, requireSuccess } from '../../utils/panel'

const props = defineProps({ section: Object, namespace: String, active: { type: Boolean, default: true } })
const items = ref([])
const error = ref(null)
const loading = ref(false)
let pending = false
const begin = latestRequest()

async function load() {
  if (pending) return
  pending = true
  const current = begin()
  loading.value = items.value.length === 0
  try {
    const r = await api.get(resolveEndpoint(props.section.endpoint, props.namespace))
    if (!current()) return
    requireSuccess(r)
    if (!Array.isArray(r) && !Array.isArray(r?.items)) throw new Error(t('统计接口返回格式错误'))
    items.value = Array.isArray(r) ? r : r.items
    error.value = null
  } catch (e) {
    if (!current()) return
    error.value = { status: e.status, message: e.message }
  } finally {
    pending = false
    if (current()) loading.value = false
  }
}

useAutoRefresh(load, {
  seconds: () => Number.isFinite(Number(props.section.refreshSeconds)) && Number(props.section.refreshSeconds) >= 0 ? Number(props.section.refreshSeconds) : 5,
  namespace: props.namespace,
  active: () => props.active,
  immediate: true
})
onUnmounted(() => { begin() })
</script>

<template>
  <div class="stats-section" v-loading="loading">
    <h2 v-if="section.title" class="stats-heading">{{ t(section.title) }}</h2>
    <SectionError :error="error" />
    <el-button v-if="error" :disabled="loading" size="small" @click="load">{{ t('重试') }}</el-button>
    <el-empty v-if="!error && !loading && !items.length" :description="t('暂无统计数据')" :image-size="64" />
    <p v-if="error && items.length" class="stale-note" role="status">{{ t('当前显示上次成功读取的数据') }}</p>
    <div v-if="items.length" class="stat-grid">
      <div v-for="(s, i) in items" :key="i" class="stat-cell">
          <div class="stat-label" :title="panelText(s.label, s.labelKey)">{{ panelText(s.label, s.labelKey) }}</div>
          <div class="stat-value" :class="{ 'stat-value-text': String(panelText(s.value ?? '', s.valueKey)).length > 16 }" :title="String(panelText(s.value ?? '—', s.valueKey))">{{ panelText(s.value ?? '—', s.valueKey) }}</div>
          <div v-if="s.hintKey || s.hint || s.description" class="stat-hint" :title="panelText(s.hint || s.description, s.hintKey)">{{ panelText(s.hint || s.description, s.hintKey) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stats-section { color: var(--lc-text); }
.stats-heading { margin: 0 0 12px; font-size: 15px; font-weight: 600; }
.stat-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(min(164px,100%),1fr)); gap:0; border-top:1px solid var(--el-border-color-light); border-bottom:1px solid var(--el-border-color-light); }
.stat-cell { min-width: 0; padding: 14px 16px; }
.stat-cell + .stat-cell { border-left:1px solid var(--el-border-color-light); }
.stat-label { font-size: 11px; color: var(--lc-text-soft); line-height: 1.4; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.stat-value { margin-top:4px; font-size:20px; font-weight:600; letter-spacing:0; line-height:1.3; overflow-wrap:anywhere; color:var(--lc-text); font-variant-numeric:tabular-nums; }
.stat-value-text { font-size: 14px; line-height: 1.45; letter-spacing: 0; }
.stat-hint { font-size: 11px; color: var(--lc-text-soft); margin-top: 4px; line-height: 1.4; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.stale-note { margin:0 0 10px; color:var(--el-color-warning); font-size:12px; }
@media (max-width:600px) { .stat-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .stat-cell { padding:12px; } .stat-cell:nth-child(odd) { border-left:0; } .stat-cell:nth-child(n+3) { border-top:1px solid var(--el-border-color-light); } .stat-value { font-size:18px; } .stat-value-text { font-size:13px; } }
</style>
