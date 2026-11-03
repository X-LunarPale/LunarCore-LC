import assert from 'node:assert/strict'
import { test } from 'node:test'
import { buildNavigation, navigationGroup } from '../src/utils/navigation.js'

const core = [
  { path: '/', title: '仪表盘', perm: 'core.dashboard', group: '概览' },
  { path: '/players', title: '玩家', perm: 'core.players.view', group: '概览' },
  { path: '/console', title: '控制台', perm: 'core.console.view', group: '概览' },
  { path: '/accounts', title: '账号与角色', perm: 'core.users.manage', group: '系统' }
]
const panel = (namespace, group = '模块', order = 100) => ({ namespace, id: namespace, title: namespace, group, order })
const keys = groups => groups.flatMap(group => group.items.map(item => item.key))

test('restricted accounts retain only allowed core entries and supplied panels', () => {
  const allowed = new Set(['core.dashboard', 'core.players.view'])
  const groups = buildNavigation(core, [], permission => allowed.has(permission))
  assert.deepEqual(groups.map(group => group.id), ['overview'])
  assert.deepEqual(keys(groups), ['/', '/players'])
})

test('navigation order stays stable across panel refresh ordering and language changes', () => {
  const panels = [panel('terrain'), panel('journal'), panel('performance'), panel('landmarks'), panel('economy')]
  const first = buildNavigation(core, panels, () => true)
  const second = buildNavigation(core, [...panels].reverse(), () => true, text => `translated:${text}`)
  assert.deepEqual(first.map(group => group.id), ['overview', 'server', 'survival', 'system'])
  assert.deepEqual(keys(first), keys(second))
  assert.deepEqual(first.find(group => group.id === 'survival').items.map(item => item.key),
    ['/panel/economy/economy', '/panel/journal/journal', '/panel/landmarks/landmarks'])
})

test('anti-cheat merges only when the ban workspace is available to the account', () => {
  const anticheat = panel('anticheat', '安全')
  const alone = buildNavigation([], [anticheat], () => true)
  assert.deepEqual(keys(alone), ['/panel/anticheat/anticheat'])
  const merged = buildNavigation([], [anticheat, panel('ban', '安全')], () => true)
  assert.deepEqual(keys(merged), ['/panel/ban/ban'])
  assert.equal(merged[0].items[0].title, '封禁与反作弊')
})

test('search finds collapsed groups in either language and retains source group aliases', () => {
  const panels = [{ ...panel('land', '经济'), title: '领地系统' }, panel('journal', '生存')]
  const translate = text => ({ 生存玩法: 'Survival', 领地系统: 'Claims' })[text] || text
  assert.equal(keys(buildNavigation([], panels, () => true, translate, 'survival')).length, 2)
  assert.deepEqual(keys(buildNavigation([], panels, () => true, translate, '领地')), ['/panel/land/land'])
  assert.deepEqual(keys(buildNavigation([], panels, () => true, translate, '经济')), ['/panel/land/land'])
  assert.deepEqual(buildNavigation([], panels, () => true, translate, 'no match'), [])
})

test('unknown extensions remain reachable and duplicate routes appear once', () => {
  const addon = panel('third-party', '自定义扩展')
  const groups = buildNavigation([], [addon, { ...addon }, panel('another')], () => true)
  assert.equal(groups[0].id, 'extensions')
  assert.equal(keys(groups).length, 2)
  assert.ok(keys(groups).includes('/panel/third-party/third-party'))
  assert.deepEqual(keys(buildNavigation([], [addon], () => true, undefined, '自定义扩展')), ['/panel/third-party/third-party'])
})

test('page breadcrumbs share the same stable navigation category', () => {
  assert.equal(navigationGroup({ namespace: 'land', group: '经济' }).title, '生存玩法')
  assert.equal(navigationGroup({ namespace: 'permissions', group: '管理' }).title, '系统')
  assert.equal(navigationGroup({ path: '/console', group: '概览' }).title, '服务器')
})
