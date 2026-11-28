import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  STALE_SAMPLE_MS, mergeHistory, metricSegments, sampleIndexAtPosition, samplePosition, sampleStatus
} from '../src/utils/dashboard-history.js'

test('a late dashboard response keeps newer WebSocket samples and deduplicates timestamps', () => {
  const first = { time: 1000, tps: 19.8 }
  const second = { time: 6000, tps: 19 }
  const third = { time: 11000, tps: 20 }
  const changed = { time: 6000, tps: 19.1 }
  assert.deepEqual(mergeHistory([second, third], [first, changed, { time: null }]), [first, changed, third])
  const history = Array.from({ length: 125 }, (_, index) => ({ time: index + 1 }))
  assert.deepEqual(mergeHistory([], history), history.slice(-120))
})

test('a successful poll with unchanged sampling time still goes stale', () => {
  assert.equal(sampleStatus(null, 1000), 'empty')
  assert.equal(sampleStatus(1000, 1000 + STALE_SAMPLE_MS - 1), 'fresh')
  assert.equal(sampleStatus(1000, 1000 + STALE_SAMPLE_MS), 'stale')
  assert.equal(sampleStatus(1000 + STALE_SAMPLE_MS, 1000 + STALE_SAMPLE_MS), 'fresh')
})

test('chart coordinates and nearest-sample selection use elapsed time between irregular samples', () => {
  const samples = [{ time: 1000 }, { time: 6000 }, { time: 16000 }]
  assert.equal(samplePosition(6000, 1000, 16000), 1 / 3)
  assert.equal(samplePosition(1000, 1000, 1000), 0.5)
  assert.equal(sampleIndexAtPosition(samples, 0.3), 1)
  assert.equal(sampleIndexAtPosition(samples, 0.8), 2)
  assert.equal(sampleIndexAtPosition([], 0.5), -1)
})

test('invalid metrics and prolonged sampling gaps break the line while valid zero remains visible', () => {
  const samples = [
    { time: 1000, cpu: 8 },
    { time: 6000, cpu: null },
    { time: 11000, cpu: 0 },
    { time: 16000, cpu: -100 },
    { time: 21000, cpu: 10 },
    { time: 26000, cpu: 9 },
    { time: 26000 + STALE_SAMPLE_MS, cpu: 12 }
  ]
  assert.deepEqual(metricSegments(samples, 'cpu'), [
    [{ time: 1000, value: 8 }],
    [{ time: 11000, value: 0 }],
    [{ time: 21000, value: 10 }, { time: 26000, value: 9 }],
    [{ time: 26000 + STALE_SAMPLE_MS, value: 12 }]
  ])
})
