import assert from 'node:assert/strict'
import test from 'node:test'
import { checkSettings, effectiveEnabled, fromLocalTime, toLocalTime, validateSchedule } from '../src/components/maintenance/maintenance.js'

const schedule = { enabled: true, scheduleEnabled: true, scheduleStart: '2026-11-15T20:00:00+08:00', scheduleEnd: '2026-11-15T22:00:00+08:00' }

test('scheduled mode owns maintenance state at both boundaries', () => {
  assert.equal(effectiveEnabled(schedule, Date.parse('2026-11-15T11:59:59Z')), false)
  assert.equal(effectiveEnabled(schedule, Date.parse('2026-11-15T12:00:00Z')), true)
  assert.equal(effectiveEnabled(schedule, Date.parse('2026-11-15T14:00:00Z')), false)
  assert.equal(effectiveEnabled({ ...schedule, enabled: false }, Date.parse('2026-11-15T12:30:00Z')), true)
  assert.equal(effectiveEnabled({ ...schedule, scheduleEnabled: false }, Date.parse('2026-11-15T14:00:00Z')), true)
})

test('optional end and invalid dates have explicit behavior', () => {
  assert.equal(effectiveEnabled({ ...schedule, scheduleEnd: '' }, Date.parse('2027-01-01')), true)
  assert.throws(() => validateSchedule({ ...schedule, scheduleStart: '' }), /开始时间/)
  assert.throws(() => validateSchedule({ ...schedule, scheduleStart: 'invalid' }), /无效/)
  assert.throws(() => validateSchedule({ ...schedule, scheduleEnd: schedule.scheduleStart }), /晚于/)
  assert.throws(() => validateSchedule({ ...schedule, scheduleStart: '', scheduleEnabled: false }), /晚于/)
  assert.doesNotThrow(() => validateSchedule({ ...schedule, scheduleEnd: '' }))
  assert.throws(() => checkSettings(null, true), /数据格式异常/)
})

test('local datetime input preserves the selected instant across time zones', () => {
  const previousZone = process.env.TZ
  try {
    for (const zone of ['Asia/Singapore', 'America/New_York', 'UTC']) {
      process.env.TZ = zone
      const input = toLocalTime(schedule.scheduleStart)
      assert.match(input, /^\d{4}-\d\d-\d\dT\d\d:\d\d$/)
      assert.equal(Date.parse(fromLocalTime(input)), Date.parse(schedule.scheduleStart))
    }
    assert.equal(fromLocalTime(''), '')
    assert.equal(toLocalTime('invalid'), '')
  } finally {
    if (previousZone === undefined) delete process.env.TZ
    else process.env.TZ = previousZone
  }
})
