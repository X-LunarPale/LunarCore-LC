import { locale, t } from '../i18n.js'

export function pad(n) {
  return String(n).padStart(2, '0')
}

export function formatDateTime(v) {
  if (v === null || v === undefined || v === '' || Number(v) <= 0) return '-'
  const d = new Date(typeof v === 'number' || /^\d+$/.test(String(v)) ? Number(v) : v)
  if (isNaN(d.getTime())) return String(v)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function formatRelative(v) {
  if (!v || Number(v) <= 0) return '-'
  const diff = Date.now() - Number(v)
  if (diff < 0) return formatDateTime(v)
  if (diff < 60_000) return t('刚刚')
  if (diff < 3_600_000) return t('{count} 分钟前', { count: Math.floor(diff / 60_000) })
  if (diff < 86_400_000) return t('{count} 小时前', { count: Math.floor(diff / 3_600_000) })
  if (diff < 30 * 86_400_000) return t('{count} 天前', { count: Math.floor(diff / 86_400_000) })
  return formatDateTime(v)
}

/** 毫秒 → "1天2小时3分" */
export function formatDuration(ms) {
  if (ms === null || ms === undefined || ms === '') return '-'
  let n = Number(ms)
  if (isNaN(n)) return String(ms)
  if (n < 0) return t('永久')
  if (n < 1000) return t('{count} 毫秒', { count: n })
  let s = Math.floor(n / 1000)
  const d = Math.floor(s / 86400)
  s -= d * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  s -= m * 60
  const parts = []
  if (d) parts.push(t('{count}天', { count: d }))
  if (h) parts.push(t('{count}小时', { count: h }))
  if (m) parts.push(t('{count}分', { count: m }))
  if (!d && !h && s) parts.push(t('{count}秒', { count: s }))
  return parts.join('') || t('{count}秒', { count: 0 })
}

export function formatBytes(b) {
  if (b === null || b === undefined || b < 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let n = Number(b)
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return n.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

export function formatNumber(v) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (isNaN(n)) return String(v)
  return n.toLocaleString(locale.value === 'en_US' ? 'en-US' : 'zh-CN', { maximumFractionDigits: 2 })
}

export function formatMoney(v) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (isNaN(n)) return String(v)
  return n.toLocaleString(locale.value === 'en_US' ? 'en-US' : 'zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** "1d2h30m" / "90s" / "2w" → 毫秒；纯数字视为秒。 */
export function parseDuration(text) {
  if (text === null || text === undefined) return null
  const s = String(text).trim().toLowerCase()
  if (!s) return null
  if (['perm', 'permanent', 'forever', '永久', '-1'].includes(s)) return -1
  if (/^\d+$/.test(s)) return Number(s) * 1000
  const unit = { w: 604800, d: 86400, h: 3600, m: 60, s: 1, mo: 2592000, y: 31536000 }
  let total = 0
  const re = /(\d+)\s*(mo|y|w|d|h|m|s)/g
  let m
  let matched = false
  while ((m = re.exec(s))) {
    matched = true
    total += Number(m[1]) * unit[m[2]]
  }
  return matched ? total * 1000 : null
}

export function avatarUrl(nameOrUuid, size = 24) {
  if (!nameOrUuid) return ''
  return `https://mc-heads.net/avatar/${encodeURIComponent(String(nameOrUuid).replace(/-/g, ''))}/${size}`
}

const TAG_COLORS = ['', 'success', 'warning', 'danger', 'info']
export function tagType(value) {
  const v = String(value ?? '').toLowerCase()
  if (['true', 'yes', 'on', 'enabled', 'active', 'online', 'success', 'ok', '启用', '在线', '正常', '成功'].includes(v)) return 'success'
  if (['false', 'no', 'off', 'disabled', 'inactive', 'offline', '禁用', '离线', '已过期', 'expired'].includes(v)) return 'info'
  if (['error', 'danger', 'ban', 'banned', 'fail', 'failed', '封禁', '临时封禁', 'ip封禁', '失败', '严重', '生效中', 'high', 'critical'].includes(v)) return 'danger'
  if (['warn', 'warning', 'mute', 'muted', 'pending', '警告', '禁言', '临时禁言', '待处理', 'medium', '管理员'].includes(v)) return 'warning'
  let h = 0
  for (const c of v) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return TAG_COLORS[h % TAG_COLORS.length]
}

/** endpoint 中的 {key} 用 row 的值替换（URL 编码）。 */
export function fillTemplate(tpl, row) {
  if (!tpl) return tpl
  return tpl.replace(/\{([^}]+)\}/g, (_, k) => {
    const v = row ? row[k] : undefined
    return v === undefined || v === null ? '' : encodeURIComponent(String(v))
  })
}

/** 文本中的 {key} 用 row 的值替换（不编码，用于确认框文本）。 */
export function fillText(tpl, row) {
  if (!tpl) return tpl
  return tpl.replace(/\{([^}]+)\}/g, (m, k) => (row && row[k] !== undefined && row[k] !== null ? String(row[k]) : m))
}

/** 毫秒 → 紧凑时长文本 "1d2h3m4s"（与后端 Durations.parse 兼容）；0 → "0"，负数/null → ""。 */
export function toDurationText(ms) {
  const n = Number(ms)
  if (ms === null || ms === undefined || ms === '' || isNaN(n) || n < 0) return ''
  if (n === 0) return '0'
  let s = Math.floor(n / 1000)
  const parts = []
  for (const [u, sec] of [['d', 86400], ['h', 3600], ['m', 60], ['s', 1]]) {
    const v = Math.floor(s / sec)
    if (v) parts.push(v + u)
    s -= v * sec
  }
  return parts.join('') || '0'
}
