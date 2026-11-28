<script setup>
import { computed, useId } from 'vue'
import { t } from '../i18n'
import { isMetricValue, metricSegments, sampleIndexAtPosition, samplePosition } from '../utils/dashboard-history'

const props = defineProps({
  samples: { type: Array, required: true },
  metric: { type: String, required: true },
  label: { type: String, required: true },
  max: { type: Number, default: null },
  color: { type: String, default: 'var(--lc-accent)' },
  format: { type: Function, default: String },
  formatTime: { type: Function, required: true },
  activeTime: { type: Number, default: null },
  selectedTime: { type: Number, default: null }
})
const emit = defineEmits(['preview', 'select'])

const WIDTH = 600
const HEIGHT = 108
const BASELINE = HEIGHT - 4
const gradientId = useId()
const start = computed(() => props.samples[0]?.time ?? 0)
const end = computed(() => props.samples.at(-1)?.time ?? 0)
const xOf = time => samplePosition(time, start.value, end.value) * WIDTH

const chart = computed(() => {
  const segments = metricSegments(props.samples, props.metric)
  const values = segments.flatMap(segment => segment.map(point => point.value))
  const minimum = values.length ? Math.min(...values) : null
  const maximum = values.length ? Math.max(...values) : null
  const scale = props.max > 0 ? props.max : Math.max(maximum ?? 0, 1)
  const paths = segments.map(segment => {
    const points = segment.map(({ time, value }) => [xOf(time), BASELINE - Math.min(1, value / scale) * (HEIGHT - 8)])
    const coordinates = points.map(point => point.join(',')).join(' ')
    return {
      points,
      coordinates,
      area: `${points[0][0]},${BASELINE} ${coordinates} ${points.at(-1)[0]},${BASELINE}`
    }
  })
  return { minimum, maximum, paths }
})

const activeIndex = computed(() => props.samples.findIndex(sample => sample.time === props.activeTime))
const controlIndex = computed(() => activeIndex.value >= 0 ? activeIndex.value : props.samples.length - 1)
const activeX = computed(() => activeIndex.value >= 0 ? xOf(props.activeTime) : null)
const readingAt = sample => isMetricValue(sample?.[props.metric]) ? props.format(sample[props.metric]) : t('暂无采样')
const controlText = computed(() => {
  const sample = props.samples[controlIndex.value]
  return sample ? `${props.formatTime(sample.time)} · ${readingAt(sample)}` : undefined
})
const sampleAtPointer = event => {
  const box = event.currentTarget.getBoundingClientRect()
  return box.width > 0 ? props.samples[sampleIndexAtPosition(props.samples, (event.clientX - box.left) / box.width)] : undefined
}

function previewPointer(event) {
  if (event.pointerType !== 'touch') emit('preview', sampleAtPointer(event)?.time ?? null)
}
function selectPointer(event) {
  const time = sampleAtPointer(event)?.time
  if (time != null) emit('select', props.selectedTime === time ? null : time)
}
function selectKey(event) {
  const last = props.samples.length - 1
  if (last < 0) return
  let index = controlIndex.value
  switch (event.key) {
    case 'ArrowLeft':
    case 'ArrowDown':
      index = Math.max(0, index - 1)
      break
    case 'ArrowRight':
    case 'ArrowUp':
      index = Math.min(last, index + 1)
      break
    case 'Home':
      index = 0
      break
    case 'End':
      index = last
      break
    case 'Escape':
      if (props.activeTime == null) return
      event.preventDefault()
      emit('select', null)
      return
    default: return
  }
  event.preventDefault()
  emit('select', props.samples[index].time)
}
</script>

<template>
  <div class="spark">
    <div class="spark-plot" :tabindex="samples.length ? 0 : undefined"
      :role="samples.length ? 'slider' : undefined" :aria-label="samples.length ? label : undefined"
      :aria-valuemin="samples.length ? 0 : undefined" :aria-valuemax="samples.length ? samples.length - 1 : undefined"
      :aria-valuenow="samples.length ? controlIndex : undefined" :aria-valuetext="controlText"
      @pointermove="previewPointer" @pointerleave="emit('preview', null)" @click="selectPointer"
      @focus="emit('preview', samples[controlIndex]?.time ?? null)" @blur="emit('preview', null)" @keydown="selectKey">
      <svg aria-hidden="true" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" preserveAspectRatio="none">
        <defs>
          <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" :stop-color="color" stop-opacity="0.13" />
            <stop offset="1" :stop-color="color" stop-opacity="0" />
          </linearGradient>
        </defs>
        <line x1="0" :x2="WIDTH" :y1="BASELINE" :y2="BASELINE" class="spark-baseline" vector-effect="non-scaling-stroke" />
        <g v-if="chart.paths.length" class="spark-series">
          <template v-for="path in chart.paths" :key="path.points[0][0]">
            <template v-if="path.points.length > 1">
              <polygon :points="path.area" :fill="`url(#${gradientId})`" />
              <polyline :points="path.coordinates" fill="none" :stroke="color" stroke-width="2" vector-effect="non-scaling-stroke" />
            </template>
            <line v-else :x1="path.points[0][0]" :x2="path.points[0][0]" :y1="path.points[0][1]" :y2="path.points[0][1]"
              :stroke="color" stroke-width="4" stroke-linecap="round" vector-effect="non-scaling-stroke" />
          </template>
        </g>
        <line v-if="activeX != null" :x1="activeX" :x2="activeX" y1="4" :y2="BASELINE" class="spark-cursor" vector-effect="non-scaling-stroke" />
      </svg>
      <span v-if="!chart.paths.length" class="spark-empty">{{ t(samples.length ? '暂无采样' : '数据采集中…') }}</span>
    </div>
    <div class="legend">
      <strong v-if="activeIndex >= 0">{{ readingAt(samples[activeIndex]) }}</strong>
      <template v-else-if="chart.minimum != null">
        <span>{{ t('最低') }} {{ format(chart.minimum) }}</span>
        <span>{{ t('最高') }} {{ format(chart.maximum) }}</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.spark-plot { position: relative; touch-action: pan-y pinch-zoom; border-radius: var(--lc-radius-control); }
.spark-plot[role="slider"] { cursor: crosshair; }
.spark-plot:focus-visible { outline-offset: -2px; }
.spark-plot svg { display: block; width: 100%; height: var(--sparkline-height, 108px); overflow: visible; }
.spark-baseline { stroke: var(--lc-line); }
.spark-cursor { stroke: var(--lc-focus-ink); stroke-width: 1.5; stroke-dasharray: 3 3; }
.spark-empty { position: absolute; inset: 0; display: grid; place-items: center; color: var(--lc-text-soft); font-size: 12px; pointer-events: none; }
.legend { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 2px 4px; min-height: 20px; margin-top: 4px; color: var(--lc-text-soft); font-size: 12px; font-variant-numeric: tabular-nums; }
.legend strong { color: var(--lc-text); font-weight: 600; }
</style>
