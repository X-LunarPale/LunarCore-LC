const COMMAND = /^(\d+):(\d+)[ \t]+([^\r\n]+)$/
const EDITABLE_TYPES = new Set(['bool', 'number', 'text', 'textarea', 'select', 'string-list', 'punishment-commands'])

export function copyConfigValue(value) {
  return Array.isArray(value) ? value.map(copyConfigValue) : value
}

export function parseGrimSnapshot(data, expectedId) {
  if (!data || typeof data !== 'object' || data.id !== expectedId || typeof data.fingerprint !== 'string'
      || !data.fingerprint || !Array.isArray(data.fields) || !data.values || typeof data.values !== 'object' || Array.isArray(data.values)) {
    throw new Error('GrimAC 配置响应不完整，请重新读取。')
  }
  const keys = new Set()
  for (const field of data.fields) {
    if (!field || typeof field.key !== 'string' || !field.key || keys.has(field.key)
        || typeof field.label !== 'string' || !field.label || !field.readOnly && !EDITABLE_TYPES.has(field.type)) {
      throw new Error('GrimAC 配置响应不完整，请重新读取。')
    }
    keys.add(field.key)
  }
  const values = Object.fromEntries(data.fields.map(field => [
    field.key, field.secret ? '' : copyConfigValue(data.values[field.key])
  ]))
  return { ...data, values }
}

export function grimChanges(fields, draft, original, touchedSecrets = {}) {
  return Object.fromEntries((fields || []).filter(field => !field.readOnly
    && (!field.secret || touchedSecrets[field.key])
    && (field.secret || JSON.stringify(draft[field.key]) !== JSON.stringify(original[field.key])))
    .map(field => [field.key, copyConfigValue(draft[field.key])]))
}

export function parsePunishmentCommand(raw) {
  const match = typeof raw === 'string' ? raw.match(COMMAND) : null
  if (!match) return null
  const threshold = Number(match[1])
  const interval = Number(match[2])
  if (!Number.isSafeInteger(threshold) || !Number.isSafeInteger(interval)
      || threshold > 2147483647 || interval > 2147483647 || !match[3].trim() || raw.includes('\0')) return null
  return { threshold, interval, command: match[3] }
}

export function updatePunishmentCommand(raw, part, value) {
  const parsed = parsePunishmentCommand(raw)
  if (!parsed || !['threshold', 'interval', 'command'].includes(part)) return raw
  parsed[part] = value
  return `${parsed.threshold ?? ''}:${parsed.interval ?? ''} ${parsed.command}`
}

export function grimFieldError(field, value) {
  if (field.readOnly) return ''
  if (field.type === 'number') {
    if (typeof value !== 'number' || !Number.isFinite(value)) return '请输入有效数值。'
    if (Number.isFinite(field.min) && value < field.min || Number.isFinite(field.max) && value > field.max) return '数值超出允许范围。'
    if (field.step === 1 && !Number.isSafeInteger(value)) return '请输入整数。'
  }
  if (field.type === 'bool' && typeof value !== 'boolean') return '请选择有效开关值。'
  if (field.type === 'select' && !(field.options || []).some(option => option.value === value)) return '请选择有效选项。'
  if (field.type === 'string-list' || field.type === 'punishment-commands') {
    if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) return '列表内容必须为文本。'
    if (field.type === 'punishment-commands' && value.some(item => !parsePunishmentCommand(item))) return '处罚规则格式无效，请填写违规等级、重复间隔和执行命令。'
  }
  if (['text', 'textarea'].includes(field.type) && typeof value !== 'string') return '请输入文本。'
  return ''
}

export function grimFieldGroups(fields, query = '', group = '') {
  const needle = query.trim().toLocaleLowerCase()
  const result = new Map()
  for (const field of fields || []) {
    const name = field.group || '其他设置'
    if (group && group !== name || needle && ![field.label, field.key, field.help, name].join(' ').toLocaleLowerCase().includes(needle)) continue
    if (!result.has(name)) result.set(name, [])
    result.get(name).push(field)
  }
  return [...result].map(([name, items]) => ({ name, fields: items }))
}
