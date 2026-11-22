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
const trends = computed(() => {
  const d = data.value
  if (!d || !d.time) return []
  const memPct = d.memoryMax > 0 ? Math.round((d.memoryUsed / d.memoryMax) * 100) : 0
  return [
    {
      key: 'tps', label: 'TPS', max: 20, value: d.tps ? Number(d.tps[0]).toFixed(1) : '-',
      valueColor: d.tps ? tpsColor(d.tps[0]) : undefined, color: 'var(--el-color-success)',
      format: v => Number(v).toFixed(1),
      detail: d.tps ? d.tps.map(v => Number(v).toFixed(1)).join(' / ') + t(' · 1 / 5 / 15 分钟') : ''
    },
    {
      key: 'mspt', label: 'MSPT', value: formatMspt(d.mspt),
      color: 'var(--el-color-warning)', format: formatMspt
    },
    {
      key: 'memory', label: '内存', max: d.memoryMax || null, value: `${memPct}%`, format: formatBytes,
      detail: `${formatBytes(d.memoryUsed)} / ${formatBytes(d.memoryMax)}`
    },
    {
      key: 'cpu', label: 'CPU', max: 100, value: formatPercent(d.cpuProcess),
      color: 'var(--lc-focus-ink)', format: formatPercent,
      detail: t('系统 {percent} · {cores} 核', { percent: d.cpuSystem >= 0 ? formatPercent(d.cpuSystem) : '-', cores: d.cores })
    },
    { key: 'online', label: '在线玩家', value: `${d.online} / ${d.maxPlayers}` }
  ].map(series => ({ ...series, values: history.value.map(point => point[series.key]) }))
})
const formatMspt = value => Number.isFinite(value) && value >= 0 ? value.toFixed(1) + ' ms' : 'N/A'
const formatPercent = value => Number.isFinite(value) && value >= 0 ? value.toFixed(1) + '%' : 'N/A'
</script>

<template>
  <div class="page dashboard-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('运行总览') }}</h1>
      <div class="page-actions">
        <span v-if="data?.tps?.[0] < 18 && !error" class="status-label" :style="{ color: tpsColor(data.tps[0]) }">
          <Icon name="activity" :size="14" />{{ t(data.tps[0] >= 15 ? '性能波动' : '负载较高') }}
        </span>
        <span v-if="data?.uptime != null" class="uptime" :title="t('运行时长')">
          <Icon name="clock" :size="14" /><span :aria-label="`${t('运行时长')} ${uptimeDisplay}`">{{ uptimeDisplay }}</span>
        </span>
        <el-tooltip :content="t('刷新')"><el-button :aria-label="t('刷新')" :loading="loading" @click="load"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
      </div>
    </header>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="load-error" />
    <el-skeleton v-if="!data && !error" :rows="6" animated />
    <el-tabs v-if="trends.length" v-model="tab">
      <el-tab-pane :label="t('性能趋势')" name="performance">
        <section class="trend-grid" :class="{ 'lc-pane-in': tab === 'performance' }" :aria-label="t('性能趋势')">
          <article v-for="series in trends" :key="series.key" class="trend-sample" :aria-labelledby="`trend-${series.key}`">
            <header class="trend-heading">
              <h2 :id="`trend-${series.key}`">{{ t(series.label) }}</h2>
              <strong :style="{ color: series.valueColor }">{{ series.value }}</strong>
            </header>
            <Sparkline :height="108" :values="series.values" :min="0" :max="series.max" :color="series.color || 'var(--lc-accent)'" :format="series.format" />
            <p v-if="series.detail" class="trend-detail">{{ series.detail }}</p>
          </article>
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
  </div>
</template>

<style scoped>
.page-header { align-items: center; }
.page-header .el-button { width: 34px; padding: 0; }
.load-error { margin-bottom: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 12px; color: var(--lc-text-soft); }
.status-label, .uptime { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
.uptime { color: var(--lc-text-soft); font-variant-numeric: tabular-nums; }
.trend-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, max(238px, (100% - 48px) / 3)), 1fr)); gap: 24px; padding-block: 6px 14px; }
.trend-sample { min-width: 0; padding: 12px 0 0; border-top: 1px solid var(--lc-line); }
.trend-heading { display: grid; gap: 3px; margin-bottom: 12px; }
.trend-heading h2 { margin: 0; color: var(--lc-text-soft); font-size: 13px; font-weight: 500; }
.trend-heading strong { font-size: 25px; line-height: 1.25; font-weight: 620; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.trend-detail { margin: 5px 0 0; color: var(--lc-text-soft); font-size: 11px; overflow-wrap: anywhere; }
.server-summary { max-width: 740px; margin: 0; }
.server-summary > div { display: grid; grid-template-columns: minmax(94px, 1fr) 3fr; gap: 16px; padding-block: 12px; border-bottom: 1px solid var(--lc-line); }
.server-summary dt { color: var(--lc-text-soft); }
.server-summary dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
