import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { chatPreview, nameplatePreview } from '../src/components/chat/chat-preview.js'
import { parseMinecraftText } from '../src/components/text-preview/minecraft-text.js'
import { t } from '../src/i18n.js'

const filename = 'ChatAppearance.vue'
const source = await readFile(new URL('../src/components/chat/ChatAppearance.vue', import.meta.url), 'utf8')
const { descriptor, errors } = parse(source, { filename })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: filename, genDefaultAs: '__component' })
const template = compileTemplate({
  source: descriptor.template.content, filename, id: filename,
  compilerOptions: { mode: 'function', bindingMetadata: script.bindings }
})
assert.deepEqual(template.errors, [])

const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [], props: {} }),
  createText: text => ({ text }),
  createComment: text => ({ comment: text }),
  insert(node, parent, anchor) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
  },
  remove(node) { node.parent?.children.splice(node.parent.children.indexOf(node), 1) },
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text; node.children = [] },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  patchProp(node, key, previous, value) { node.props[key] = value }
})

const Preview = {
  props: ['text', 'label'],
  setup: props => () => vue.h('text-preview', { text: props.text, label: props.label })
}
const scope = { ...vue, chatPreview, nameplatePreview, t, MinecraftTextPreview: Preview }
const names = Object.keys(script.imports)
for (const name of names) assert.ok(Object.hasOwn(scope, name), `Missing import: ${name}`)
const component = new Function(...names, `${script.content.replace(/^import .*$/gm, '')}; return __component`)(...names.map(name => scope[name]))
component.render = new Function('Vue', template.code)(vue)

function mount(context, values = {}) {
  const model = vue.reactive({
    worldAliases: [{ world: 'world', name: '生存世界', color: '#55ff55' }],
    defaultChatFormat: '{world} {title} {player}: <message>',
    chatShowTitle: false, tabEnabled: true, tabShowTitle: true,
    tabName: '{world} {title} {player}', nameplateEnabled: true, ...values
  })
  const app = renderer.createApp(component, { model })
  const stub = { setup: (_, { slots }) => () => vue.h('stub-control', slots.default?.()) }
  for (const name of ['el-button', 'el-input', 'el-color-picker', 'el-tooltip', 'el-select', 'el-option']) app.component(name, stub)
  app.component('Icon', { render: () => null })
  const root = { children: [] }
  app.mount(root)
  context.after(() => app.unmount())
  return { model, root, state: app._instance.setupState }
}

const plain = value => parseMinecraftText(value).segments.map(segment => segment.text).join('')
function descendants(node) { return [node, ...(node.children || []).flatMap(descendants)] }
function warnings(root) {
  return descendants(root).filter(node => node.type === 'p' && node.props.class === 'preview-warning').map(node => node.text)
}

test('the actual appearance template warns about missing TAB titles without rewriting a custom format', async context => {
  const format = '<aqua>{world}</aqua> {player}'
  const { model, root, state } = mount(context, { tabName: format })
  assert.equal(state.missingTabTitle, true)
  assert.deepEqual(warnings(root), ['TAB 名称格式缺少 {title}，当前称号不会显示。'])
  assert.equal(model.tabName, format)
  model.tabName = '{world} {title} {player}'
  await vue.nextTick()
  assert.equal(state.missingTabTitle, false)
  assert.deepEqual(warnings(root), [])
})

test('recognized title tags and configured PAPI tokens do not produce a missing-title warning', async context => {
  const { model, root, state } = mount(context, { titlePlaceholder: '%custom_title%' })
  for (const token of ['{title}', '<title>', '%playerTitle_use%', '%lc_playertitle_current%', '%lc_titles_title%', '%custom_title%']) {
    model.tabName = `${token} {player}`
    await vue.nextTick()
    assert.equal(state.missingTabTitle, false, token)
    assert.deepEqual(warnings(root), [], token)
    assert.equal(plain(state.tab), '[旅行者] Steve', token)
  }
})

test('disabled TAB titles and blank opt-out templates do not warn or change saved choices', async context => {
  const { model, root, state } = mount(context, { tabName: '{player}', tabShowTitle: false })
  assert.deepEqual(warnings(root), [])
  assert.equal(plain(state.tab), 'Steve')
  model.tabShowTitle = true
  model.tabEnabled = false
  await vue.nextTick()
  assert.deepEqual(warnings(root), [])
  for (const format of ['', '   ']) {
    model.tabEnabled = true
    model.tabName = format
    await vue.nextTick()
    assert.equal(Boolean(state.missingTabTitle), false)
    assert.deepEqual(warnings(root), [])
    assert.equal(model.tabName, format)
  }
})

test('chat stays titleless while TAB and the overhead preview retain the current title', context => {
  const { root, state } = mount(context)
  assert.equal(plain(state.chat), '生存世界 Steve: 大家好，今天一起探险吗？')
  assert.equal(plain(state.tab), '生存世界 [旅行者] Steve')
  assert.equal(plain(state.nameplate), '[旅行者]\nSteve')
  const previews = descendants(root).filter(node => node.type === 'text-preview')
  assert.deepEqual(previews.map(node => node.props.label), ['聊天预览', 'TAB 玩家名称预览', '头顶称号预览'])
  assert.deepEqual(previews.map(node => plain(node.props.text)), [plain(state.chat), plain(state.tab), plain(state.nameplate)])
})
