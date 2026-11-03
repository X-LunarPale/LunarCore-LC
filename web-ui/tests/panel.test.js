import assert from 'node:assert/strict'
import { test } from 'node:test'
import { groupFields, latestRequest, missingRequired, panelEnum, panelText, requireSuccess, rowActionVisible, selectValue, presentFields } from '../src/utils/panel.js'
import { setLocale } from '../src/i18n.js'

test('world patch fields preserve false/zero, omit blank numeric fields, and map boolean selects', () => {
  const field = { type: 'select', options: [{ value: 'true' }, { value: 'false' }] }
  assert.equal(selectValue(field, false), 'false')
  assert.equal(selectValue(field, true), 'true')
  assert.deepEqual(presentFields({ time: null, spawnX: undefined, pvp: 'false', count: 0 }), { pvp: 'false', count: 0 })
})

test('business failures retain their error instead of becoming success', () => {
  assert.throws(() => requireSuccess({ ok: false, error: 'denied' }), /denied/)
  assert.throws(() => requireSuccess({ ok: false, message: 'failed' }), /failed/)
  assert.deepEqual(requireSuccess({ ok: true }), { ok: true })
})
test('required fields accept zero/false and reject whitespace', () => {
  const fields = [{ key: 'value', label: '值', required: true }]
  for (const value of [0, false, 'false']) assert.equal(missingRequired(fields, { value }), undefined)
  for (const value of [null, undefined, '', '   ']) assert.equal(missingRequired(fields, { value }), fields[0])
})
test('late response cannot replace latest search data', () => {
  const begin = latestRequest()
  const old = begin(), newest = begin()
  assert.equal(old(), false)
  assert.equal(newest(), true)
})

test('field groups retain every field and preserve the original field definitions', () => {
  const fields = [
    { key: 'singular', group: '货币显示' },
    { key: 'initial', group: '账户规则' },
    { key: 'plural', group: '货币显示' },
    { key: 'extension' }
  ]
  const groups = groupFields(fields)
  assert.deepEqual(groups.map(group => group.title), ['货币显示', '账户规则', ''])
  assert.deepEqual(groups.map(group => group.fields.map(field => field.key)), [['singular', 'plural'], ['initial'], ['extension']])
  assert.equal(groups[0].fields[0], fields[0])
  assert.equal(groups.flatMap(group => group.fields).length, fields.length)
  assert.deepEqual(fields.map(field => field.key), ['singular', 'initial', 'plural', 'extension'])
})

test('legacy forms remain a single group without requiring metadata', () => {
  const fields = [{ key: 'amount', defaultValue: 0 }, { key: 'enabled', defaultValue: false }]
  assert.deepEqual(groupFields(fields), [{ title: '', fields }])
  assert.deepEqual(groupFields(undefined), [])
})

test('only explicit panel text and enum metadata translate server values', () => {
  setLocale('en_US')
  try {
    assert.equal(panelText('设置'), '设置')
    assert.equal(panelText('设置', '设置'), 'Settings')
    assert.equal(panelText(0), 0)
    assert.equal(panelText(false, ''), false)
    assert.equal(panelEnum('设置'), '设置')
    assert.equal(panelEnum('settings', { settings: '设置' }), 'Settings')
    assert.equal(panelEnum('玩家建筑', { settings: '设置' }), '玩家建筑')
    assert.equal(panelEnum('toString', {}), 'toString')
    assert.equal(panelEnum(false, { false: '否' }), 'No')
  } finally {
    setLocale('zh_CN')
  }
})

test('row actions keep legacy visibility and use original status values for explicit rules', () => {
  assert.equal(rowActionVisible({}, { status: 'WITHDRAWN' }), true)
  const action = { visibleWhenField: 'status', visibleWhenValues: ['PENDING', 'APPROVED', 'REJECTED'] }
  for (const status of action.visibleWhenValues) assert.equal(rowActionVisible(action, { status }), true)
  for (const status of ['WITHDRAWN', 'Published', undefined, null]) {
    assert.equal(rowActionVisible(action, { status }), false)
  }
  assert.equal(rowActionVisible({ visibleWhenField: 'status' }, { status: 'PENDING' }), false)
})
