export const SCOPES = [
  { id: 'global', label: '全局配置', description: '管理服务器级别的 Paper 设置。' },
  { id: 'world-defaults', label: '世界默认配置', description: '作为世界配置的默认设置；各世界的独立配置可能覆盖这些值。' }
]

export function formatValue(value) {
  if (value === undefined || value === null) return '未设置'
  if (typeof value === 'boolean') return value ? '开启' : '关闭'
  return String(value)
}

export function sentinelEntries(field) {
  const sentinel = field.sentinel
  if (!sentinel || typeof sentinel !== 'object') return []
  if (Array.isArray(sentinel)) return sentinel.map(item => ({ value: item.value, label: item.label || item.help || String(item.value) }))
  return Object.entries(sentinel).map(([value, label]) => ({ value: /^-?\d+(\.\d+)?$/.test(value) ? Number(value) : value, label: typeof label === 'object' ? label.label || label.help || String(value) : String(label) }))
}

export function optionsFor(field) {
  return (field.options || []).map(option => typeof option === 'object' && option !== null
    ? { value: option.value, label: option.label || option.name || String(option.value) }
    : { value: option, label: String(option) })
}

export function isNumeric(field) { return ['integer', 'number', 'double', 'float', 'int', 'long'].includes(field.type) }

export function fieldError(field, value) {
  if (sentinelEntries(field).some(item => Object.is(item.value, value))) return ''
  if (field.type === 'boolean') return typeof value === 'boolean' ? '' : '请选择开启或关闭'
  if (isNumeric(field)) {
    if (typeof value !== 'number' || !Number.isFinite(value)) return '请输入有效数值'
    if (['integer', 'int', 'long'].includes(field.type) && !Number.isInteger(value)) return '请输入整数'
    if (field.min != null && value < field.min) return `不得小于 ${field.min}`
    if (field.max != null && value > field.max) return `不得大于 ${field.max}`
  }
  if (field.options?.length && !optionsFor(field).some(option => Object.is(option.value, value))) return '请选择支持的选项'
  return ''
}

export function makePatch(fields, values, original) {
  return Object.fromEntries(fields.filter(field => field.editable !== false && field.present !== false && !Object.is(values[field.key], original[field.key])).map(field => [field.key, values[field.key]]))
}
