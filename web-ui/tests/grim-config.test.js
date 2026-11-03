import assert from 'node:assert/strict'
import { test } from 'node:test'
import { copyConfigValue, grimChanges, grimFieldError, grimFieldGroups, parseGrimSnapshot, parsePunishmentCommand, updatePunishmentCommand } from '../src/utils/grim-config.js'

test('punishment commands preserve valid original values and accept intervals larger than thresholds', () => {
  assert.deepEqual(parsePunishmentCommand('20:40 [alert]'), { threshold: 20, interval: 40, command: '[alert]' })
  assert.deepEqual(parsePunishmentCommand('100:0 kick %player% Too many violations'), { threshold: 100, interval: 0, command: 'kick %player% Too many violations' })
  assert.equal(updatePunishmentCommand('20:40 [alert]', 'interval', 0), '20:0 [alert]')
  assert.equal(updatePunishmentCommand('legacy unparsed command', 'interval', 0), 'legacy unparsed command')
})

test('invalid punishment rows remain invalid rather than being silently replaced', () => {
  for (const raw of ['wrong', '1:1 ', '-1:1 [alert]', '1:0.5 [log]', '2147483648:1 [log]', '1:1 [alert]\nstop', '1:1 \0']) {
    assert.equal(parsePunishmentCommand(raw), null, raw)
    assert.ok(grimFieldError({ type: 'punishment-commands' }, [raw]), raw)
  }
  assert.equal(grimFieldError({ type: 'punishment-commands' }, ['20:40 [alert]', '1:0 [log]']), '')
})

test('list patches preserve blank entries, order, and original array ownership', () => {
  const fields = [{ key: 'profile', type: 'string-list' }]
  const original = { profile: ['', '%player%', '', 'footer'] }
  const draft = { profile: copyConfigValue(original.profile) }
  assert.notEqual(draft.profile, original.profile)
  assert.deepEqual(grimChanges(fields, draft, original), {})
  draft.profile.splice(1, 1)
  const changes = grimChanges(fields, draft, original)
  assert.deepEqual(changes, { profile: ['', '', 'footer'] })
  changes.profile.push('other')
  assert.deepEqual(draft.profile, ['', '', 'footer'])
  assert.deepEqual(original.profile, ['', '%player%', '', 'footer'])
})

test('secrets are omitted until explicitly edited, including an explicit empty replacement', () => {
  const fields = [{ key: 'password', type: 'text', secret: true }, { key: 'mode', type: 'text', readOnly: true }]
  const original = { password: '', mode: 'V2' }
  assert.deepEqual(grimChanges(fields, { password: '', mode: 'V3' }, original), {})
  assert.deepEqual(grimChanges(fields, { password: '', mode: 'V3' }, original, { password: true }), { password: '' })
  assert.deepEqual(grimChanges(fields, { password: 'replacement', mode: 'V3' }, original, { password: true }), { password: 'replacement' })
})

test('snapshots require a consistent file id and field schema and never retain returned secrets', () => {
  const input = {
    id: 'database', fingerprint: 'rev',
    fields: [{ key: 'password', label: '数据库密码', type: 'text', secret: true, configured: true }, { key: 'lines', label: '消息', type: 'string-list' }],
    values: { password: 'must-not-render', lines: ['', 'line'] }
  }
  const parsed = parseGrimSnapshot(input, 'database')
  assert.equal(parsed.values.password, '')
  assert.deepEqual(parsed.values.lines, ['', 'line'])
  assert.notEqual(parsed.values.lines, input.values.lines)
  assert.throws(() => parseGrimSnapshot(input, 'config'))
  assert.throws(() => parseGrimSnapshot({ ...input, fingerprint: '' }, 'database'))
  assert.throws(() => parseGrimSnapshot({ ...input, values: 'invalid' }, 'database'))
  assert.throws(() => parseGrimSnapshot({ ...input, fields: [...input.fields, input.fields[0]] }, 'database'))
  assert.throws(() => parseGrimSnapshot({ ...input, fields: [{ key: 'password', label: '密码', type: 'unsupported' }] }, 'database'))
})

test('Chinese names, raw keys, and groups are searchable without changing field order', () => {
  const fields = [
    { key: 'Simulation.threshold', label: '移动偏差阈值', group: '移动模拟' },
    { key: 'Reach.threshold', label: '攻击距离阈值', group: '攻击检测' },
    { key: 'Simulation.max-advantage', label: '最大累计优势', group: '移动模拟' }
  ]
  assert.deepEqual(grimFieldGroups(fields, '移动').map(group => group.fields.map(field => field.key)), [['Simulation.threshold', 'Simulation.max-advantage']])
  assert.deepEqual(grimFieldGroups(fields, 'REACH').map(group => group.fields[0].key), ['Reach.threshold'])
  assert.deepEqual(grimFieldGroups(fields, '阈值', '攻击检测').map(group => group.fields[0].key), ['Reach.threshold'])
})

test('client validation catches nonfinite numbers, wrong types, and out-of-range values', () => {
  assert.ok(grimFieldError({ type: 'number' }, Infinity))
  assert.ok(grimFieldError({ type: 'number', min: 0, max: 1 }, -1))
  assert.ok(grimFieldError({ type: 'number', step: 1 }, 1.5))
  assert.equal(grimFieldError({ type: 'number', min: 0, max: 1, step: 0.001 }, 0.001), '')
  assert.ok(grimFieldError({ type: 'bool' }, 'true'))
  assert.ok(grimFieldError({ type: 'select', options: [{ value: 'BLOCKING' }] }, 'FAST'))
  assert.equal(grimFieldError({ type: 'string-list' }, ['', 'vanilla']), '')
})
