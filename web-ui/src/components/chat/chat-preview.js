import { parseMinecraftText } from '../text-preview/minecraft-text.js'

const literal = value => String(value ?? '').replaceAll('\\', '\\\\').replaceAll('<', '\\<')
function titleText(value) {
  return parseMinecraftText(value, {}, { legacy: true }).segments.map(segment => {
    const tags = []
    if (segment.style.color) tags.push(segment.style.color)
    if (segment.style.fontWeight) tags.push('bold')
    if (segment.style.fontStyle) tags.push('italic')
    if (segment.style.textDecorationLine?.includes('underline')) tags.push('underlined')
    if (segment.style.textDecorationLine?.includes('line-through')) tags.push('strikethrough')
    return tags.map(tag => `<${tag}>`).join('') + literal(segment.text) + tags.toReversed().map(tag => `</${tag}>`).join('')
  }).join('')
}

// Remove interaction tags while retaining their visible children.
function visibleTemplate(input) {
  let result = ''
  for (let index = 0; index < input.length;) {
    if (input[index] !== '<') { result += input[index++]; continue }
    let end = index + 1, quote = ''
    for (; end < input.length; end++) {
      const char = input[end]
      if (char === '\\') { end++; continue }
      if (quote) { if (char === quote) quote = '' }
      else if (char === '"' || char === "'") quote = char
      else if (char === '>') break
    }
    if (end === input.length) { result += input.slice(index); break }
    const tag = input.slice(index + 1, end)
    if (!/^\/?(?:hover|click|insertion|insert|font)(?::|$)/i.test(tag)) result += input.slice(index, end + 1)
    index = end + 1
  }
  return result
}

export function chatPreview(format, { world = 'world', worldName = world, color = '#55ffff', player = 'Steve', title = '&6[旅行者]', message = '大家好，今天一起探险吗？', titlePlaceholder = '%playerTitle_use%', showTitle = true } = {}) {
  let template = visibleTemplate(String(format || ''))
  for (const token of [titlePlaceholder, '%playerTitle_use%', '%lc_playertitle_current%', '%lc_titles_title%']) {
    if (token) template = template.replaceAll(token, '{title}')
  }
  const replacements = {
    world: `<${/^#[0-9a-f]{6}$/i.test(color) || /^[a-z_]+$/.test(color) ? color : '#55ffff'}>${literal(worldName)}</${/^#[0-9a-f]{6}$/i.test(color) || /^[a-z_]+$/.test(color) ? color : '#55ffff'}>`,
    world_name: literal(world), title: showTitle ? titleText(title) : '', player: literal(player),
    displayname: literal(player), message: literal(message)
  }
  if (!showTitle || !title.trim()) template = template.replace(/(?:\{title}|<title>)\s*/g, '')
  return template.replace(/\{(world|world_name|title|player|displayname|message)}|<(world|title|displayname|message)>/g,
    (_, curly, tag) => replacements[curly || tag])
}
