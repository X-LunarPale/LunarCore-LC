<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, socket } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatBytes, formatDateTime, formatDuration } from '../utils/format'
import { STALE_SAMPLE_MS, isMetricValue, isSampleTime, mergeHistory, sampleStatus } from '../utils/dashboard-history'
import { useAuth } from '../stores/auth'
import Sparkline from '../components/Sparkline.vue'
import { localeTag, t } from '../i18n'

const auth = useAuth()
const data = ref(null)
const tab = ref('performance')
const loading = ref(false)
const clock = ref(performance.now())
const serverClock = ref(null)
const history = ref([])
const error = ref('')
const previewTime = ref(null)
const selectedTime = ref(null)
const activeTime = computed(() => {
  const time = previewTime.value ?? selectedTime.value
  return history.value.some(sample => sample.time === time) ? time : null
})
const timeFormat = computed(() => new Intl.DateTimeFormat(localeTag.value, {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
}))
const formatTime = time => timeFormat.value.format(time)
const isoTime = time => new Date(time).toISOString()
const latestSample = computed(() => history.value.at(-1))
const latestTime = computed(() => latestSample.value?.time ?? null)
const serverTime = computed(() => {
  const now = clock.value
  const anchor = serverClock.value
  return anchor ? anchor.time + Math.max(0, now - anchor.receivedAt) : Date.now()
})
const freshness = computed(() => sampleStatus(latestTime.value, serverTime.value))
const updatedAt = computed(() => {
  if (latestTime.value == null) return ''
  const day = new Date(latestTime.value).toDateString()
  return day === new Date(serverTime.value).toDateString()
    ? formatTime(latestTime.value)
    : formatDateTime(latestTime.value)
})
const uptimeDisplay = computed(() => {
  const snapshot = data.value
  if (!isSampleTime(snapshot?.time) || !isMetricValue(snapshot.uptime)) return '-'
  const elapsed = Math.min(STALE_SAMPLE_MS, Math.max(0, serverTime.value - snapshot.time))
  return formatDuration(Math.floor(snapshot.uptime + elapsed))
})
let timer
let off
let loadSequence = 0

function selectTime(time) {
  previewTime.value = null
  selectedTime.value = time
}
watch(tab, () => { previewTime.value = null })
watch(history, samples => {
  if (selectedTime.value != null && !samples.some(sample => sample.time === selectedTime.value)) selectTime(null)
})

async function load() {
  if (loading.value) return
  loading.value = true
  const sequence = ++loadSequence
  try {
    const d = await api.get('/api/dashboard')
    if (sequence !== loadSequence) return
    const receivedAt = performance.now()
    if (isSampleTime(d?.serverTime)) {
      serverClock.value = { time: d.serverTime, receivedAt }
    }
    const samples = Array.isArray(d?.history) ? d.history : []
    const snapshot = isSampleTime(d?.time) ? {
      time: d.time, tps: d.tps?.[0], mspt: d.mspt, memory: d.memoryUsed, cpu: d.cpuProcess, online: d.online
    } : null
    if (samples.length || snapshot) {
      history.value = mergeHistory(history.value, snapshot ? [snapshot, ...samples] : samples)
    }
    data.value = d
    clock.value = receivedAt
    error.value = ''
  } catch (e) {
    if (sequence === loadSequence) error.value = e.message
  } finally { if (sequence === loadSequence) loading.value = false }
}

useAutoRefresh(load, { seconds: () => 2, immediate: true })
onMounted(() => {
  timer = setInterval(() => { clock.value = performance.now() }, 1000)
  off = socket.subscribe('dashboard', (point, backlog) => {
    if (backlog || !isSampleTime(point?.time)) return
    history.value = mergeHistory(history.value, [point])
    clock.value = performance.now()
  })
})
onUnmounted(() => {
  clearInterval(timer)
  loadSequence++
  off && off()
})

const tpsColor = (t) => (t >= 18 ? 'var(--el-color-success)' : t >= 15 ? 'var(--el-color-warning)' : 'var(--el-color-danger)')
const serverHealth = computed(() => {
  const tps = latestSample.value?.tps
  if (freshness.value === 'stale') return { label: '数据已过期', icon: 'triangle-alert', color: 'var(--el-color-warning)' }
  if (!isMetricValue(tps)) return { label: '暂无采样', icon: 'activity' }
  if (tps >= 18) return { label: '运行正常', icon: 'circle-check', color: tpsColor(tps) }
  return { label: tps >= 15 ? '性能波动' : '负载较高', icon: 'triangle-alert', color: tpsColor(tps) }
})
const trends = computed(() => {
  const d = data.value
  const point = latestSample.value
  if (!d || !point) return []
  const memPct = d.memoryMax > 0 && isMetricValue(point.memory) ? Math.round((point.memory / d.memoryMax) * 100) : null
  return [
    {
      key: 'tps', label: 'TPS', max: 20, value: isMetricValue(point.tps) ? point.tps.toFixed(1) : '-',
      valueColor: isMetricValue(point.tps) && freshness.value === 'fresh' ? tpsColor(point.tps) : undefined,
      color: 'var(--el-color-success)',
      format: v => Number(v).toFixed(1),
      detail: d.tps ? d.tps.map(v => Number(v).toFixed(1)).join(' / ') + t(' · 1 / 5 / 15 分钟') : ''
    },
    {
      key: 'mspt', label: 'MSPT', value: formatMspt(point.mspt),
      color: 'var(--el-color-warning)', format: formatMspt, detail: t('每刻耗时')
    },
    {
      key: 'memory', label: '内存', max: d.memoryMax || null, value: memPct == null ? '-' : `${memPct}%`, format: formatBytes,
      detail: `${formatBytes(point.memory)} / ${formatBytes(d.memoryMax)}`
    },
    {
      key: 'cpu', label: 'CPU', max: 100, value: formatPercent(point.cpu),
      color: 'var(--lc-focus-ink)', format: formatPercent,
      detail: t('系统 {percent} · {cores} 核', { percent: d.cpuSystem >= 0 ? formatPercent(d.cpuSystem) : '-', cores: d.cores })
    },
    { key: 'online', label: '在线玩家', value: `${point.online ?? '-'} / ${d.maxPlayers ?? '-'}`, detail: t('当前在线 / 人数上限') }
  ]
})
const formatMspt = value => isMetricValue(value) ? value.toFixed(1) + ' ms' : '-'
const formatPercent = value => isMetricValue(value) ? value.toFixed(1) + '%' : '-'
</script>

<template>
  <div class="page dashboard-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('运行总览') }}</h1>
      <el-tooltip :content="t('刷新')"><el-button :aria-label="t('刷新')" :loading="loading" @click="load"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
    </header>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="load-error" />
    <el-skeleton v-if="!data && !error" :rows="6" animated />
    <dl v-if="data || latestTime" class="overview-strip" :aria-label="t('关键运行指标')">
      <div class="health-summary">
        <dt class="visually-hidden">{{ t('服务器进程') }}</dt>
        <dd class="health-label" role="status" :style="{ color: serverHealth.color }">
          <Icon :name="serverHealth.icon" :size="16" />{{ t(serverHealth.label) }}
        </dd>
        <dd v-if="latestTime" class="sample-age">
          <span>{{ t('最后更新') }}</span>
          <time :datetime="isoTime(latestTime)" :title="formatDateTime(latestTime)">{{ updatedAt }}</time>
        </dd>
      </div>
      <div>
        <dt>{{ t('运行时长') }}</dt>
        <dd>{{ uptimeDisplay }}</dd>
      </div>
      <div v-if="data?.worldCount != null" class="world-summary">
        <dt class="visually-hidden">{{ t('世界与区块') }}</dt>
        <dd>
          <button type="button" class="overview-link" @click="tab = 'worlds'">
            <Icon name="globe" :size="16" />
            {{ t('{worlds} 个世界 · {chunks} 个区块', { worlds: data.worldCount, chunks: data.chunks }) }}
            <Icon name="chevron-right" :size="14" />
          </button>
        </dd>
      </div>
    </dl>
    <el-tabs v-if="trends.length" v-model="tab">
      <el-tab-pane :label="t('性能趋势')" name="performance">
        <section :class="{ 'lc-pane-in': tab === 'performance' }" :aria-label="t('性能趋势')">
          <div class="trend-timeline">
            <span v-if="activeTime">
              {{ t('采样时间') }} <time :datetime="isoTime(activeTime)" :title="formatDateTime(activeTime)">{{ formatTime(activeTime) }}</time>
            </span>
            <span v-else>
              <time :datetime="isoTime(history[0].time)" :title="formatDateTime(history[0].time)">{{ formatTime(history[0].time) }}</time>
              <template v-if="history.length > 1">
                – <time :datetime="isoTime(latestTime)" :title="formatDateTime(latestTime)">{{ formatTime(latestTime) }}</time>
              </template>
            </span>
            <button v-if="selectedTime != null" type="button" class="overview-link clear-selection" @click="selectTime(null)">{{ t('清除选择') }}</button>
          </div>
          <div class="trend-grid">
            <article v-for="series in trends" :key="series.key" class="trend-sample" :class="{ 'trend-sample-wide': series.key === 'online' }" :aria-labelledby="`trend-${series.key}`">
              <header class="trend-heading">
                <h2 :id="`trend-${series.key}`">
                  <RouterLink v-if="series.key === 'online' && auth.can('core.players.view')" :to="{ name: 'players' }" class="overview-link">
                    {{ t(series.label) }}<Icon name="chevron-right" :size="14" />
                  </RouterLink>
                  <template v-else>{{ t(series.label) }}</template>
                </h2>
                <strong :style="{ color: series.valueColor }">{{ series.value }}</strong>
              </header>
              <Sparkline :samples="history" :metric="series.key" :label="t(series.label)" :max="series.max"
                :color="series.color" :format="series.format" :format-time="formatTime"
                :active-time="activeTime" :selected-time="selectedTime"
                @preview="previewTime = $event" @select="selectTime" />
              <p v-if="series.detail" class="trend-detail">{{ series.detail }}</p>
            </article>
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
  </div>
</template>

<style scoped>
.dashboard-page { container-type: inline-size; }
.page-header { align-items: center; margin-bottom: 16px; }
.page-header .el-button { width: 34px; padding: 0; }
.load-error { margin-bottom: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 12px; color: var(--lc-text-soft); }
.overview-strip { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)) minmax(0, 1.25fr); align-items: center; gap: 8px 16px; margin: 0 0 12px; padding: 10px 16px; border-radius: var(--lc-radius-control); background: var(--lc-surface-muted); }
.overview-strip > div { min-width: 0; }
.overview-strip > div + div { padding-inline-start: 16px; border-inline-start: 1px solid var(--lc-line); }
.overview-strip dt { margin-bottom: 2px; color: var(--lc-text-soft); font-size: 12px; }
.overview-strip dd { display: flex; align-items: center; flex-wrap: wrap; gap: 0 6px; margin: 0; font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.overview-strip .sample-age { color: var(--lc-text-soft); font-size: 12px; font-weight: 400; margin-top: 2px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.overview-link { display: inline-flex; align-items: center; gap: 6px; min-width: 0; padding: 0; border: 0; color: inherit; background: transparent; font: inherit; text-align: start; text-decoration: none; cursor: pointer; overflow-wrap: anywhere; }
.overview-link > .lc-icon { flex: none; }
.overview-link:hover { color: var(--lc-text); text-decoration: underline; text-underline-offset: 3px; }
.overview-link > .lc-icon:last-child { color: var(--lc-text-soft); }
.trend-timeline { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 0 8px; min-height: 36px; color: var(--lc-text-soft); font-size: 12px; font-variant-numeric: tabular-nums; }
.clear-selection { min-height: 36px; padding: 0 4px; font-weight: 500; }
.trend-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px 16px; padding-block: 0 12px; }
.trend-sample { min-width: 0; padding-top: 8px; border-top: 1px solid var(--lc-line); }
.trend-heading { display: grid; gap: 2px; margin-bottom: 6px; }
.trend-heading h2 { margin: 0; color: var(--lc-text-soft); font-size: 13px; font-weight: 500; }
.trend-heading strong { font-size: 24px; line-height: 1.25; font-weight: 620; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.trend-detail { margin: 3px 0 0; color: var(--lc-text-soft); font-size: 12px; overflow-wrap: anywhere; }
.server-summary { max-width: 740px; margin: 0; }
.server-summary > div { display: grid; grid-template-columns: minmax(94px, 1fr) 3fr; gap: 16px; padding-block: 12px; border-bottom: 1px solid var(--lc-line); }
.server-summary dt { color: var(--lc-text-soft); }
.server-summary dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
@container (min-width: 310px) {
  .trend-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .trend-sample-wide { grid-column: span 2; }
}
@container (min-width: 660px) {
  .trend-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@container (min-width: 1050px) {
  .trend-grid { grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 18px 20px; }
  .trend-sample-wide { grid-column: auto; }
}
@container (max-width: 520px) {
  .overview-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 10px; padding: 8px 12px 0; }
  .overview-strip > .world-summary { grid-column: 1 / -1; padding: 0; border-inline-start: 0; border-top: 1px solid var(--lc-line); }
  .world-summary .overview-link, .clear-selection, .trend-timeline { min-height: 44px; }
  .trend-grid { --sparkline-height: 64px; gap: 12px 14px; }
  .trend-heading strong { font-size: 21px; }
}
</style>
