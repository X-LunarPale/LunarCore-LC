<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, socket } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatBytes } from '../utils/format'
import Sparkline from '../components/Sparkline.vue'
import { t } from '../i18n'

const data = ref(null)
const tab = ref('performance')
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
    { label: 'TPS', value: d.tps ? Number(d.tps[0]).toFixed(1) : '-', hint: d.tps ? d.tps.map((x) => Number(x).toFixed(1)).join(' / ') + t(' · 1 / 5 / 15 分钟') : t('暂无采样'), color: tpsColor(d.tps?.[0] ?? 20) },
    { label: 'MSPT', value: d.mspt >= 0 ? Number(d.mspt).toFixed(1) + ' ms' : 'N/A', hint: d.mspt >= 0 ? (d.mspt < 50 ? t('运行流畅') : t('负载较高')) : t('非 Paper 服务端') },
    { label: t('在线玩家'), value: `${d.online} / ${d.maxPlayers}` },
    { label: t('内存使用率'), value: `${memPct}%`, hint: `${formatBytes(d.memoryUsed)} / ${formatBytes(d.memoryMax)}` },
    { label: 'CPU', value: d.cpuProcess >= 0 ? d.cpuProcess.toFixed(1) + '%' : 'N/A', hint: t('系统 {percent} · {cores} 核', { percent: d.cpuSystem >= 0 ? d.cpuSystem.toFixed(1) + '%' : '-', cores: d.cores }) },
    { label: t('运行时长'), value: uptimeDisplay.value }
  ]
})
const series = computed(() => ({
  tps: history.value.map((p) => p.tps),
  mspt: history.value.map((p) => p.mspt),
  online: history.value.map((p) => p.online),
  memory: history.value.map((p) => p.memory),
  cpu: history.value.map((p) => p.cpu)
}))
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
          <dd>
            <strong :style="c.color ? { color: c.color } : undefined">{{ c.value }}</strong>
            <small v-if="c.hint">{{ c.hint }}</small>
          </dd>
        </div>
      </dl>
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('性能趋势')" name="performance">
          <section class="trend-grid" :aria-label="t('性能趋势')">
            <div class="trend"><h2>TPS</h2><Sparkline :height="100" :values="series.tps" :min="0" :max="20" color="var(--el-color-primary)" :format="(v) => Number(v).toFixed(1)" /></div>
            <div class="trend"><h2>MSPT</h2><Sparkline :height="100" :values="series.mspt" :min="0" color="var(--el-color-warning)" :format="(v) => v < 0 ? 'N/A' : Number(v).toFixed(1) + ' ms'" /></div>
            <div class="trend"><h2>{{ t('内存') }}</h2><Sparkline :height="100" :values="series.memory" :min="0" :max="data.memoryMax || null" color="var(--el-color-primary)" :format="formatBytes" /></div>
            <div class="trend"><h2>CPU</h2><Sparkline :height="100" :values="series.cpu" :min="0" :max="100" color="var(--el-color-success)" :format="(v) => v < 0 ? 'N/A' : Number(v).toFixed(1) + '%'" /></div>
            <div class="trend online-trend"><h2>{{ t('在线人数趋势') }}</h2><Sparkline :height="80" :values="series.online" :min="0" color="var(--el-color-primary)" /></div>
          </section>
        </el-tab-pane>
        <el-tab-pane :label="t('世界与区块')" name="worlds" lazy>
          <div class="section-head"><span>{{ t('{worlds} 个世界 · {chunks} 个区块', { worlds: data.worldCount, chunks: data.chunks }) }}</span><span>{{ t('实体总数') }} {{ data.entities }}</span></div>
          <el-table v-if="data.worlds" :data="data.worlds" max-height="480"><el-table-column prop="name" :label="t('名称')" min-width="180" /><el-table-column prop="environment" :label="t('类型')" min-width="140" /><el-table-column prop="players" :label="t('玩家')" width="100" /><el-table-column prop="entities" :label="t('实体')" width="100" /><el-table-column prop="chunks" :label="t('区块')" width="100" /></el-table>
        </el-tab-pane>
        <el-tab-pane :label="t('服务端')" name="server" lazy>
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
.metric-strip { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); margin: 0 0 22px; border-block: 1px solid var(--lc-line); }
.metric { padding: 16px 18px; min-width: 0; border-right: 1px solid var(--el-border-color-light); }
.metric:first-child { padding-left: 0; }
.metric:last-child { border-right: 0; }
.metric dt, .metric small { display: block; color: var(--lc-text-soft); font-size: 12px; }
.metric dd { margin: 0; }
.metric strong { display: block; margin: 4px 0; font-size: 22px; font-weight: 600; line-height: 1.4; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.metric small { font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.section-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 12px; color: var(--lc-text-soft); }
.status-label { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
.status-label i { width: 6px; height: 6px; background: currentColor; border-radius: 50%; }
.trend-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 32px; }
.trend { min-width: 0; padding-block: 6px 14px; border-bottom: 1px solid var(--lc-line); }
.trend h2 { margin: 0 0 8px; font-size: 13px; font-weight: 600; }
.online-trend { grid-column: 1 / -1; }
.server-summary { max-width: 740px; margin: 0; }
.server-summary > div { display: grid; grid-template-columns: minmax(94px, 1fr) 3fr; gap: 16px; padding-block: 12px; border-bottom: 1px solid var(--lc-line); }
.server-summary dt { color: var(--lc-text-soft); }
.server-summary dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.dashboard-page :deep(.legend) { flex-wrap: wrap; gap: 6px; font-size: 11px; }
@media (max-width: 1200px) { .metric-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }.metric:nth-child(3) { border-right: 0; }.metric:nth-child(4) { padding-left: 0; }.metric:nth-child(-n+3) { border-bottom: 1px solid var(--el-border-color-light); } }
@media (max-width: 650px) { .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-bottom: 18px; }.metric { padding: 12px; border-right: 0; border-bottom: 1px solid var(--el-border-color-light); }.metric:nth-child(odd) { padding-left: 0; border-right: 1px solid var(--el-border-color-light); }.metric:nth-child(even) { padding-left: 12px; }.metric:nth-last-child(-n+2) { border-bottom: 0; }.metric strong { font-size: 20px; }.trend-grid { grid-template-columns: 1fr; gap: 16px; } }
</style>
