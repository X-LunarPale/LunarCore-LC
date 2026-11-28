export const STALE_SAMPLE_MS = 20_000
const HISTORY_LIMIT = 120

export const isSampleTime = value => Number.isFinite(value) && value > 0
export const isMetricValue = value => Number.isFinite(value) && value >= 0

export function mergeHistory(previous, incoming) {
  const byTime = new Map()
  for (const sample of [...previous, ...incoming]) {
    if (isSampleTime(sample?.time)) byTime.set(sample.time, sample)
  }
  return [...byTime.values()].sort((a, b) => a.time - b.time).slice(-HISTORY_LIMIT)
}

export function sampleStatus(sampleTime, currentTime) {
  if (!isSampleTime(sampleTime)) return 'empty'
  return currentTime - sampleTime >= STALE_SAMPLE_MS ? 'stale' : 'fresh'
}

export function samplePosition(time, start, end) {
  return end > start ? (time - start) / (end - start) : 0.5
}

export function sampleIndexAtPosition(samples, position) {
  if (!samples.length) return -1
  const start = samples[0].time
  const time = start + (samples.at(-1).time - start) * Math.min(1, Math.max(0, position))
  let nearest = 0
  for (let index = 1; index < samples.length; index++) {
    if (Math.abs(samples[index].time - time) < Math.abs(samples[nearest].time - time)) nearest = index
  }
  return nearest
}

export function metricSegments(samples, metric) {
  const segments = []
  let segment = []
  for (const sample of samples) {
    const value = sample[metric]
    const valid = isMetricValue(value)
    if (!valid || (segment.length && sample.time - segment.at(-1).time >= STALE_SAMPLE_MS)) {
      if (segment.length) segments.push(segment)
      segment = []
    }
    if (valid) segment.push({ time: sample.time, value })
  }
  if (segment.length) segments.push(segment)
  return segments
}
