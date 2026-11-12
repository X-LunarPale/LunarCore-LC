import assert from 'node:assert/strict'
import test from 'node:test'
import { defaultConfig, parseConfig, parseSchema, parseStatus, sameConfig, sameSpawn, terrainHeights, validName, validSeed, validSpawn, validTerrainHeight } from '../src/components/terrain/terrain.js'

function fixture() {
  return {
    supported: true, gameVersion: '26.3', upstream: { name: 'Tectonic', version: '3.0.29', license: 'MIT' }, schema: [], heights: [384, 640, 1024],
    defaults: { ...defaultConfig }, presets: [{ id: 'alpine', name: '宏伟山脉', config: { ...defaultConfig } }],
    worlds: [{
      name: 'alpine', key: 'lc_terrain:alpine', seed: '9223372036854775807', config: { ...defaultConfig },
      status: 'ready', error: '', loaded: true, autoLoad: true, players: 0, minY: -64, maxY: 576
    }],
    busy: false, error: ''
  }
}

test('world names reject path fragments, invalid identifiers and Windows device names', () => {
  for (const value of ['alpine', 'a', 'high_mountain-2', 'a'.repeat(32), 'con_peak']) assert.equal(validName(value), true, value)
  for (const value of ['', '../alpine', 'a/b', 'a\\b', 'ALPINE', '1peak', 'a'.repeat(33), 'con', 'prn', 'aux', 'nul', 'com0', 'com9', 'lpt0', 'lpt9', null, 5]) {
    assert.equal(validName(value), false, String(value))
  }
})

test('seed validation matches signed long string input and preserves every digit', () => {
  for (const value of ['', '  ', '0', '-0', '-9223372036854775808', '9223372036854775807', ' 42 ', '00042']) assert.equal(validSeed(value), true, value)
  for (const value of ['+1', '1.1', '1e5', '0x12', '9223372036854775808', '-9223372036854775809', '0'.repeat(20), '1 2', null, 42, 9007199254740992]) {
    assert.equal(validSeed(value), false, String(value))
  }
  const parsed = parseStatus(fixture())
  assert.equal(parsed.worlds[0].seed, '9223372036854775807')
  assert.equal(typeof parsed.worlds[0].seed, 'string')
})

test('terrain settings accept explicit false and reject malformed scale or height', () => {
  const options = { ...defaultConfig, rollingHills: false, lavaTunnels: false }
  assert.deepEqual(parseConfig(options), options)
  for (const patch of [{ height: 577 }, { height: '640' }, { verticalScale: NaN }, { verticalScale: .09 }, { verticalScale: 15.01 }, { horizontalScale: .49 }, { horizontalScale: 3.01 }, { undergroundRivers: 'false' }, { junglePillars: null }]) {
    assert.throws(() => parseConfig({ ...defaultConfig, ...patch }))
  }
  assert.throws(() => parseConfig([]))
  for (const height of terrainHeights) assert.equal(parseConfig({ ...defaultConfig, height }).height, height)
  for (const verticalScale of [.1, .49, 3.01, 15]) assert.equal(parseConfig({ ...defaultConfig, verticalScale }).verticalScale, verticalScale)
})

test('status parser accepts the actual backend contract and leaves the source unchanged', () => {
  const source = fixture()
  const before = structuredClone(source)
  const parsed = parseStatus(source)
  assert.deepEqual(parsed, source)
  assert.deepEqual(source, before)
  assert.notEqual(parsed.defaults, source.defaults)
  assert.notEqual(parsed.worlds[0].config, source.worlds[0].config)
  source.supported = false
  source.gameVersion = '1.21.11'
  source.error = 'Unsupported server'
  assert.equal(parseStatus(source).error, 'Unsupported server')
})

test('existing terrain worlds retain their saved namespace', () => {
  const source = fixture()
  source.worlds[0].namespace = 'l' + 'br_terrain'
  source.worlds[0].key = `${source.worlds[0].namespace}:alpine`
  assert.equal(parseStatus(source).worlds[0].key, source.worlds[0].key)
})

test('status rejects lossy seeds, mismatched heights and invalid world rows', () => {
  const patches = [
    { seed: 9007199254740992 }, { seed: '' }, { seed: ' 42 ' }, { key: 'minecraft:overworld' }, { name: 'con' },
    { minY: 0 }, { maxY: 640 }, { maxY: undefined }, { autoLoad: 'true' }, { loaded: 1 },
    { players: -1 }, { players: 1.2 }, { loaded: false, players: 1 }, { status: 'unknown' }, { error: null }
  ]
  for (const patch of patches) {
    const value = fixture()
    Object.assign(value.worlds[0], patch)
    assert.throws(() => parseStatus(value), undefined, JSON.stringify(patch))
  }
  const duplicate = fixture()
  duplicate.worlds.push({ ...duplicate.worlds[0] })
  assert.throws(() => parseStatus(duplicate))
  const malformed = fixture()
  malformed.worlds.push(null)
  assert.throws(() => parseStatus(malformed))
})

test('status rejects incomplete metadata and duplicate or malformed presets', () => {
  for (const patch of [{ gameVersion: 26.3 }, { gameVersion: '1.21' }, { supported: 'true' }, { busy: null }, { error: undefined }, { upstream: null }, { upstream: { name: 'Tectonic', version: '3.0.28', license: 'MIT' } }]) {
    assert.throws(() => parseStatus({ ...fixture(), ...patch }))
  }
  const duplicate = fixture()
  duplicate.presets.push({ ...duplicate.presets[0] })
  assert.throws(() => parseStatus(duplicate))
  const malformed = fixture()
  malformed.presets[0].config.height = 577
  assert.throws(() => parseStatus(malformed))
})

const advancedSchema = [
  { key: 'global_terrain.elevation_boost', group: '大陆与山脉', label: '山地抬升', type: 'number', defaultValue: 0, min: 0, max: 5, step: .1 },
  { key: 'caves.cheese_enabled', group: '洞穴', label: '大型洞穴', type: 'boolean', defaultValue: true }
]

test('advanced settings normalize legacy input and preserve explicit zero and false', () => {
  const legacy = { ...defaultConfig }
  delete legacy.advanced
  const normalized = parseConfig(legacy, advancedSchema)
  assert.deepEqual(normalized.advanced, { 'global_terrain.elevation_boost': 0, 'caves.cheese_enabled': true })
  assert.deepEqual(parseConfig({ ...legacy, advanced: { 'caves.cheese_enabled': false } }, advancedSchema).advanced,
    { 'global_terrain.elevation_boost': 0, 'caves.cheese_enabled': false })
  for (const advanced of [null, [], { unknown: 1 }, { 'global_terrain.elevation_boost': '1' },
    { 'global_terrain.elevation_boost': Infinity }, { 'global_terrain.elevation_boost': -1 }, { 'caves.cheese_enabled': 0 }]) {
    assert.throws(() => parseConfig({ ...legacy, advanced }, advancedSchema))
  }
})

test('advanced schema rejects duplicate keys, unbounded inputs and invalid defaults', () => {
  assert.deepEqual(parseSchema(advancedSchema), advancedSchema)
  assert.throws(() => parseSchema([advancedSchema[0], advancedSchema[0]]))
  for (const patch of [{ key: 'path/invalid' }, { type: 'html' }, { defaultValue: NaN }, { min: 6 }, { max: -1 }, { step: 0 }, { group: '' }]) {
    assert.throws(() => parseSchema([{ ...advancedSchema[0], ...patch }]))
  }
  assert.throws(() => parseSchema([{ ...advancedSchema[1], defaultValue: 'true' }]))
})

test('preset comparison includes advanced values without relying on object identity or key order', () => {
  const config = parseConfig(defaultConfig, advancedSchema)
  assert.equal(sameConfig(config, structuredClone(config)), true)
  const reordered = { ...config, advanced: { 'caves.cheese_enabled': true, 'global_terrain.elevation_boost': 0 } }
  assert.equal(sameConfig(config, reordered), true)
  assert.equal(sameConfig(config, { ...config, advanced: { ...config.advanced, 'caves.cheese_enabled': false } }), false)
  assert.equal(sameConfig(config, { ...config, advanced: {} }), false)
})

test('status normalizes complete official presets and per-world advanced settings', () => {
  const source = fixture()
  source.schema = advancedSchema
  source.presets[0].id = 'official_default'
  source.presets[0].config.advanced = { 'global_terrain.elevation_boost': 1 }
  const parsed = parseStatus(source)
  assert.equal(parsed.presets[0].config.advanced['global_terrain.elevation_boost'], 1)
  assert.equal(parsed.worlds[0].config.advanced['global_terrain.elevation_boost'], 0)
  assert.equal(parsed.worlds[0].config.advanced['caves.cheese_enabled'], true)
  assert.equal(source.worlds[0].config.advanced['caves.cheese_enabled'], undefined)
})

test('status distinguishes registered heights from valid preset heights without changing either', () => {
  const source = fixture()
  source.presets.push({ id: 'frozen_wasteland', name: '冰封荒原', config: { ...defaultConfig, height: 512 } })
  source.presets.push({ id: 'overkill', name: '极限地形', config: { ...defaultConfig, height: 832, verticalScale: 2.5 } })
  const parsed = parseStatus(source)
  assert.deepEqual(parsed.heights, [384, 640, 1024])
  assert.equal(parsed.presets.at(-2).config.height, 512)
  assert.equal(parsed.presets.at(-1).config.height, 832)
  assert.notEqual(parsed.heights, source.heights)
  delete source.heights
  assert.deepEqual(parseStatus(source).heights, [384, 640, 1024])
  for (const heights of [null, '384', [384, '640'], [384, 384], [769]]) {
    assert.throws(() => parseStatus({ ...source, heights }))
  }
  assert.deepEqual(parseStatus({ ...source, supported: false, heights: [] }).heights, [])
})

test('custom bounds are validated and match the world and registered dimension', () => {
  const source = fixture()
  source.schema = [{ key: 'global_terrain.min_y', group: '整体地形', label: '最低高度',
    type: 'number', defaultValue: -64, min: -2032, max: 0, step: 16 }]
  source.heightRanges = [{ minY: -128, maxY: 896, height: 1024 }]
  source.worlds[0].config = { ...defaultConfig, height: 1024, advanced: { 'global_terrain.min_y': -128 } }
  source.worlds[0].minY = -128
  source.worlds[0].maxY = 896
  const parsed = parseStatus(source)
  assert.deepEqual(parsed.heightRanges, source.heightRanges)
  assert.notEqual(parsed.heightRanges[0], source.heightRanges[0])
  assert.equal(parsed.worlds[0].config.advanced['global_terrain.min_y'], -128)
  for (const [height, minY] of [[1024, -129], [1025, -128], [4096, -64], [256, -64], [640, 16]]) {
    assert.equal(validTerrainHeight(height, minY), false)
  }
  source.worlds[0].minY = -64
  assert.throws(() => parseStatus(source))
  assert.throws(() => parseStatus({ ...fixture(), heightRanges: [{ minY: -128, maxY: 897, height: 1024 }] }))
  assert.throws(() => parseStatus({ ...fixture(), heightRanges: [source.heightRanges[0], source.heightRanges[0]] }))
})

test('official preset differences are validated and copied independently of editable settings', () => {
  const source = fixture()
  source.schema = [{ key: 'general.snow_start_offset', group: '整体地形', label: '积雪起点偏移', type: 'number', defaultValue: 0, min: 0, max: 1024, step: 16 }]
  source.presets[0].config.advanced = { 'general.snow_start_offset': 512 }
  source.presets[0].unsupported = [
    { key: 'oceans.monument_offset', label: '海底神殿高度偏移', value: -30 }
  ]
  const parsed = parseStatus(source)
  assert.deepEqual(parsed.presets[0].unsupported, source.presets[0].unsupported)
  assert.notEqual(parsed.presets[0].unsupported, source.presets[0].unsupported)
  assert.notEqual(parsed.presets[0].unsupported[0], source.presets[0].unsupported[0])
  assert.equal(parsed.presets[0].config.advanced['general.snow_start_offset'], 512)
  assert.throws(() => parseConfig({ ...defaultConfig, advanced: { 'general.snow_start_offset': 512.5 } }, source.schema))
  for (const unsupported of [null, {}, [null], [{ key: 'invalid', label: '设置', value: 1 }],
    [{ key: 'oceans.monument_offset', label: '', value: -30 }],
    [{ key: 'oceans.monument_offset', label: '海底神殿高度偏移', value: Infinity }],
    [source.presets[0].unsupported[0], source.presets[0].unsupported[0]]]) {
    const malformed = fixture()
    malformed.presets[0].unsupported = unsupported
    assert.throws(() => parseStatus(malformed))
  }
})

test('spawn edits require exact integer coordinates with room for a floor and two clear blocks', () => {
  for (const value of [{ x: 0, y: -63, z: 0 }, { x: -29999984, y: 574, z: 29999984 }]) {
    assert.equal(validSpawn(value, -64, 576), true)
    assert.equal(sameSpawn(value, { ...value }), true)
  }
  for (const value of [null, [], {}, { x: '0', y: 64, z: 0 }, { x: 0.1, y: 64, z: 0 },
    { x: NaN, y: 64, z: 0 }, { x: 29999985, y: 64, z: 0 }, { x: 0, y: 64, z: -29999985 },
    { x: 0, y: -64, z: 0 }, { x: 0, y: 575, z: 0 }, { x: 0, y: 64, z: 0, yaw: 0 }]) {
    assert.equal(validSpawn(value, -64, 576), false, JSON.stringify(value))
  }
  assert.equal(sameSpawn({ x: 0, y: 64, z: 0 }, { x: 0, y: 65, z: 0 }), false)
  assert.equal(sameSpawn({}, undefined), false)
})

test('status copies optional spawn coordinates without hiding unsafe stored locations from repair', () => {
  const source = fixture()
  source.worlds[0].spawn = { x: 0, y: 1000, z: -1 }
  const parsed = parseStatus(source)
  assert.deepEqual(parsed.worlds[0].spawn, source.worlds[0].spawn)
  assert.notEqual(parsed.worlds[0].spawn, source.worlds[0].spawn)
  assert.equal(validSpawn(parsed.worlds[0].spawn, -64, 576), false)
  for (const spawn of [null, [], {}, { x: 0, y: 64, z: '1' }, { x: 0, y: 64.5, z: 0 },
    { x: 0, y: 64, z: Infinity }, { x: 0, y: 64, z: 9007199254740992 }, { x: 0, y: 64, z: 0, yaw: 0 }]) {
    const invalid = fixture()
    invalid.worlds[0].spawn = spawn
    assert.throws(() => parseStatus(invalid))
  }
})
