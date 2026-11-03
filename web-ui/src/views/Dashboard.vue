<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, socket } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatBytes, formatDuration } from '../utils/format'
import Sparkline from '../components/Sparkline.vue'
import { locale, t } from '../i18n'

const data = ref(null)
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
const uptimeValue = ref('-')
watch([data, liveUptime, locale], () => {
  if (!data.value || data.value.uptime == null || uptimeBase.value == null) { uptimeValue.value = '-'; return }
  const seconds = Math.floor(liveUptime.value / 1000)
  const day = Math.floor(seconds / 86400), hour = Math.floor(seconds % 86400 / 3600), minute = Math.floor(seconds % 3600 / 60), second = seconds % 60
  uptimeValue.value = `${day ? day + t('天') : ''}${hour ? hour + t('小时') : ''}${minute ? minute + t('分') : ''}${!day && !hour && second ? second + t('秒') : ''}` || t('0秒')
}, { immediate: true })

async function load() {
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
    if (d.history) history.value = d.history
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
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
    { label: t('在线玩家'), value: `${d.online} / ${d.maxPlayers}`, hint: t('当前在线 / 人数上限') },
    { label: t('内存使用率'), value: `${memPct}%`, hint: `${formatBytes(d.memoryUsed)} / ${formatBytes(d.memoryMax)}` },
    { label: 'CPU', value: d.cpuProcess >= 0 ? d.cpuProcess.toFixed(1) + '%' : 'N/A', hint: t('系统 {percent} · {cores} 核', { percent: d.cpuSystem >= 0 ? d.cpuSystem.toFixed(1) + '%' : '-', cores: d.cores }) },
    { label: t('运行时长'), value: uptimeValue, hint: t('服务器进程') }
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
      <div><p class="eyebrow">{{ t('服务器监控') }}</p><h1 class="page-title">{{ t('运行总览') }}</h1><p v-if="data?.serverVersion" class="page-sub">{{ data.serverVersion }} · LunarCore {{ data.coreVersion }} · Java {{ data.javaVersion }} · {{ data.os }}</p></div>
      <el-button @click="load"><Icon name="refresh-cw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
    </header>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="load-error" />
    <el-skeleton v-if="!data && !error" :rows="6" animated />
    <template v-if="cards.length">
      <TransitionGroup name="metric" tag="section" appear class="metric-strip" :aria-label="t('关键运行指标')">
        <div v-for="(c, index) in cards" :key="index" class="metric" :style="{ '--metric-order': index }"><span>{{ c.label }}</span><strong :style="c.color ? { color: c.color } : {}">{{ c.value }}</strong><small>{{ c.hint }}</small></div>
      </TransitionGroup>
      <section class="card performance">
        <div class="section-head"><h2>{{ t('性能趋势') }}</h2><span class="status-label" :style="{ color: tpsColor(data.tps?.[0] ?? 0) }"><i />{{ data.tps ? (data.tps[0] >= 18 ? t('运行正常') : data.tps[0] >= 15 ? t('性能波动') : t('负载较高')) : t('暂无采样') }}</span></div>
        <div class="trend-grid">
          <div class="trend"><h3>TPS <span>{{ t('目标 20') }}</span></h3><Sparkline :values="series.tps" :min="0" :max="20" color="var(--el-color-primary)" :format="(v) => Number(v).toFixed(1)" /></div>
          <div class="trend"><h3>MSPT <span>{{ t('每刻耗时') }}</span></h3><Sparkline :values="series.mspt" :min="0" color="var(--el-color-warning)" :format="(v) => v < 0 ? 'N/A' : Number(v).toFixed(1) + ' ms'" /></div>
          <div class="trend"><h3>{{ t('内存') }} <span>{{ cards[3].hint }}</span></h3><Sparkline :values="series.memory" :min="0" :max="data.memoryMax || null" color="var(--el-color-primary)" :format="formatBytes" /></div>
          <div class="trend"><h3>CPU <span>{{ t('进程使用率') }}</span></h3><Sparkline :values="series.cpu" :min="0" :max="100" color="var(--el-color-success)" :format="(v) => v < 0 ? 'N/A' : Number(v).toFixed(1) + '%'" /></div>
        </div>
      </section>
      <section class="environment">
        <div class="card worlds-panel"><div class="section-head"><h2>{{ t('世界与区块') }}</h2><span>{{ t('{worlds} 个世界 · {chunks} 个区块', { worlds: data.worldCount, chunks: data.chunks }) }}</span></div><el-table v-if="data.worlds" :data="data.worlds" size="small" max-height="280"><el-table-column prop="name" :label="t('名称')" min-width="120" /><el-table-column prop="environment" :label="t('类型')" width="100" /><el-table-column prop="players" :label="t('玩家')" width="65" /><el-table-column prop="entities" :label="t('实体')" width="75" /><el-table-column prop="chunks" :label="t('区块')" width="75" /></el-table></div>
        <aside class="card service-panel"><div class="section-head"><h2>{{ t('服务概况') }}</h2><Icon name="server" :size="16" /></div><dl><div><dt>{{ t('实体总数') }}</dt><dd>{{ data.entities }}</dd></div><div><dt>{{ t('Bukkit 插件') }}</dt><dd>{{ data.plugins }}</dd></div><div><dt>{{ t('LC 模块') }}</dt><dd>{{ data.modules }}</dd></div></dl><h3>{{ t('在线人数趋势') }}</h3><Sparkline :values="series.online" :min="0" color="var(--el-color-primary)" /></aside>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page{color:var(--lbr-text);min-width:0}.page-header{margin-bottom:20px;gap:12px}.page-header>div{min-width:0}.eyebrow{font-size:11px;color:var(--lbr-text-soft);margin:0 0 5px;letter-spacing:.08em}.page-title{font-size:25px;letter-spacing:-.5px}.page-sub{margin:6px 0 0;overflow-wrap:anywhere}.load-error{margin-bottom:16px}.metric-strip{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));border:1px solid var(--lbr-sidebar-border);border-radius:8px;background:var(--lbr-surface);margin-bottom:16px;overflow:hidden}.metric{padding:16px;min-width:0;border-right:1px solid var(--lbr-sidebar-border)}.metric-enter-active{transition:opacity 220ms ease,transform 480ms cubic-bezier(.2,1.24,.34,1);transition-delay:calc(var(--metric-order) * 40ms)}.metric-enter-from{opacity:0;transform:translateY(8px) scale(.98)}.metric:last-child{border-right:0}.metric span,.metric small{display:block;color:var(--lbr-text-soft);font-size:12px}.metric strong{display:block;font-size:23px;line-height:1.4;font-weight:600;margin:5px 0;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.metric small{font-size:11px;line-height:1.6;overflow-wrap:anywhere}.card{padding:18px;border-radius:8px}.performance,.environment{animation:section-enter 520ms cubic-bezier(.2,1.24,.34,1) both}.performance{animation-delay:110ms}.environment{animation-delay:180ms}@keyframes section-enter{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}.section-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px;min-width:0}.section-head h2{font-size:14px;font-weight:600;margin:0}.section-head>span{font-size:12px;color:var(--lbr-text-soft)}.status-label{display:inline-flex;align-items:center;gap:6px;white-space:nowrap}.status-label i{width:6px;height:6px;border-radius:50%;background:currentColor}.trend-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px}.trend{min-width:0}.trend h3,.service-panel h3{margin:0 0 10px;font-size:12px;font-weight:500}.trend h3 span{display:block;margin-top:3px;font-size:11px;font-weight:400;color:var(--lbr-text-soft)}.environment{display:grid;grid-template-columns:minmax(0,2fr) minmax(240px,1fr);gap:16px;margin-top:16px}.environment .card+.card{margin-top:0}.worlds-panel{min-width:0;overflow:hidden}.service-panel dl{margin:0 0 18px}.service-panel dl>div{display:flex;justify-content:space-between;border-bottom:1px solid var(--el-border-color-lighter);padding:8px 0;font-size:13px}.service-panel dt{color:var(--lbr-text-soft)}.service-panel dd{margin:0;font-variant-numeric:tabular-nums}.service-panel :deep(.spark svg){height:72px!important}.dashboard-page :deep(.legend){gap:4px;flex-wrap:wrap;font-size:10px}.dashboard-page :deep(.legend span){overflow-wrap:anywhere}.dashboard-page :deep(.spark .empty){font-size:11px}@media(max-width:1200px){.metric-strip{grid-template-columns:repeat(3,minmax(0,1fr))}.metric:nth-child(3){border-right:0}.metric:nth-child(-n+3){border-bottom:1px solid var(--lbr-sidebar-border)}.trend-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:720px){.environment{grid-template-columns:1fr}.metric-strip{grid-template-columns:repeat(2,minmax(0,1fr))}.metric{padding:12px;border-right:0;border-bottom:1px solid var(--lbr-sidebar-border)}.metric:nth-child(odd){border-right:1px solid var(--lbr-sidebar-border)}.metric:nth-last-child(-n+2){border-bottom:0}.metric strong{font-size:21px}.card{padding:14px}.trend-grid{gap:18px}.section-head{flex-wrap:wrap}.page-title{font-size:23px}}
@media(prefers-reduced-motion:reduce){.metric-enter-active{transition:none}.performance,.environment{animation:none}}
</style>
