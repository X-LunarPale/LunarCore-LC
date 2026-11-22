<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, socket } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatBytes } from '../utils/format'
import { useAuth } from '../stores/auth'
import Sparkline from '../components/Sparkline.vue'
import { t } from '../i18n'

const auth = useAuth()
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
const serverHealth = computed(() => {
  const tps = data.value?.tps?.[0]
  if (!Number.isFinite(tps) || error.value) return { label: '暂无采样', icon: 'activity' }
  if (tps >= 18) return { label: '运行正常', icon: 'circle-check', color: tpsColor(tps) }
  return { label: tps >= 15 ? '性能波动' : '负载较高', icon: 'triangle-alert', color: tpsColor(tps) }
})
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
      color: 'var(--el-color-warning)', format: formatMspt, detail: t('每刻耗时')
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
    { key: 'online', label: '在线玩家', value: `${d.online} / ${d.maxPlayers}`, detail: t('当前在线 / 人数上限') }
  ].map(series => ({ ...series, values: history.value.map(point => point[series.key]) }))
})
const formatMspt = value => Number.isFinite(value) && value >= 0 ? value.toFixed(1) + ' ms' : 'N/A'
const formatPercent = value => Number.isFinite(value) && value >= 0 ? value.toFixed(1) + '%' : 'N/A'
</script>

<template>
  <div class="page dashboard-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('运行总览') }}</h1>
      <el-tooltip :content="t('刷新')"><el-button :aria-label="t('刷新')" :loading="loading" @click="load"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
    </header>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="load-error" />
    <el-skeleton v-if="!data && !error" :rows="6" animated />
    <dl v-if="trends.length" class="overview-strip" :aria-label="t('关键运行指标')">
      <div>
        <dt>{{ t('服务器进程') }}</dt>
        <dd :style="{ color: serverHealth.color }"><Icon :name="serverHealth.icon" :size="16" />{{ t(serverHealth.label) }}</dd>
      </div>
      <div>
        <dt>{{ t('运行时长') }}</dt>
        <dd><Icon name="clock" :size="16" />{{ uptimeDisplay }}</dd>
      </div>
      <div>
        <dt>{{ t('世界与区块') }}</dt>
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
        <section class="trend-grid" :class="{ 'lc-pane-in': tab === 'performance' }" :aria-label="t('性能趋势')">
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
.dashboard-page { container-type: inline-size; }
.page-header { align-items: center; }
.page-header .el-button { width: 34px; padding: 0; }
.load-error { margin-bottom: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 12px; color: var(--lc-text-soft); }
.overview-strip { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)) minmax(0, 1.4fr); gap: 16px; margin: 0 0 18px; padding: 14px 16px; border-radius: var(--lc-radius-control); background: var(--lc-surface-muted); }
.overview-strip > div { min-width: 0; }
.overview-strip > div + div { padding-inline-start: 16px; border-inline-start: 1px solid var(--lc-line); }
.overview-strip dt { margin-bottom: 5px; color: var(--lc-text-soft); font-size: 12px; }
.overview-strip dd { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.overview-link { display: inline-flex; align-items: center; gap: 6px; min-width: 0; padding: 0; border: 0; color: inherit; background: transparent; font: inherit; text-align: start; text-decoration: none; cursor: pointer; overflow-wrap: anywhere; }
.overview-link:hover { color: var(--lc-text); text-decoration: underline; text-underline-offset: 3px; }
.overview-link > .lc-icon:last-child { color: var(--lc-text-soft); }
.trend-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 24px; padding-block: 6px 14px; }
.trend-sample { min-width: 0; padding: 12px 0 0; border-top: 1px solid var(--lc-line); }
.trend-heading { display: grid; gap: 3px; margin-bottom: 12px; }
.trend-heading h2 { margin: 0; color: var(--lc-text-soft); font-size: 13px; font-weight: 500; }
.trend-heading strong { font-size: 25px; line-height: 1.25; font-weight: 620; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.trend-detail { margin: 5px 0 0; color: var(--lc-text-soft); font-size: 11px; overflow-wrap: anywhere; }
.server-summary { max-width: 740px; margin: 0; }
.server-summary > div { display: grid; grid-template-columns: minmax(94px, 1fr) 3fr; gap: 16px; padding-block: 12px; border-bottom: 1px solid var(--lc-line); }
.server-summary dt { color: var(--lc-text-soft); }
.server-summary dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
@container (min-width: 440px) {
  .trend-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .trend-sample-wide { grid-column: span 2; }
}
@container (min-width: 660px) {
  .trend-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@container (max-width: 460px) {
  .overview-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .overview-strip > div:last-child { grid-column: 1 / -1; padding: 10px 0 0; border-inline-start: 0; border-top: 1px solid var(--lc-line); }
}
</style>
