// A deliberately small, pure renderer: output is text plus a closed set of styles,
// never HTML, URLs, events, or user-supplied CSS.
export const NAMED_COLORS = Object.freeze({
  black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
  dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa',
  dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
  red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff'
})
const LEGACY = Object.values(NAMED_COLORS)
const DECORATIONS = { b: 'bold', bold: 'bold', i: 'italic', em: 'italic', italic: 'italic', u: 'underlined', underlined: 'underlined', st: 'strikethrough', strikethrough: 'strikethrough' }
const MAX_LENGTH = 16384
const baseStyle = () => ({ color: null, bold: false, italic: false, underlined: false, strikethrough: false, effect: null })
const color = value => typeof value === 'string' ? (Object.hasOwn(NAMED_COLORS, value) ? NAMED_COLORS[value] : /^#[0-9a-f]{6}$/i.test(value) ? value.toLowerCase() : null) : null
const mix = (a, b, t) => '#' + [1, 3, 5].map(offset => Math.round(parseInt(a.slice(offset, offset + 2), 16) * (1 - t) + parseInt(b.slice(offset, offset + 2), 16) * t).toString(16).padStart(2, '0')).join('')
function rainbow(t) {
  const h = ((t % 1) + 1) % 1 * 6
  const x = Math.round(255 * (1 - Math.abs(h % 2 - 1)))
  const rgb = [[255, x, 0], [x, 255, 0], [0, 255, x], [0, x, 255], [x, 0, 255], [255, 0, x]][Math.floor(h)]
  return '#' + rgb.map(v => v.toString(16).padStart(2, '0')).join('')
}
function tagSpec(raw) {
  const parts = raw.toLowerCase().split(':')
  let name = parts.shift()
  if (name === 'reset' && !parts.length) return { name, reset: true }
  const c = name === 'color' || name === 'colour' || name === 'c' ? (parts.length === 1 && color(parts[0])) : (!parts.length && color(name))
  if (c) return { name: name === 'colour' || name === 'c' ? 'color' : name, patch: { color: c, effect: null } }
  const negative = name.startsWith('!')
  if (negative) name = name.slice(1)
  if (Object.hasOwn(DECORATIONS, name) && (parts.length === 0 || (parts.length === 1 && ['true', 'false'].includes(parts[0])))) {
    return { name: DECORATIONS[name], patch: { [DECORATIONS[name]]: !negative && parts[0] !== 'false' } }
  }
  if (name === 'gradient') {
    let phase = 0
    if (parts.length && /^-?(?:\d+(?:\.\d*)?|\.\d+)$/.test(parts.at(-1))) phase = Number(parts.pop())
    const colors = parts.map(color)
    if (colors.length >= 2 && colors.every(Boolean) && Math.abs(phase) <= 1) return { name, patch: { effect: { kind: name, colors, phase }, color: null } }
  }
  if (name === 'rainbow' && parts.length <= 1 && parts[0] !== '') {
    const inverted = parts[0]?.startsWith('!') || false
    const rawPhase = (parts[0] || '').replace(/^!/, '')
    const phase = rawPhase === '' ? 0 : Number(rawPhase)
    if ((rawPhase === '' || /^-?(?:\d+(?:\.\d*)?|\.\d+)$/.test(rawPhase)) && Number.isFinite(phase)) return { name, patch: { effect: { kind: name, phase, inverted }, color: null } }
  }
  return null
}

export function parseMinecraftText(input, variables = {}, options = {}) {
  const warnings = new Set()
  const unresolved = new Set()
  let text = input == null ? '' : String(input)
  if (text.length > MAX_LENGTH) { text = text.slice(0, MAX_LENGTH); warnings.add('内容过长，仅预览前 16384 个字符。') }
  const chars = []
  let style = baseStyle()
  let stack = []
  function append(content, variable = false) {
    for (const char of String(content)) {
      if (chars.length >= MAX_LENGTH) { warnings.add('预览文字过长，已截断。'); return }
      if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(char)) { warnings.add('控制字符已在预览中替换。'); chars.push({ text: '�', style: { ...style }, variable }); continue }
      chars.push({ text: char, style: { ...style }, variable })
    }
  }
  for (let i = 0; i < text.length;) {
    if (text[i] === '\\' && ['<', '\\'].includes(text[i + 1])) { append(text[i + 1]); i += 2; continue }
    if (text[i] === '{') {
      const match = /^\{([a-zA-Z][\w.-]*)\}/.exec(text.slice(i))
      if (match) {
        const key = match[1]
        const known = Object.hasOwn(variables || {}, key) && ['string', 'number'].includes(typeof variables[key]) && (typeof variables[key] !== 'number' || Number.isFinite(variables[key]))
        if (options.atomicVariables) {
          if (!known) unresolved.add(key)
          chars.push({ text: known ? String(variables[key]) : match[0], style: { ...style }, variable: true })
        } else if (known) append(String(variables[key]), true)
        else { unresolved.add(key); append(match[0], true) }
        i += match[0].length; continue
      }
    }
    if (text[i] === '<') {
      const end = text.indexOf('>', i + 1)
      if (end !== -1) {
        const raw = text.slice(i + 1, end)
        if (raw.startsWith('/')) {
          const close = raw.slice(1).toLowerCase()
          const canonical = Object.hasOwn(DECORATIONS, close) ? DECORATIONS[close] : (['c', 'colour'].includes(close) ? 'color' : close)
          const index = stack.findLastIndex(frame => frame.name === canonical)
          if (index >= 0) { style = stack[index].before; stack = stack.slice(0, index); i = end + 1; continue }
        } else {
          const spec = tagSpec(raw)
          if (spec?.reset) { style = baseStyle(); stack = []; i = end + 1; continue }
          if (spec && stack.length < 64) { stack.push({ name: spec.name, before: { ...style } }); style = { ...style, ...spec.patch }; i = end + 1; continue }
        }
        warnings.add('存在不支持、格式错误或未匹配的标签，已按原文显示；交互与 HTML 不会执行。')
        append(text.slice(i, end + 1)); i = end + 1; continue
      }
      warnings.add('存在未闭合的标签，已按原文显示。')
    }
    if (options.legacy !== false && (text[i] === '&' || text[i] === '§')) {
      const tail = text.slice(i)
      const expanded = /^[&§]x(?:[&§][0-9a-f]){6}/i.exec(tail)
      const hex = /^[&§]#[0-9a-f]{6}/i.exec(tail)
      if (expanded || hex) {
        const match = expanded?.[0] || hex[0]
        const c = expanded ? '#' + match.slice(2).replace(/[&§]/g, '') : match.slice(1)
        style = { ...baseStyle(), color: c.toLowerCase() }; i += match.length; continue
      }
      const code = (text[i + 1] || '').toLowerCase()
      if (/^[0-9a-f]$/.test(code)) { style = { ...baseStyle(), color: LEGACY[parseInt(code, 16)] }; i += 2; continue }
      const decoration = { l: 'bold', o: 'italic', n: 'underlined', m: 'strikethrough' }[code]
      if (decoration) { style = { ...style, [decoration]: true }; i += 2; continue }
      if (code === 'r') { style = baseStyle(); stack = []; i += 2; continue }
      if (/^[a-z#]$/i.test(code)) warnings.add('存在不支持或无效的 legacy 格式码，已按原文显示。')
    }
    const point = String.fromCodePoint(text.codePointAt(i))
    append(point); i += point.length
  }
  const counts = new Map()
  for (const char of chars) if (char.style.effect && char.text !== '\n' && char.text !== '\r') counts.set(char.style.effect, (counts.get(char.style.effect) || 0) + 1)
  const positions = new Map()
  const segments = []
  for (const char of chars) {
    const effect = char.style.effect
    let renderedColor = char.style.color
    if (effect && char.text !== '\n' && char.text !== '\r') {
      const position = positions.get(effect) || 0
      positions.set(effect, position + 1)
      const t = counts.get(effect) <= 1 ? 0 : position / (counts.get(effect) - 1)
      if (effect.kind === 'rainbow') renderedColor = rainbow((effect.inverted ? -t : t) + effect.phase)
      else {
        // MiniMessage gradient phases wrap by reflecting at the two endpoints.
        const shifted = ((t + effect.phase) % 2 + 2) % 2
        const p = (shifted > 1 ? 2 - shifted : shifted) * (effect.colors.length - 1)
        const index = Math.min(Math.floor(p), effect.colors.length - 2)
        renderedColor = mix(effect.colors[index], effect.colors[index + 1], p - index)
      }
    }
    const css = {
      ...(renderedColor ? { color: renderedColor } : {}),
      ...(char.style.bold ? { fontWeight: '700' } : {}),
      ...(char.style.italic ? { fontStyle: 'italic' } : {}),
      ...((char.style.underlined || char.style.strikethrough) ? { textDecorationLine: [char.style.underlined && 'underline', char.style.strikethrough && 'line-through'].filter(Boolean).join(' ') } : {})
    }
    const previous = segments.at(-1)
    const key = JSON.stringify(css) + char.variable
    if (previous?.key === key) previous.text += char.text
    else segments.push({ text: char.text, style: css, variable: char.variable, key })
  }
  return { segments, warnings: [...warnings], unresolved: [...unresolved] }
}

// Public-facing text fields only. Explicit false opts out; sensitive identifiers
// and non-text types never qualify, even when preview is requested.
export function shouldPreviewField(field = {}) {
  const type = String(field.type || 'text').toLowerCase()
  if (!['text', 'textarea'].includes(type) || field.preview === false) return false
  const identity = [field.key, field.name, field.id, field.label].filter(Boolean).join(' ')
  if (/password|passwd|secret|token|command|url|uri|e-?mail|endpoint|webhook|密码|口令|密钥|令牌|命令|网址|链接|邮箱|邮件/i.test(identity)) return false
  if (field.preview === true || field.preview === 'minecraft' || (field.preview && typeof field.preview === 'object')) return true
  return /motd|announcement|broadcast|welcome|join.?message|kick.?message|deny.?message|denial|server.?description|公告|广播|欢迎|拒绝提示|踢出提示|入服提示|服务器简介/i.test(identity)
}

export function numberMetadata(field = {}) {
  const finite = value => value !== '' && value !== null && value !== undefined && Number.isFinite(Number(value)) ? Number(value) : undefined
  const min = finite(field.min)
  const max = finite(field.max)
  const suppliedStep = finite(field.step)
  return { min, max, step: suppliedStep > 0 ? suppliedStep : 1, bounded: min !== undefined && max !== undefined && min < max }
}
