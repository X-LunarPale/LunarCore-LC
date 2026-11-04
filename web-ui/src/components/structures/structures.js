export const families = [
  ['mineshafts', '矿井'], ['dungeons', '地牢'], ['strongholds', '要塞'],
  ['desert_temples', '沙漠神庙'], ['jungle_temples', '丛林神庙'], ['witch_huts', '女巫小屋'],
  ['ocean_monuments', '海底神殿'], ['nether_fortresses', '下界要塞'], ['end_island', '末地岛']
].map(([id, label]) => ({ id, label }))
const ids = families.map(f => f.id)
const fields = [...ids, 'overwriteExisting']
export const jobPhases = {
  queued: '等待处理', recovering: '正在恢复备份', scanning: '正在扫描旧结构', replaying: '正在重放生成',
  checking: '正在核对保护范围', backing_up: '正在备份', applying: '正在覆盖结构',
  completed: '处理完成', cancelled: '已取消处理', failed: '处理失败', recovery_required: '需要恢复备份'
}
const object = v => v && typeof v === 'object' && !Array.isArray(v)

// Keep coordinates and native diagnostic details intact while translating the server's explanations.
export function structureMessage(value, translate) {
  const text = String(value || '')
  for (const [prefix, key] of [
    ['无法重放生成来源：', '无法重放生成来源：{detail}'],
    ['与原版重放不一致，已保留原样；', '与原版重放不一致，已保留原样；{detail}'],
    ['方块不一致：', '方块不一致：{detail}'],
    ['范围内有无法确认来源的实体：', '范围内有无法确认来源的实体：{detail}']
  ]) {
    if (text.startsWith(prefix)) return translate(key, { detail: structureMessage(text.slice(prefix.length), translate) })
  }
  return translate(text)
}

export function parseConfig(value) {
  if (!object(value) || Object.keys(value).sort().join() !== ['defaults', 'revision', 'schema', 'worlds'].join()
    || value.schema !== 1 || !Number.isSafeInteger(value.revision) || value.revision < 0
    || !object(value.defaults) || Object.keys(value.defaults).sort().join() !== 'overwriteExisting,structures'
    || typeof value.defaults.overwriteExisting !== 'boolean' || !object(value.defaults.structures)
    || Object.keys(value.defaults.structures).sort().join() !== [...ids].sort().join()
    || ids.some(id => typeof value.defaults.structures[id] !== 'boolean') || !object(value.worlds)) {
    throw new Error('结构配置格式无效')
  }
  for (const [world, overrides] of Object.entries(value.worlds)) {
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(world) || world.includes('..') || world.length > 256
      || !object(overrides) || Object.entries(overrides).some(([key, flag]) => !fields.includes(key) || typeof flag !== 'boolean')) {
      throw new Error('世界结构配置格式无效')
    }
  }
  return JSON.parse(JSON.stringify(value))
}

export function effective(config, world = '') {
  const result = { ...config.defaults.structures, overwriteExisting: config.defaults.overwriteExisting }
  return world ? { ...result, ...(config.worlds[world] || {}) } : result
}

export function setFlag(config, world, field, value) {
  if (!fields.includes(field) || typeof value !== 'boolean') throw new Error('结构开关格式无效')
  if (world) config.worlds[world] = { ...config.worlds[world], [field]: value }
  else if (field === 'overwriteExisting') config.defaults.overwriteExisting = value
  else config.defaults.structures[field] = value
}

export function inheritWorld(config, world, inherit) {
  if (!world) return
  if (inherit) delete config.worlds[world]
  else config.worlds[world] = effective(config, world)
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
