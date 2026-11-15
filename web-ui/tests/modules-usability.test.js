import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { requireSuccess } from '../src/utils/panel.js'
import { t } from '../src/i18n.js'

const filename = 'Modules.vue'
const source = await readFile(new URL('../src/views/Modules.vue', import.meta.url), 'utf8')
const { descriptor, errors } = parse(source, { filename })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: filename, genDefaultAs: '__component' })
const template = compileTemplate({
  source: descriptor.template.content, filename, id: filename,
  compilerOptions: { mode: 'function', bindingMetadata: script.bindings }
})
assert.deepEqual(template.errors, [])

const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [] }),
  createText: text => ({ text }),
  createComment: text => ({ comment: text }),
  insert(node, parent) { node.parent = parent; parent.children.push(node) },
  remove(node) { node.parent?.children.splice(node.parent.children.indexOf(node), 1) },
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text },
  parentNode: node => node.parent,
  nextSibling: () => null,
  patchProp() {}
})

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const moduleRecord = { id: 'chat', name: '聊天', enabled: true }

function environment(context) {
  const events = { successes: [], errors: [], warnings: [] }
  const api = {
    get: async () => ({ items: [moduleRecord] }),
    post: async () => ({ ok: true })
  }
  const appStore = { loadPanels: async () => [] }
  const messageBox = { confirm: async () => true }
  let refresh
  const scope = {
    ...vue, api, requireSuccess, t,
    useAuth: () => ({ can: () => true }),
    useApp: () => appStore,
    useAutoRefresh(callback, options) { refresh = { callback, options } },
    ElMessage: {
      success: text => events.successes.push(text),
      error: text => events.errors.push(text),
      warning: text => events.warnings.push(text)
    },
    ElMessageBox: messageBox,
    ModuleMarket: {}
  }
  const names = Object.keys(script.imports)
  for (const name of names) assert.ok(Object.hasOwn(scope, name), `Missing import: ${name}`)
  const component = new Function(...names, `${script.content.replace(/^import .*$/gm, '')}; return __component`)(...names.map(name => scope[name]))
  component.render = () => null
  const mounted = renderer.createApp(component)
  mounted.mount({ children: [] })
  context.after(() => mounted.unmount())
  return { state: mounted._instance.setupState, api, appStore, messageBox, events, refresh }
}

async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await vue.nextTick()
}

test('module reads stay single-flight and retain the previous list after a failed or malformed refresh', async context => {
  const env = environment(context)
  const response = deferred()
  let calls = 0
  env.api.get = () => { calls++; return response.promise }
  const loading = env.state.load()
  assert.equal(env.state.loading, true)
  assert.equal(await env.state.load(), false)
  assert.equal(calls, 1)
  response.resolve({ items: [moduleRecord] })
  assert.equal(await loading, true)
  assert.equal(env.state.loaded, true)
  assert.deepEqual(env.state.items, [moduleRecord])
  env.api.get = async () => ({ items: [null] })
  assert.equal(await env.state.load(), false)
  assert.deepEqual(env.state.items, [moduleRecord])
  assert.match(env.state.error, /格式错误/)
  env.api.get = async () => { throw new Error('connection lost') }
  assert.equal(await env.state.load(), false)
  assert.deepEqual(env.state.items, [moduleRecord])
  assert.equal(env.state.error, 'connection lost')
  assert.equal(env.state.loading, false)
  assert.deepEqual(env.events.errors, [])
  env.api.get = async () => ({ items: [] })
  assert.equal(await env.state.load(), true)
  assert.deepEqual(env.state.items, [])
  assert.equal(env.state.error, '')
})

test('an initial module load failure is separate from a successful empty list', async context => {
  const env = environment(context)
  env.api.get = async () => ({ ok: false, error: 'not available' })
  assert.equal(await env.state.load(), false)
  assert.equal(env.state.loaded, false)
  assert.equal(env.state.error, 'not available')
  assert.deepEqual(env.state.items, [])
})

test('disable confirmation locks other module actions and cancellation releases the lock without a request', async context => {
  const env = environment(context)
  const confirmation = deferred()
  let posts = 0
  env.messageBox.confirm = () => confirmation.promise
  env.api.post = async () => { posts++; return { ok: true } }
  const pending = env.state.op(moduleRecord, 'disable')
  assert.equal(env.state.busy, 'chatdisable')
  assert.equal(env.refresh.options.active(), false)
  await env.state.op({ id: 'economy' }, 'enable')
  assert.equal(posts, 0)
  confirmation.reject(new Error('cancel'))
  await pending
  assert.equal(env.state.busy, '')
  assert.equal(env.refresh.options.active(), true)
  assert.equal(posts, 0)
  assert.deepEqual(env.events.errors, [])
})

test('the module lock stays active through mutation, list refresh, and navigation refresh', async context => {
  const env = environment(context)
  const mutation = deferred(), navigation = deferred()
  let posts = 0
  env.api.post = () => { posts++; return mutation.promise }
  env.appStore.loadPanels = () => navigation.promise
  const pending = env.state.op(moduleRecord, 'reload')
  assert.equal(env.state.busy, 'chatreload')
  await env.state.op({ id: 'economy' }, 'enable')
  assert.equal(posts, 1)
  mutation.resolve({ ok: true })
  await settle()
  assert.equal(env.state.busy, 'chatreload')
  await env.state.op({ id: 'economy' }, 'disable')
  assert.equal(posts, 1)
  navigation.resolve([])
  await pending
  assert.equal(env.state.busy, '')
  assert.equal(env.events.successes.length, 1)
})

test('a rejected module mutation never reports success or refreshes the navigation', async context => {
  const env = environment(context)
  let panels = 0
  env.api.post = async () => ({ ok: false, error: 'dependency missing' })
  env.appStore.loadPanels = async () => { panels++ }
  await env.state.op(moduleRecord, 'enable')
  assert.deepEqual(env.events.successes, [])
  assert.deepEqual(env.events.errors, ['dependency missing'])
  assert.equal(panels, 0)
  assert.equal(env.state.busy, '')
})

test('navigation refresh failures remain a warning after a confirmed successful module operation', async context => {
  const env = environment(context)
  env.appStore.loadPanels = async () => { throw new Error('menu unavailable') }
  await env.state.op(moduleRecord, 'reload')
  assert.equal(env.events.successes.length, 1)
  assert.deepEqual(env.events.errors, [])
  assert.equal(env.events.warnings.length, 1)
  assert.match(env.events.warnings[0], /menu unavailable/)
  assert.equal(env.state.busy, '')
  assert.equal(env.state.loaded, true)
})
