<script setup>
import { computed } from 'vue'
import { t } from '../i18n'

// 轻量折线图（SVG），无需图表库
const props = defineProps({
  values: { type: Array, default: () => [] },
  min: { type: Number, default: null },
  max: { type: Number, default: null },
  height: { type: Number, default: 120 },
  color: { type: String, default: '#38bdf8' },
  format: { type: Function, default: (v) => v }
})

const W = 600

const stats = computed(() => {
  const vals = props.values.filter((v) => typeof v === 'number' && isFinite(v) && v >= 0)
  let lo = props.min ?? Math.min(...vals, 0)
  let hi = props.max ?? Math.max(...vals, 1)
  if (hi <= lo) hi = lo + 1
  return { lo, hi, vals }
})

const points = computed(() => {
  const n = props.values.length
  if (n < 2) return []
  const { lo, hi } = stats.value
  return props.values.map((v, i) => {
    const x = (i / (n - 1)) * W
    const y = props.height - ((Math.max(lo, Math.min(hi, Number(v) || 0)) - lo) / (hi - lo)) * (props.height - 8) - 4
    return [x, y]
  })
})

const line = computed(() => points.value.map((p) => p.join(',')).join(' '))
const area = computed(() => (points.value.length ? `0,${props.height} ${line.value} ${W},${props.height}` : ''))
const gid = 'g' + Math.random().toString(36).slice(2)
const last = computed(() => (props.values.length ? props.values[props.values.length - 1] : null))
</script>

<template>
  <div class="spark">
    <svg :viewBox="`0 0 ${W} ${height}`" preserveAspectRatio="none" :style="{ height: height + 'px' }">
      <defs>
        <linearGradient :id="gid" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" :stop-color="color" stop-opacity="0.35" />
          <stop offset="1" :stop-color="color" stop-opacity="0" />
        </linearGradient>
      </defs>
      <polygon v-if="area" :points="area" :fill="`url(#${gid})`" />
      <polyline v-if="line" :points="line" fill="none" :stroke="color" stroke-width="2" vector-effect="non-scaling-stroke" />
    </svg>
    <div class="legend">
      <span>{{ t('最低') }} {{ format(stats.vals.length ? Math.min(...stats.vals) : 0) }}</span>
      <span>{{ t('当前') }} <b>{{ last === null ? '-' : format(last) }}</b></span>
      <span>{{ t('最高') }} {{ format(stats.vals.length ? Math.max(...stats.vals) : 0) }}</span>
    </div>
    <div v-if="values.length < 2" class="empty">{{ t('数据采集中…') }}</div>
  </div>
</template>

<style scoped>
.spark { position: relative; }
svg { width: 100%; display: block; }
.legend { display: flex; justify-content: space-between; font-size: 12px; color: var(--lc-text-soft); margin-top: 6px; }
.empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--lc-text-soft); font-size: 13px; }
</style>
