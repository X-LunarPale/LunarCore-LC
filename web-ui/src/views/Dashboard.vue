<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, socket } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatBytes } from '../utils/format'
import Sparkline from '../components/Sparkline.vue'
import { t } from '../i18n'

const data = ref(null)
const tab = ref('performance')
const trend = ref('tps')
const loading = ref(false)
const clock = ref(performance.now())
const uptimeBase = ref(null)
const calibratedAt = ref(performance.now())
const liveUptime = computed(() => uptimeBase.value == null ? 0 : Math.max(0, uptimeBase.value + Math.max(0, clock.value - calibratedAt.value)))
const uptimeDisplay = computed(() => {
  const ms = liveUptime.value
  if (!data.value || data.value.uptime == null) return '-'
  const seconds = Math.floor(ms / 1000)
  const day = Math.floor(seconds / 86400), hour = Math.floor(seconds % 86400 / 3600), minute = Math.floor(seconds % 3600 / 60), second = seconds % 60
  return `${day ? day + t('天') : ''}${hour ? hour + t('小时') : ''}${minute ? minute + t('分') : ''}${!day && !hour && second ? second + t('秒') : ''}` || t('0秒')
})
const history = ref([])
const error = ref('')
let timer
let off
let loadSequence = 0
async function load() {
  if (loading.value) return
  loading.value = true
  const sequence = ++loadSequence
  try {
    const d = await api.get('/api/dashboard')
    if (sequence !== loadSequence) return
    const receivedAt = performance.now()
    const serverUptime = Number(d?.uptime)
    const previousUptime = uptimeBase.value == null ? null : uptimeBase.value + Math.max(0, receivedAt - calibratedAt.value)
    if (Number.isFinite(serverUptime) && serverUptime >= 0) {
      const processRestarted = previousUptime != null && serverUptime + 5000 < previousUptime
      if (previousUptime == null || processRestarted) {
        uptimeBase.value = serverUptime
        calibratedAt.value = receivedAt
      }
    }
    data.value = d
    clock.value = receivedAt
    if (Array.isArray(d.history)) history.value = d.history.slice(-120)
    error.value = ''
  } catch (e) {
    if (sequence === loadSequence) error.value = e.message
  } finally { if (sequence === loadSequence) loading.value = false }
}

useAutoRefresh(load, { seconds: () => 2, immediate: true })
onMounted(() => {
  timer = setInterval(() => { clock.value = performance.now() }, 1000)
  off = socket.subscribe('dashboard', (point, backlog) => {
    if (backlog || !point) return
    history.value = [...history.value.slice(-119), point]
  })
})
onUnmounted(() => {
  clearInterval(timer)
  loadSequence++
  off && off()
})

const tpsColor = (t) => (t >= 18 ? 'var(--el-color-success)' : t >= 15 ? 'var(--el-color-warning)' : 'var(--el-color-danger)')
const cards = computed(() => {
  const d = data.value
  if (!d || !d.time) return []
  const memPct = d.memoryMax > 0 ? Math.round((d.memoryUsed / d.memoryMax) * 100) : 0
  return [
    { label: 'TPS', value: d.tps ? Number(d.tps[0]).toFixed(1) : '-', color: tpsColor(d.tps?.[0] ?? 20) },
    { label: 'MSPT', value: d.mspt >= 0 ? Number(d.mspt).toFixed(1) + ' ms' : 'N/A' },
    { label: t('在线玩家'), value: `${d.online} / ${d.maxPlayers}` },
    { label: t('内存使用率'), value: `${memPct}%` },
    { label: 'CPU', value: d.cpuProcess >= 0 ? d.cpuProcess.toFixed(1) + '%' : 'N/A' },
    { label: t('运行时长'), value: uptimeDisplay.value }
  ]
})
const trendOptions = computed(() => [
  { label: 'TPS', value: 'tps' },
  { label: 'MSPT', value: 'mspt' },
  { label: t('内存'), value: 'memory' },
  { label: 'CPU', value: 'cpu' },
  { label: t('在线玩家'), value: 'online' }
])
const trendValues = computed(() => history.value.map((point) => point[trend.value]))
const trendConfig = computed(() => {
  const d = data.value
  if (trend.value === 'tps') return {
    max: 20, format: v => Number(v).toFixed(1),
    summary: d.tps ? d.tps.map(x => Number(x).toFixed(1)).join(' / ') + t(' · 1 / 5 / 15 分钟') : t('暂无采样')
  }
  if (trend.value === 'mspt') return {
    color: 'var(--el-color-warning)', format: v => v < 0 ? 'N/A' : Number(v).toFixed(1) + ' ms',
    summary: d.mspt >= 0 ? (d.mspt < 50 ? t('运行流畅') : t('负载较高')) : t('非 Paper 服务端')
  }
  if (trend.value === 'memory') return {
    max: d.memoryMax || null, format: formatBytes, summary: `${formatBytes(d.memoryUsed)} / ${formatBytes(d.memoryMax)}`
  }
  if (trend.value === 'cpu') return {
    max: 100, color: 'var(--el-color-success)', format: v => v < 0 ? 'N/A' : Number(v).toFixed(1) + '%',
    summary: t('系统 {percent} · {cores} 核', { percent: d.cpuSystem >= 0 ? d.cpuSystem.toFixed(1) + '%' : '-', cores: d.cores })
  }
  return {}
})
</script>

<template>
  <div class="page dashboard-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('运行总览') }}</h1>
      <div class="page-actions">
        <span v-if="data?.tps && !error" class="status-label" :style="{ color: tpsColor(data.tps[0]) }">
          <i aria-hidden="true" />{{ data.tps[0] >= 18 ? t('运行正常') : data.tps[0] >= 15 ? t('性能波动') : t('负载较高') }}
        </span>
        <el-tooltip :content="t('刷新')"><el-button :aria-label="t('刷新')" :loading="loading" @click="load"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
      </div>
    </header>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="load-error" />
    <el-skeleton v-if="!data && !error" :rows="6" animated />
    <template v-if="cards.length">
      <dl class="metric-strip" :aria-label="t('关键运行指标')">
        <div v-for="c in cards" :key="c.label" class="metric">
          <dt>{{ c.label }}</dt>
          <dd :style="c.color ? { color: c.color } : undefined">{{ c.value }}</dd>
        </div>
      </dl>
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('性能趋势')" name="performance">
          <section class="trend" :aria-label="t('性能趋势')">
            <el-segmented v-model="trend" :options="trendOptions" :aria-label="t('关键运行指标')" />
            <div :key="trend" class="trend-sample" :class="{ 'lc-pane-in': tab === 'performance' }">
              <p class="trend-summary">{{ trendConfig.summary }}</p>
              <Sparkline :height="176" :values="trendValues" :min="0" :max="trendConfig.max" :color="trendConfig.color || 'var(--lc-accent)'" :format="trendConfig.format" />
            </div>
          </section>
        </el-tab-pane>
        <el-tab-pane :label="t('世界与区块')" name="worlds" lazy :class="{ 'lc-pane-in': tab === 'worlds' }">
          <div class="section-head"><span>{{ t('{worlds} 个世界 · {chunks} 个区块', { worlds: data.worldCount, chunks: data.chunks }) }}</span><span>{{ t('实体总数') }} {{ data.entities }}</span></div>
          <el-table v-if="data.worlds" :data="data.worlds" max-height="480"><el-table-column prop="name" :label="t('名称')" min-width="180" /><el-table-column prop="environment" :label="t('类型')" min-width="140" /><el-table-column prop="players" :label="t('玩家')" width="100" /><el-table-column prop="entities" :label="t('实体')" width="100" /><el-table-column prop="chunks" :label="t('区块')" width="100" /></el-table>
        </el-tab-pane>
        <el-tab-pane :label="t('服务端')" name="server" lazy :class="{ 'lc-pane-in': tab === 'server' }">
          <dl class="server-summary">
            <div><dt>{{ t('服务端') }}</dt><dd>{{ data.serverVersion || '-' }}</dd></div>
            <div><dt>LunarCore</dt><dd>{{ data.coreVersion || '-' }}</dd></div>
            <div><dt>Java</dt><dd>{{ data.javaVersion || '-' }}</dd></div>
            <div><dt>{{ t('操作系统') }}</dt><dd>{{ data.os || '-' }}</dd></div>
            <div><dt>{{ t('Bukkit 插件') }}</dt><dd>{{ data.plugins }}</dd></div>
            <div><dt>{{ t('LC 模块') }}</dt><dd>{{ data.modules }}</dd></div>
          </dl>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<style scoped>
.page-header { align-items: center; }
.page-header .el-button { width: 34px; padding: 0; }
.load-error { margin-bottom: 16px; }
.metric-strip { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 0 16px; margin: 0 0 12px; }
.metric { padding: 14px 0; min-width: 0; border-top: 1px solid var(--lc-line); }
.metric dt { color: var(--lc-text-soft); font-size: 12px; overflow-wrap: anywhere; }
.metric dd { margin: 4px 0 0; font-size: 22px; font-weight: 600; line-height: 1.4; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.section-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 12px; color: var(--lc-text-soft); }
.status-label { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
.status-label i { width: 6px; height: 6px; background: currentColor; border-radius: 50%; }
.trend { min-width: 0; padding-block: 4px 14px; }
.trend .el-segmented { max-width: 100%; }
.trend-summary { margin: 12px 0 8px; min-height: 1.55em; color: var(--lc-text-soft); font-size: 12px; }
.trend-sample :deep(stop:first-child) { stop-opacity: .13; }
.server-summary { max-width: 740px; margin: 0; }
.server-summary > div { display: grid; grid-template-columns: minmax(94px, 1fr) 3fr; gap: 16px; padding-block: 12px; border-bottom: 1px solid var(--lc-line); }
.server-summary dt { color: var(--lc-text-soft); }
.server-summary dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.dashboard-page :deep(.legend) { flex-wrap: wrap; gap: 6px; font-size: 11px; }
@media (max-width: 1220px) { .metric-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 560px) {
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric { padding-block: 9px; }
  .metric dd { font-size: 20px; }
  .trend :deep(.el-segmented__item) { padding-inline: 7px; }
}
</style>
