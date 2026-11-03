export const normalKeys = ['enabled', 'line1', 'line2']
export const controlKeys = ['enabled', 'allowJoin', 'kickOnline', 'whitelist', 'joinMessage', 'kickMessage']
export const motdKeys = ['line1', 'line2']
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
  const keys = maintenance ? [...controlKeys, ...motdKeys] : normalKeys
  if (!value || keys.some(key => typeof value[key] !== (['enabled', 'allowJoin', 'kickOnline'].includes(key) ? 'boolean' : 'string'))) throw new Error('设置数据格式异常，请重新加载')
  return pick(value, keys)
}

export function checkStatus(value) {
  const row = Array.isArray(value) && value.length === 1 ? value[0] : null
  if (!row || ['enabled', 'allowJoin', 'kickOnline'].some(key => typeof row[key] !== 'boolean') || ['online', 'eligible'].some(key => !Number.isFinite(row[key]) || row[key] < 0)) throw new Error('维护状态数据格式异常')
  return row
}
