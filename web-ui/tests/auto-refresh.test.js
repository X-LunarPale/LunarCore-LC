import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as panel from '../src/utils/panel.js'
import * as format from '../src/utils/format.js'
import { t } from '../src/i18n.js'

class TrackedTarget extends EventTarget {
  listeners = new Map()
  addEventListener(type, listener, options) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(listener)
    super.addEventListener(type, listener, options)
  }
  removeEventListener(type, listener, options) {
    this.listeners.get(type)?.delete(listener)
    super.removeEventListener(type, listener, options)
  }
  get listenerCount() { return [...this.listeners.values()].reduce((total, listeners) => total + listeners.size, 0) }
}

globalThis.window = Object.assign(new TrackedTarget(), { location: new URL('https://panel.example/') })
globalThis.document = Object.assign(new TrackedTarget(), { hidden: false })
const { api, resolveEndpoint, socket } = await import('../src/api.js')
const { useAutoRefresh } = await import('../src/utils/autoRefresh.js')

const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [] }),
  createText: text => ({ text }),
  createComment: text => ({ text }),
  insert(node, parent) { node.parent = parent; parent.children.push(node) },
  remove(node) { node.parent?.children.splice(node.parent.children.indexOf(node), 1) },
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text },
  parentNode: node => node.parent,
  nextSibling: () => null,
  patchProp() {}
})

async function settle() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await vue.nextTick()
}

function environment(context) {
  globalThis.window = Object.assign(new TrackedTarget(), { location: new URL('https://panel.example/') })
  globalThis.document = Object.assign(new TrackedTarget(), { hidden: false })
  socket.connected = true
  let now = 0, sequence = 0
  const timers = new Map()
  context.mock.method(globalThis, 'setTimeout', (callback, delay) => {
    const id = ++sequence
    timers.set(id, { callback, at: now + delay })
    return id
  })
  context.mock.method(globalThis, 'clearTimeout', id => timers.delete(id))
  function tick(milliseconds) {
    const until = now + milliseconds
    let next
    while ((next = [...timers].filter(([, timer]) => timer.at <= until).sort((a, b) => a[1].at - b[1].at)[0])) {
      now = next[1].at
      timers.delete(next[0])
      next[1].callback()
    }
    now = until
  }
  function mount(component) {
    const app = renderer.createApp(component)
    app.mount({ children: [] })
    context.after(() => { if (app._instance) app.unmount() })
    return app
  }
  function poll(refresh, options = {}) {
    return mount({ setup() { useAutoRefresh(refresh, options); return () => null } })
  }
  function changed(namespace) {
    const event = new Event('lbr:panel-changed')
    event.detail = { namespace }
    window.dispatchEvent(event)
  }
  return { timers, tick, mount, poll, changed }
}

async function scriptComponent(path) {
  const { descriptor } = parse(await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: path, genDefaultAs: '__component' })
  const scope = {
    ...vue, ...panel, ...format, api, resolveEndpoint, useAutoRefresh, t,
    SectionError: {}, FieldInput: {}, PlayerCell: {},
    useAuth: () => ({ can: () => true }),
    onBeforeRouteLeave() {}, onBeforeRouteUpdate() {},
    ElMessage: { success() {}, error() {}, warning() {} },
    ElMessageBox: { confirm: async () => true }
  }
  const body = script.content.replace(/^import .*$/gm, '')
  const names = Object.keys(script.imports)
  for (const name of names) assert.ok(Object.hasOwn(scope, name), `Missing test import: ${name}`)
  const component = new Function(...names, `${body}; return __component`)(...names.map(name => scope[name]))
  component.render = () => null
  return component
}

test('initial load runs once when the socket is already connected', async context => {
  const env = environment(context)
  let calls = 0, finish
  env.poll(() => { calls++; return new Promise(resolve => { finish = resolve }) }, { immediate: true })
  assert.equal(calls, 1)
  env.tick(60000)
  assert.equal(calls, 1)
  finish()
  await settle()
  env.tick(4999)
  assert.equal(calls, 1)
  env.tick(1)
  assert.equal(calls, 2)
  finish()
  await settle()
})

test('the first socket open shares an in-flight initial load', async context => {
  const env = environment(context)
  socket.setConnected(false)
  let calls = 0, finish
  env.poll(() => { calls++; return new Promise(resolve => { finish = resolve }) }, {
    immediate: true, seconds: () => 0
  })
  assert.equal(calls, 1)
  socket.setConnected(true)
  env.tick(60000)
  assert.equal(calls, 1)
  finish()
  await settle()
  env.tick(60000)
  assert.equal(calls, 1)
  assert.equal(env.timers.size, 0)
})

test('the first socket open still refreshes when immediate loading is disabled', async context => {
  const env = environment(context)
  socket.setConnected(false)
  let calls = 0
  env.poll(() => { calls++ }, { seconds: () => 0 })
  assert.equal(calls, 0)
  socket.setConnected(true)
  env.tick(50)
  await settle()
  assert.equal(calls, 1)
  assert.equal(env.timers.size, 0)
})

test('reconnects during a failed refresh queue one recovery when periodic refresh is disabled', async context => {
  const env = environment(context)
  let calls = 0, fail
  env.poll(() => {
    calls++
    if (calls === 1) return new Promise((_resolve, reject) => { fail = reject })
  }, { immediate: true, seconds: () => 0 })
  for (let i = 0; i < 3; i++) {
    socket.setConnected(false)
    socket.setConnected(true)
  }
  env.tick(60000)
  assert.equal(calls, 1)
  fail(new Error('connection lost'))
  await settle()
  env.tick(49)
  assert.equal(calls, 1)
  env.tick(1)
  await settle()
  assert.equal(calls, 2)
  assert.equal(env.timers.size, 0)
  env.tick(60000)
  assert.equal(calls, 2)
})

test('a reconnect after the first socket open queues behind the initial load', async context => {
  const env = environment(context)
  socket.setConnected(false)
  let calls = 0, finish
  env.poll(() => {
    calls++
    if (calls === 1) return new Promise(resolve => { finish = resolve })
  }, { immediate: true, seconds: () => 0 })
  socket.setConnected(true)
  socket.setConnected(false)
  socket.setConnected(true)
  finish()
  await settle()
  env.tick(50)
  await settle()
  assert.equal(calls, 2)
  assert.equal(env.timers.size, 0)
})

test('focus, visibility and matching mutations share one refresh and queue only one follow-up', async context => {
  const env = environment(context)
  let calls = 0, finish
  const active = vue.ref(true)
  env.poll(() => { calls++; return new Promise(resolve => { finish = resolve }) }, { namespace: 'chat', active: () => active.value })
  env.changed('other')
  env.tick(50)
  assert.equal(calls, 0)
  window.dispatchEvent(new Event('focus'))
  document.dispatchEvent(new Event('visibilitychange'))
  env.changed('chat')
  env.tick(50)
  assert.equal(calls, 1)
  document.hidden = true
  document.dispatchEvent(new Event('visibilitychange'))
  assert.equal(env.timers.size, 0)
  document.hidden = false
  document.dispatchEvent(new Event('visibilitychange'))
  active.value = false
  await settle()
  active.value = true
  await settle()
  window.dispatchEvent(new Event('focus'))
  env.changed('chat')
  env.tick(60000)
  assert.equal(calls, 1)
  finish()
  await settle()
  env.tick(50)
  assert.equal(calls, 2)
  finish()
})

test('hidden and inactive views retain no polling timers and refresh once when restored', async context => {
  const env = environment(context)
  const active = vue.ref(false)
  let calls = 0
  env.poll(() => { calls++ }, { active: () => active.value, immediate: true })
  assert.equal(env.timers.size, 0)
  active.value = true
  await settle()
  env.tick(50)
  await settle()
  assert.equal(calls, 1)
  document.hidden = true
  document.dispatchEvent(new Event('visibilitychange'))
  assert.equal(env.timers.size, 0)
  env.tick(60000)
  assert.equal(calls, 1)
  document.hidden = false
  document.dispatchEvent(new Event('visibilitychange'))
  window.dispatchEvent(new Event('focus'))
  env.tick(50)
  await settle()
  assert.equal(calls, 2)
  active.value = false
  await settle()
  assert.equal(env.timers.size, 0)
})

test('interval changes apply without remounting and zero disables only periodic refresh', async context => {
  const env = environment(context)
  const seconds = vue.ref(5)
  let calls = 0
  env.poll(() => { calls++ }, { seconds: () => seconds.value })
  seconds.value = 12
  await settle()
  env.tick(5000)
  assert.equal(calls, 0)
  env.tick(7000)
  await settle()
  assert.equal(calls, 1)
  seconds.value = 0
  await settle()
  assert.equal(env.timers.size, 0)
  window.dispatchEvent(new Event('focus'))
  env.tick(50)
  await settle()
  assert.equal(calls, 2)
  assert.equal(env.timers.size, 0)
})

test('unmount removes listeners, queued work and timers even while a refresh is pending', async context => {
  const env = environment(context)
  let finish, calls = 0
  const subscribers = socket.statusListeners.size
  const app = env.poll(() => { calls++; return new Promise(resolve => { finish = resolve }) }, { immediate: true })
  env.changed('chat')
  assert.equal(socket.statusListeners.size, subscribers + 1)
  app.unmount()
  assert.equal(socket.statusListeners.size, subscribers)
  assert.equal(window.listenerCount, 0)
  assert.equal(document.listenerCount, 0)
  finish()
  await settle()
  assert.equal(env.timers.size, 0)
  env.tick(60000)
  assert.equal(calls, 1)
})

test('a failed refresh releases the in-flight slot and keeps future polling available', async context => {
  const env = environment(context)
  let calls = 0
  env.poll(async () => { calls++; throw new Error('network unavailable') }, { immediate: true })
  await settle()
  env.tick(5000)
  await settle()
  assert.equal(calls, 2)
})

test('hidden form groups do not load or poll, and dirty drafts survive reactivation', async context => {
  const env = environment(context)
  const component = await scriptComponent('components/panel/FormSection.vue')
  const activeGroup = vue.ref(0)
  let calls = 0, serverValue = 'first'
  context.mock.method(api, 'get', async () => { calls++; return { value: serverValue } })
  const section = { loadEndpoint: 'config', fields: [{ key: 'value', type: 'text' }] }
  const app = env.mount({ render: () => vue.h('div', Array.from({ length: 3 }, (_, index) =>
    vue.h(component, { key: index, section, namespace: 'demo', active: activeGroup.value === index }))) })
  await settle()
  assert.equal(calls, 1)
  const first = app._instance.subTree.children[0].component.setupState
  first.model.value = 'my draft'
  await settle()
  assert.equal(env.timers.size, 0)
  activeGroup.value = 1
  await settle()
  env.tick(50)
  await settle()
  assert.equal(calls, 2)
  serverValue = 'external edit'
  env.tick(5000)
  await settle()
  assert.equal(calls, 3)
  const second = app._instance.subTree.children[1].component.setupState
  assert.equal(second.model.value, 'external edit')
  activeGroup.value = 0
  await settle()
  env.tick(60000)
  await settle()
  assert.equal(first.model.value, 'my draft')
  assert.equal(calls, 3)
})

test('saving a form reads back normalized values once through the mutation refresh', async context => {
  const env = environment(context)
  const component = await scriptComponent('components/panel/FormSection.vue')
  let calls = 0, serverValue = 'before'
  context.mock.method(api, 'get', async () => { calls++; return { value: serverValue } })
  context.mock.method(api, 'post', async (_path, body) => { serverValue = body.value.toUpperCase(); return { ok: true } })
  globalThis.CustomEvent = class extends Event { constructor(type, options) { super(type); this.detail = options.detail } }
  const mounted = env.mount({ render: () => vue.h(component, {
    namespace: 'demo', section: { loadEndpoint: 'config', saveEndpoint: 'config', fields: [{ key: 'value' }] }
  }) })
  await settle()
  const state = mounted._instance.subTree.component.setupState
  state.model.value = 'after'
  await settle()
  await state.save()
  await settle()
  env.tick(50)
  await settle()
  assert.equal(calls, 2)
  assert.equal(state.model.value, 'AFTER')
  assert.equal(state.dirty, false)
})

test('a background response cannot replace edits made while it was in flight', async context => {
  const env = environment(context)
  const component = await scriptComponent('components/panel/FormSection.vue')
  let calls = 0, finish
  context.mock.method(api, 'get', () => ++calls === 1 ? Promise.resolve({ value: 'initial' })
    : new Promise(resolve => { finish = resolve }))
  const app = env.mount({ render: () => vue.h(component, {
    namespace: 'demo', section: { loadEndpoint: 'config', fields: [{ key: 'value' }] }
  }) })
  await settle()
  const state = app._instance.subTree.component.setupState
  env.tick(5000)
  assert.equal(calls, 2)
  state.model.value = 'my new draft'
  await settle()
  finish({ value: 'late server snapshot' })
  await settle()
  assert.equal(state.model.value, 'my new draft')
  assert.equal(state.dirty, true)
  assert.equal(env.timers.size, 0)
})

test('players fetch history on first visit and suspend online polling in hidden tabs', async context => {
  const env = environment(context)
  const component = await scriptComponent('views/Players.vue')
  const calls = []
  context.mock.method(api, 'get', async path => { calls.push(path); return { items: [], total: 0 } })
  const app = env.mount(component)
  await settle()
  assert.deepEqual(calls, ['/api/players/online'])
  const state = app._instance.setupState
  state.tab = 'history'
  await settle()
  assert.deepEqual(calls, ['/api/players/online', '/api/players/history'])
  env.tick(60000)
  await settle()
  assert.equal(calls.length, 2)
  state.tab = 'online'
  await settle()
  env.tick(50)
  await settle()
  assert.equal(calls.length, 3)
  document.hidden = true
  document.dispatchEvent(new Event('visibilitychange'))
  env.tick(60000)
  assert.equal(calls.length, 3)
  state.tab = 'history'
  await settle()
  assert.equal(calls.filter(path => path.endsWith('/history')).length, 1)
})
