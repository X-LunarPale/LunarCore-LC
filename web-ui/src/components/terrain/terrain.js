export const baseConfigKeys = ['height', 'verticalScale', 'horizontalScale', 'rollingHills', 'undergroundRivers', 'lavaTunnels', 'junglePillars']
export const configKeys = [...baseConfigKeys, 'advanced']
export const terrainHeights = [384, 512, 640, 704, 832, 1024]
export const defaultConfig = { height: 640, verticalScale: 1.8, horizontalScale: 1.2, rollingHills: true, undergroundRivers: true, lavaTunnels: true, junglePillars: true, advanced: {} }
export const worldStates = { registered: '等待加载', queued: '排队中', running: '生成中', ready: '就绪', failed: '生成失败', unloaded: '已卸载' }
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const spawnKeys = ['x', 'y', 'z']
const spawnCoordinates = value => record(value) && Object.keys(value).length === 3 &&
  spawnKeys.every(key => Object.hasOwn(value, key) && Number.isSafeInteger(value[key]))

export const configMinY = config => config.advanced?.['global_terrain.min_y'] ?? -64
export function validTerrainHeight(height, minY = -64) {
  return Number.isInteger(height) && height >= 256 && height <= 4064 && height % 16 === 0 &&
    Number.isInteger(minY) && minY >= -2032 && minY <= 0 && minY % 16 === 0 &&
    minY + height >= 256 && minY + height <= 2032
}

export function sameSpawn(first, second) {
  return !!first && !!second && spawnKeys.every(key => first[key] === second[key])
}

export function validSpawn(value, minY, maxY) {
  return spawnCoordinates(value) && Number.isInteger(minY) && Number.isInteger(maxY) &&
    Math.abs(value.x) <= 29999984 && Math.abs(value.z) <= 29999984 &&
    value.y >= minY + 1 && value.y <= maxY - 2
}

export function parseConfig(value, schema = []) {
  if (!record(value) || !Number.isInteger(value.height) ||
      !Number.isFinite(value.verticalScale) || value.verticalScale < .1 || value.verticalScale > 15 ||
      !Number.isFinite(value.horizontalScale) || value.horizontalScale < .5 || value.horizontalScale > 3 ||
      !baseConfigKeys.slice(3).every(key => typeof value[key] === 'boolean')) throw new Error('地形参数接口返回格式错误')
  const supplied = value.advanced === undefined ? {} : value.advanced
  if (!record(supplied)) throw new Error('地形高级参数接口返回格式错误')
  const fields = new Map(schema.map(field => [field.key, field]))
  if (Object.keys(supplied).some(key => !fields.has(key))) throw new Error('地形高级参数接口返回格式错误')
  const advanced = {}
  for (const field of schema) {
    const setting = Object.hasOwn(supplied, field.key) ? supplied[field.key] : field.defaultValue
    if (field.type === 'boolean' ? typeof setting !== 'boolean'
      : !Number.isFinite(setting) || setting < field.min || setting > field.max ||
        ['general.snow_start_offset', 'oceans.monument_offset'].includes(field.key) && !Number.isInteger(setting) ||
        field.key === 'global_terrain.min_y' && setting % 16 !== 0) throw new Error('地形高级参数接口返回格式错误')
    advanced[field.key] = setting
  }
  if (!validTerrainHeight(value.height, advanced['global_terrain.min_y'] ?? -64)) throw new Error('地形参数接口返回格式错误')
  return { ...Object.fromEntries(baseConfigKeys.map(key => [key, value[key]])), advanced }
}

export function parseSchema(value = []) {
  if (!Array.isArray(value)) throw new Error('地形高级参数接口返回格式错误')
  const keys = new Set()
  return value.map(field => {
    if (!record(field) || typeof field.key !== 'string' || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(field.key) ||
        keys.has(field.key) || !['number', 'boolean'].includes(field.type) ||
        typeof field.group !== 'string' || !field.group.trim() || typeof field.label !== 'string' || !field.label.trim() ||
        (field.type === 'boolean' ? typeof field.defaultValue !== 'boolean'
          : ![field.min, field.max, field.step, field.defaultValue].every(Number.isFinite) ||
            field.min > field.max || field.defaultValue < field.min || field.defaultValue > field.max || field.step <= 0)) {
      throw new Error('地形高级参数接口返回格式错误')
    }
    keys.add(field.key)
    return { ...field }
  })
}

export function sameConfig(first, second) {
  if (!first || !second || !baseConfigKeys.every(key => first[key] === second[key])) return false
  const a = first.advanced || {}, b = second.advanced || {}
  return Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(key => Object.hasOwn(b, key) && a[key] === b[key])
}

export function validName(value) {
  return typeof value === 'string' && /^[a-z][a-z0-9_-]{0,31}$/.test(value) &&
    !/^(con|prn|aux|nul|com[0-9]|lpt[0-9])$/.test(value)
}

export function validSeed(value) {
  if (typeof value !== 'string') return false
  value = value.trim()
  if (value === '') return true
  if (!/^-?[0-9]{1,19}$/.test(value)) return false
  try { const seed = BigInt(value); return seed >= -9223372036854775808n && seed <= 9223372036854775807n } catch { return false }
}

export function parseStatus(value) {
  if (!record(value) || value.ok === false || typeof value.supported !== 'boolean' || !Array.isArray(value.worlds) ||
      !Array.isArray(value.presets) || typeof value.busy !== 'boolean' || typeof value.error !== 'string' ||
      typeof value.gameVersion !== 'string' || !value.gameVersion.trim() || value.supported && value.gameVersion !== '26.3' ||
      !record(value.upstream) || value.upstream.name !== 'Tectonic' || value.upstream.version !== '3.0.29' || value.upstream.license !== 'MIT') {
    throw new Error(typeof value?.error === 'string' && value.error ? value.error : '地形状态接口返回格式错误')
  }
  const heights = value.heights === undefined ? [384, 640, 1024] : value.heights
  if (!Array.isArray(heights) || heights.some(height => !validTerrainHeight(height)) || new Set(heights).size !== heights.length) {
    throw new Error('地形可用高度接口返回格式错误')
  }
  let heightRanges
  if (value.heightRanges !== undefined) {
    const seen = new Set()
    if (!Array.isArray(value.heightRanges)) throw new Error('地形可用高度接口返回格式错误')
    heightRanges = value.heightRanges.map(range => {
      if (!record(range) || !validTerrainHeight(range.height, range.minY) ||
          range.maxY !== range.minY + range.height || seen.has(`${range.minY}:${range.height}`)) {
        throw new Error('地形可用高度接口返回格式错误')
      }
      seen.add(`${range.minY}:${range.height}`)
      return { ...range }
    })
  }
  const schema = parseSchema(value.schema)
  const defaults = parseConfig(value.defaults, schema)
  const presetIds = new Set()
  const presets = value.presets.map(preset => {
    if (!record(preset) || typeof preset.id !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(preset.id) || presetIds.has(preset.id) ||
        typeof preset.name !== 'string' || !preset.name.trim()) throw new Error('地形状态接口返回格式错误')
    if (preset.unsupported !== undefined) {
      const keys = new Set()
      if (!Array.isArray(preset.unsupported) || preset.unsupported.some(field => {
        if (!record(field) || typeof field.key !== 'string' || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(field.key) ||
            keys.has(field.key) || typeof field.label !== 'string' || !field.label.trim() ||
            typeof field.value !== 'boolean' && !Number.isFinite(field.value)) return true
        keys.add(field.key)
        return false
      })) throw new Error('地形预设适配信息接口返回格式错误')
    }
    presetIds.add(preset.id)
    return { ...preset, ...(preset.unsupported === undefined ? {} : { unsupported: preset.unsupported.map(field => ({ ...field })) }), config: parseConfig(preset.config, schema) }
  })
  const worldNames = new Set()
  const worlds = value.worlds.map(world => {
    if (!record(world) || !validName(world.name) || worldNames.has(world.name) || world.key !== `lbr_terrain:${world.name}` ||
        typeof world.seed !== 'string' || !validSeed(world.seed) || !world.seed || world.seed.trim() !== world.seed ||
        !Object.hasOwn(worldStates, world.status) || typeof world.loaded !== 'boolean' || typeof world.autoLoad !== 'boolean' ||
        !Number.isSafeInteger(world.players) || world.players < 0 || !world.loaded && world.players !== 0 ||
        typeof world.error !== 'string') throw new Error('地形世界接口返回格式错误')
    const config = parseConfig(world.config, schema)
    if (world.minY !== configMinY(config) || world.maxY !== config.height + configMinY(config)) throw new Error('地形世界接口返回格式错误')
    if (world.spawn !== undefined && !spawnCoordinates(world.spawn)) throw new Error('地形出生点接口返回格式错误')
    worldNames.add(world.name)
    return { ...world, config, ...(world.spawn === undefined ? {} : { spawn: { ...world.spawn } }) }
  })
  return { ...value, heights: [...heights], ...(heightRanges === undefined ? {} : { heightRanges }), schema, defaults, presets, worlds }
}
