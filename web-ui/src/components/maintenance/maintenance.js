export const normalKeys = ['enabled', 'line1', 'line2']
export const controlKeys = ['enabled', 'allowJoin', 'kickOnline', 'scheduleEnabled', 'scheduleStart', 'scheduleEnd']
export const whitelistKeys = ['whitelist']
export const messageKeys = ['joinMessage', 'kickMessage']
export const motdKeys = ['line1', 'line2']
export const maintenanceKeys = [...controlKeys, ...whitelistKeys, ...messageKeys, ...motdKeys]
export const pick = (value, keys) => Object.fromEntries(keys.map(key => [key, value[key]]))
export const same = (left, right, keys) => !!left && !!right && keys.every(key => left[key] === right[key])

const defaultText = (source, params = {}) => source.replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))

export function validateText(value, label, translate = defaultText) {
  if (typeof value !== 'string' || value.length > 512) throw new Error(translate('{label}最多允许 512 个字符', { label }))
  if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029\ue000-\ue002]/.test(value)) throw new Error(translate('{label}不得包含换行或控制字符', { label }))
}

export function normalizeWhitelist(text, translate = defaultText) {
  if (typeof text !== 'string' || text.length > 20000) throw new Error(translate('白名单最多允许 20000 个字符'))
  const entries = text.split(/\r\n|\n|\r/).map(item => item.trim()).filter(Boolean)
  if (entries.length > 500) throw new Error(translate('维护白名单最多允许 500 项'))
  const normalized = entries.map((entry, index) => {
    if (/^[a-zA-Z0-9_]{3,16}$/.test(entry)) return entry.toLowerCase()
    let uuid = entry.toLowerCase()
    if (/^[a-f0-9]{32}$/.test(uuid)) uuid = `${uuid.slice(0, 8)}-${uuid.slice(8, 12)}-${uuid.slice(12, 16)}-${uuid.slice(16, 20)}-${uuid.slice(20)}`
    if (/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(uuid)) return uuid
    throw new Error(translate('白名单第 {index} 项无效：请输入 3–16 位玩家名称或完整 UUID', { index: index + 1 }))
  })
  return [...new Set(normalized)].join('\n')
}

export function checkSettings(value, maintenance = false) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('设置数据格式异常，请重新加载')
  if (maintenance) value = { scheduleEnabled: false, scheduleStart: '', scheduleEnd: '', ...value }
  const keys = maintenance ? maintenanceKeys : normalKeys
  if (!value || keys.some(key => typeof value[key] !== (['enabled', 'allowJoin', 'kickOnline', 'scheduleEnabled'].includes(key) ? 'boolean' : 'string'))) throw new Error('设置数据格式异常，请重新加载')
  return pick(value, keys)
}

export function checkStatus(value) {
  const row = Array.isArray(value) && value.length === 1
    ? { scheduleEnabled: false, scheduleActive: false, scheduleStart: '', scheduleEnd: '', nextTransitionAt: '', ...value[0] }
    : null
  if (!row || ['enabled', 'allowJoin', 'kickOnline', 'scheduleEnabled', 'scheduleActive'].some(key => typeof row[key] !== 'boolean') || ['online', 'eligible'].some(key => !Number.isFinite(row[key]) || row[key] < 0)) throw new Error('维护状态数据格式异常')
  return row
}

export function effectiveEnabled(settings, now = Date.now()) {
  if (!settings.scheduleEnabled) return settings.enabled
  const start = Date.parse(settings.scheduleStart)
  const end = settings.scheduleEnd ? Date.parse(settings.scheduleEnd) : Infinity
  return Number.isFinite(start) && now >= start && now < end
}

export function validateSchedule(settings, translate = defaultText) {
  const start = settings.scheduleStart ? Date.parse(settings.scheduleStart) : null
  const end = settings.scheduleEnd ? Date.parse(settings.scheduleEnd) : null
  if (settings.scheduleEnabled && start === null) throw new Error(translate('请选择维护开始时间'))
  if (start !== null && !Number.isFinite(start) || end !== null && !Number.isFinite(end)) throw new Error(translate('维护时间无效，请重新选择'))
  if (end !== null && (start === null || end <= start)) throw new Error(translate('维护结束时间必须晚于开始时间'))
}

export function toLocalTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  const pad = part => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function fromLocalTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isFinite(date.getTime()) ? date.toISOString() : value
}
