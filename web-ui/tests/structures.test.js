import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import { effective, families, inheritWorld, parseConfig, sameRules, setFlag } from '../src/components/structures/structures.js'

const fixture = () => ({ schema: 1, revision: 0, defaults: { structures: Object.fromEntries(families.map(f => [f.id, false])), overwriteExisting: false }, worlds: {} })

test('world overrides preserve explicit false and can restore inheritance', () => {
  const config = fixture()
  setFlag(config, '', 'witch_huts', true)
  assert.equal(effective(config, 'minecraft:overworld').witch_huts, true)
  setFlag(config, 'minecraft:overworld', 'witch_huts', false)
  assert.equal(effective(config, 'minecraft:overworld').witch_huts, false)
  assert.equal(effective(config, 'custom:resources').witch_huts, true)
  inheritWorld(config, 'minecraft:overworld', true)
  assert.equal(effective(config, 'minecraft:overworld').witch_huts, true)
  inheritWorld(config, 'minecraft:overworld', false)
  setFlag(config, '', 'witch_huts', false)
  assert.equal(effective(config, 'minecraft:overworld').witch_huts, true)
})

test('all nine switches and the overwrite switch are independent', () => {
  for (const field of [...families.map(f => f.id), 'overwriteExisting']) {
    const config = fixture()
    setFlag(config, '', field, true)
    assert.deepEqual(Object.entries(effective(config)).filter(([, v]) => v).map(([k]) => k), [field])
  }
})

test('configuration parsing supports Vue reactive drafts without mutating or aliasing them', () => {
  const config = reactive(fixture())
  setFlag(config, 'minecraft:overworld', 'dungeons', true)
  const parsed = parseConfig(config)
  assert.deepEqual(parsed, config)
  parsed.worlds['minecraft:overworld'].dungeons = false
  assert.equal(config.worlds['minecraft:overworld'].dungeons, true)
})

test('malformed and incomplete settings cannot be silently interpreted as vanilla', () => {
  for (const patch of [{ schema: 2 }, { revision: NaN }, { revision: -1 }, { revision: 1.1 }, { worlds: [] }, { extra: true }])
    assert.throws(() => parseConfig({ ...fixture(), ...patch }))
  for (const value of ['false', null, 0]) {
    const config = fixture()
    config.defaults.structures.witch_huts = value
    assert.throws(() => parseConfig(config))
  }
  const missing = fixture()
  delete missing.defaults.structures.end_island
  assert.throws(() => parseConfig(missing))
  const path = fixture()
  path.worlds['minecraft:../world'] = { dungeons: true }
  assert.throws(() => parseConfig(path))
  assert.throws(() => setFlag(fixture(), '', 'unknown', true))
})

test('dirty state ignores revisions and field ordering but detects changed inheritance', () => {
  const a = fixture(), b = fixture()
  b.revision = 10
  b.defaults.structures = Object.fromEntries(Object.entries(b.defaults.structures).reverse())
  assert.equal(sameRules(a, b), true)
  inheritWorld(b, 'minecraft:overworld', false)
  assert.equal(sameRules(a, b), false)
  inheritWorld(b, 'minecraft:overworld', true)
  assert.equal(sameRules(a, b), true)
})
