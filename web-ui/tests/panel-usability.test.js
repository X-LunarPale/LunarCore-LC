import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as panel from '../src/utils/panel.js'
import * as format from '../src/utils/format.js'
import { numberMetadata, shouldPreviewField } from '../src/components/text-preview/minecraft-text.js'
import { t } from '../src/i18n.js'

globalThis.window = { location: new URL('https://panel.example/') }
const { api, resolveEndpoint } = await import('../src/api.js')

const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [], props: {} }),
  createText: text => ({ text }),
  createComment: text => ({ comment: text }),
  insert(node, parent) { node.parent = parent; parent.children.push(node) },
  remove(node) { node.parent?.children.splice(node.parent.children.indexOf(node), 1) },
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text },
  parentNode: node => node.parent,
  nextSibling: () => null,
  patchProp(node, key, previous, value) { node.props[key] = value }
})

const formContextKey = Symbol('panel-form')
const CodeEditor = {
  props: ['modelValue', 'readonly'],
  setup: props => () => vue.h('test-code', { value: props.modelValue, readonly: props.readonly })
}

async function component(name, overrides = {}, render = false) {
  const { descriptor } = parse(await readFile(new URL(`../src/components/panel/${name}.vue`, import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: name, genDefaultAs: '__component' })
  const scope = {
    ...vue, ...panel, ...format, api, resolveEndpoint, t, formContextKey,
    numberMetadata, shouldPreviewField, CodeEditor,
    useAuth: () => ({ can: () => true }),
    useAutoRefresh() {},
    ElMessage: { success() {}, error() {}, warning() {} },
    ElMessageBox: { confirm: async () => true },
    ActionRunner: {}, CellValue: {}, FieldInput: {}, MinecraftTextPreview: {}, SectionError: {},
    ...overrides
  }
  const names = Object.keys(script.imports)
  for (const name of names) assert.ok(Object.hasOwn(scope, name), `Missing import: ${name}`)
  const definition = new Function(...names, `${script.content.replace(/^import .*$/gm, '')}; return __component`)(...names.map(name => scope[name]))
  if (render) {
    const template = compileTemplate({
      source: descriptor.template.content, filename: name, id: name,
      compilerOptions: { mode: 'function', bindingMetadata: script.bindings }
    })
    assert.deepEqual(template.errors, [])
    definition.render = new Function('Vue', template.code)(vue)
  } else definition.render = () => null
  return definition
}

function mount(context, definition, props) {
  const app = renderer.createApp(definition, props)
  for (const name of ['el-input-number', 'el-switch', 'el-option', 'el-select', 'el-input', 'el-date-picker', 'Icon']) {
    app.component(name, { render: () => null })
  }
  const root = { children: [] }
  const instance = app.mount(root)
  context.after(() => app.unmount())
  return { app, root, instance, state: app._instance.setupState }
}

async function settle() {
  await Promise.resolve()
  await vue.nextTick()
}

test('tables respect disabled polling and keep draft search text out of requests until submitted', async context => {
  let refresh
  const requests = []
  context.mock.method(api, 'get', async (_path, query) => {
    requests.push({ ...query })
    return { items: [], total: 0 }
  })
  const definition = await component('TableSection', {
    useAutoRefresh(callback, options) { refresh = { callback, options } }
  })
  const { state } = mount(context, definition, {
    namespace: 'demo', section: { endpoint: 'records', refreshSeconds: 0 }
  })
  assert.equal(refresh.options.seconds(), 0)
  state.s.q = ' draft '
  await refresh.callback()
  assert.equal(requests[0].q, '')
  state.search()
  await settle()
  assert.equal(requests[1].q, 'draft')
  state.s.q = 'different draft'
  await refresh.callback()
  assert.equal(requests[2].q, 'draft')
})

test('an open or executing row action pauses table refresh and exposes its busy state', async context => {
  let refresh, finish
  const requests = []
  context.mock.method(api, 'get', async () => { requests.push('get'); return { items: [], total: 0 } })
  const action = await component('ActionRunner', {
    request: () => new Promise(resolve => { requests.push('post'); finish = resolve })
  })
  const runner = mount(context, action, { namespace: 'demo' })
  const table = await component('TableSection', {
    useAutoRefresh(callback, options) { refresh = { callback, options } }
  })
  const { state } = mount(context, table, {
    namespace: 'demo', section: { endpoint: 'records', refreshSeconds: 8 }
  })
  state.runner = runner.instance
  assert.equal(refresh.options.active(), true)
  await runner.instance.run({ label: '编辑', endpoint: 'edit', fields: [{ key: 'value', type: 'text' }] }, { value: 'before' })
  assert.equal(runner.instance.busy, true)
  assert.equal(refresh.options.active(), false)
  await refresh.callback()
  assert.deepEqual(requests, [])
  runner.state.dlg.visible = false
  assert.equal(runner.instance.busy, false)
  const pending = runner.instance.run({ label: '执行', endpoint: 'run' }, {})
  assert.equal(runner.instance.busy, true)
  assert.equal(refresh.options.active(), false)
  await runner.instance.run({ label: '执行', endpoint: 'run' }, {})
  assert.deepEqual(requests, ['post'])
  finish({ ok: true })
  await pending
  assert.equal(runner.instance.busy, false)
  assert.equal(refresh.options.active(), true)
})

test('code fields follow the disabled state of the surrounding form', async context => {
  const definition = await component('FieldInput', {}, true)
  const disabled = vue.ref(false)
  const parent = {
    setup() {
      vue.provide(formContextKey, { disabled })
      return () => vue.h(definition, {
        field: { key: 'config', label: '配置', type: 'code' }, modelValue: 'enabled: true'
      })
    }
  }
  const { root } = mount(context, parent)
  const control = () => root.children.find(node => node.type === 'test-code')
  assert.equal(control().props.readonly, false)
  disabled.value = true
  await settle()
  assert.equal(control().props.readonly, true)
  disabled.value = false
  await settle()
  assert.equal(control().props.readonly, false)
})

test('failed list and metric refreshes retain the last successful data', async context => {
  let fail = false, refresh
  context.mock.method(api, 'get', async path => {
    if (fail) throw new Error('Temporary failure')
    return path.endsWith('stats') ? [{ label: 'Online', value: 8 }] : { items: [{ id: 'player-1' }], total: 1 }
  })
  const table = await component('TableSection')
  const metrics = await component('StatsSection', {
    useAutoRefresh(callback, options) { refresh = { callback, options } }
  })
  const list = mount(context, table, { namespace: 'demo', section: { endpoint: 'players' } }).state
  const stats = mount(context, metrics, { namespace: 'demo', section: { endpoint: 'stats', refreshSeconds: 0 } }).state
  assert.equal(refresh.options.seconds(), 0)
  await list.load()
  await refresh.callback()
  fail = true
  await list.load()
  await refresh.callback()
  assert.deepEqual(list.s.items, [{ id: 'player-1' }])
  assert.equal(list.s.total, 1)
  assert.equal(list.s.error.message, 'Temporary failure')
  assert.deepEqual(stats.items, [{ label: 'Online', value: 8 }])
  assert.equal(stats.error.message, 'Temporary failure')
})
