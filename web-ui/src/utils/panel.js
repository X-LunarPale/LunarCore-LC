// Shared panel request rules; keep failures visible and never treat them as success.
import { t } from '../i18n.js'

export function requireSuccess(result, fallback = '操作未成功') {
  if (result && typeof result === 'object' && result.ok === false) {
    throw new Error(result.error || result.message || t(fallback))
  }
  return result
}

export function selectValue(field, value) {
  if ((field.type || '').toLowerCase() !== 'select' || value == null) return value
  const options = field.options || []
  return options.find(o => o.value === value)?.value ?? options.find(o => String(o.value) === String(value))?.value ?? value
}

export function presentFields(values) {
  return Object.fromEntries(Object.entries(values || {}).filter(([, value]) => value !== null && value !== undefined))
}

export function missingRequired(fields, values) {
  return (fields || []).find(f => f.required && (values[f.key] == null ||
    (typeof values[f.key] === 'string' && !values[f.key].trim())))
}

export function groupFields(fields) {
  const groups = new Map()
  for (const field of fields || []) {
    const title = typeof field.group === 'string' ? field.group.trim() : ''
    if (!groups.has(title)) groups.set(title, [])
    groups.get(title).push(field)
  }
  return [...groups].map(([title, items]) => ({ title, fields: items }))
}

export function panelText(value, translationKey) {
  return typeof translationKey === 'string' && translationKey.trim() ? t(translationKey) : value
}

export function panelEnum(value, labels) {
  return labels && Object.hasOwn(labels, String(value)) ? t(labels[String(value)]) : value
}

export function rowActionVisible(action, row) {
  return !action.visibleWhenField || (Array.isArray(action.visibleWhenValues) &&
    action.visibleWhenValues.includes(String(row?.[action.visibleWhenField])))
}

// A slower earlier response must not overwrite a newer search or refresh.
export function latestRequest() {
  let revision = 0
  return () => { const current = ++revision; return () => revision === current }
}
