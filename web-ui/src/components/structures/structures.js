export const families = [
  ['mineshafts', '矿井'], ['dungeons', '地牢'], ['strongholds', '要塞'],
  ['desert_temples', '沙漠神庙'], ['jungle_temples', '丛林神庙'], ['witch_huts', '女巫小屋'],
  ['ocean_monuments', '海底神殿'], ['nether_fortresses', '下界要塞'], ['end_island', '末地岛']
].map(([id, label]) => ({ id, label }))
const ids = families.map(f => f.id)
const object = v => v && typeof v === 'object' && !Array.isArray(v)

export function parseConfig(value) {
  if (!object(value) || Object.keys(value).sort().join() !== ['defaults', 'revision', 'schema', 'worlds'].join()
    || value.schema !== 1 || !Number.isSafeInteger(value.revision) || value.revision < 0
    || !object(value.defaults) || Object.keys(value.defaults).join() !== 'structures'
    || !object(value.defaults.structures)
    || Object.keys(value.defaults.structures).sort().join() !== [...ids].sort().join()
    || ids.some(id => typeof value.defaults.structures[id] !== 'boolean') || !object(value.worlds)) {
    throw new Error('结构配置格式无效')
  }
  for (const [world, overrides] of Object.entries(value.worlds)) {
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(world) || world.includes('..') || world.length > 256
      || !object(overrides) || Object.entries(overrides).some(([key, flag]) => !ids.includes(key) || typeof flag !== 'boolean')) {
      throw new Error('世界结构配置格式无效')
    }
  }
  return JSON.parse(JSON.stringify(value))
}

export function effective(config, world = '') {
  const result = { ...config.defaults.structures }
  return world ? { ...result, ...(config.worlds[world] || {}) } : result
}

export function setFlag(config, world, field, value) {
  if (!ids.includes(field) || typeof value !== 'boolean') throw new Error('结构开关格式无效')
  if (world) config.worlds[world] = { ...config.worlds[world], [field]: value }
  else config.defaults.structures[field] = value
}

export function inheritWorld(config, world, inherit) {
  if (!world) return
  if (inherit) delete config.worlds[world]
  else config.worlds[world] = effective(config, world)
}

// No worlds selected means the server defaults.
export function commonFlags(config, worlds) {
  const values = (worlds.length ? worlds : ['']).map(world => effective(config, world))
  return Object.fromEntries(ids.map(field => [field, values.every(value => value[field] === values[0][field]) ? values[0][field] : null]))
}

export function setFlags(config, worlds, field, value) {
  if (!worlds.length) return setFlag(config, '', field, value)
  for (const world of worlds) {
    // A partial override would leave this world's other switches following the defaults.
    if (!Object.keys(config.worlds[world] || {}).length) inheritWorld(config, world, false)
    setFlag(config, world, field, value)
  }
}

export function inheritWorlds(config, worlds, inherit) {
  worlds.forEach(world => inheritWorld(config, world, inherit))
}

export function sameRules(a, b) {
  if (!a || !b) return false
  const sorted = value => Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)))
  const canonical = config => {
    const worlds = Object.entries(config.worlds)
      .filter(([, value]) => Object.keys(value).length)
      .map(([key, value]) => [key, sorted(value)])
    return JSON.stringify({ defaults: sorted(effective(config)), worlds: sorted(Object.fromEntries(worlds)) })
  }
  return canonical(a) === canonical(b)
}
