import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import { commonFlags, effective, families, inheritWorld, inheritWorlds, parseConfig, sameRules, setFlag, setFlags } from '../src/components/structures/structures.js'

const fixture = () => ({ schema: 1, revision: 0, defaults: { structures: Object.fromEntries(families.map(f => [f.id, false])) }, worlds: {} })

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

test('all nine switches are independent', () => {
  for (const field of families.map(f => f.id)) {
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

test('the removed overwrite switch is not accepted back', () => {
  const config = fixture()
  config.defaults.overwriteExisting = true
  assert.throws(() => parseConfig(config))
  assert.throws(() => setFlag(fixture(), '', 'overwriteExisting', true))
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

test('a change applies to every selected world and leaves the others alone', () => {
  const config = fixture()
  setFlag(config, '', 'dungeons', true)
  setFlags(config, ['minecraft:a', 'minecraft:b'], 'witch_huts', true)
  for (const world of ['minecraft:a', 'minecraft:b']) assert.equal(effective(config, world).witch_huts, true)
  assert.equal(effective(config, 'minecraft:c').witch_huts, false)
  assert.equal(config.defaults.structures.witch_huts, false)
  setFlag(config, '', 'dungeons', false)
  assert.equal(effective(config, 'minecraft:a').dungeons, true, 'selected worlds stop following the defaults')
})

test('no selected worlds edits the server defaults', () => {
  const config = fixture()
  setFlags(config, [], 'strongholds', true)
  assert.equal(config.defaults.structures.strongholds, true)
  assert.deepEqual(config.worlds, {})
})

test('selected worlds report a shared value or null when they differ', () => {
  const config = fixture()
  setFlags(config, ['minecraft:a'], 'mineshafts', true)
  assert.equal(commonFlags(config, ['minecraft:a', 'minecraft:b']).mineshafts, null)
  assert.equal(commonFlags(config, ['minecraft:a', 'minecraft:b']).dungeons, false)
  assert.equal(commonFlags(config, ['minecraft:a']).mineshafts, true)
  assert.equal(commonFlags(config, []).mineshafts, false)
  setFlags(config, ['minecraft:a', 'minecraft:b'], 'mineshafts', false)
  assert.equal(commonFlags(config, ['minecraft:a', 'minecraft:b']).mineshafts, false)
})

test('inheritance changes every selected world together', () => {
  const config = fixture()
  inheritWorlds(config, ['minecraft:a', 'minecraft:b'], false)
  assert.deepEqual(Object.keys(config.worlds), ['minecraft:a', 'minecraft:b'])
  inheritWorlds(config, ['minecraft:a', 'minecraft:b'], true)
  assert.deepEqual(config.worlds, {})
})
