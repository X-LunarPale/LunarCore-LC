const definitions = [
  { id: 'overview', title: '概览', icon: 'layout-dashboard', keys: ['/', '/players'] },
  { id: 'server', title: '服务器', icon: 'server', keys: ['/console', '/server', 'performance', 'world', 'multiverse', 'terrain', 'motd'] },
  { id: 'players', title: '玩家服务', icon: 'users', keys: ['essentials', 'teleport', 'chat', 'titles', 'playertitle', 'scoreboard'] },
  { id: 'survival', title: '生存玩法', icon: 'compass', keys: ['economy', 'signin', 'land', 'journal', 'landmarks', 'vote'] },
  { id: 'security', title: '安全', icon: 'shield-check', keys: ['ban', 'anticheat', 'antixray', 'qqauth'] },
  { id: 'system', title: '系统', icon: 'settings-2', keys: ['/modules', '/plugins', '/config', 'permissions', '/accounts', '/audit'] },
  { id: 'extensions', title: '扩展模块', icon: 'blocks', keys: [] }
]

const byKey = new Map(definitions.flatMap(group => group.keys.map(key => [key, group])))
const byLegacyGroup = new Map([
  ['概览', 'overview'], ['服务器', 'server'], ['管理', 'server'],
  ['玩家', 'players'], ['玩家服务', 'players'],
  ['经济', 'survival'], ['玩法', 'survival'], ['生存', 'survival'], ['生存玩法', 'survival'],
  ['安全', 'security'], ['系统', 'system']
])

export function navigationGroup(item) {
  return byKey.get(item.namespace || item.path)
    || definitions.find(group => group.id === byLegacyGroup.get(item.group))
    || definitions[definitions.length - 1]
}

export function buildNavigation(coreMenus, panels, can, translate = value => value, query = '') {
  const groups = new Map(definitions.map(group => [group.id, { ...group, items: [] }]))
  const hasBan = panels.some(panel => panel.namespace === 'ban')
  const seen = new Set()
  const add = (source, key) => {
    if (seen.has(key)) return
    seen.add(key)
    const group = navigationGroup(source)
    const title = source.namespace === 'ban' && hasBan && panels.some(panel => panel.namespace === 'anticheat')
      ? '封禁与反作弊' : source.title
    const rank = group.keys.indexOf(source.namespace || source.path)
    groups.get(group.id).items.push({
      key, title: translate(title), icon: source.icon || 'box',
      rank: rank < 0 ? group.keys.length : rank,
      order: Number.isFinite(source.order) ? source.order : 100,
      search: [title, translate(title), source.group, translate(source.group || ''), source.namespace || '', key].join(' ').toLocaleLowerCase()
    })
  }
  for (const menu of coreMenus) {
    if (can(menu.perm)) add(menu, menu.path)
  }
  for (const panel of panels) {
    if (panel.namespace === 'anticheat' && hasBan) continue
    add(panel, `/panel/${panel.namespace}/${panel.id}`)
  }
  const needle = query.trim().toLocaleLowerCase()
  return [...groups.values()].map(group => ({
    id: group.id, title: translate(group.title), icon: group.icon,
    items: group.items
      .sort((a, b) => a.rank - b.rank || a.order - b.order || a.key.localeCompare(b.key))
      .filter(item => !needle || `${group.title} ${translate(group.title)} ${item.search}`.toLocaleLowerCase().includes(needle))
  })).filter(group => group.items.length)
}
