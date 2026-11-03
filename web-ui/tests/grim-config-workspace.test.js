import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as configuration from '../src/utils/grim-config.js'
import { requireSuccess } from '../src/utils/panel.js'
import { t } from '../src/i18n.js'

const source = await readFile(new URL('../src/components/moderation/GrimConfigWorkspace.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'grim-config-test', genDefaultAs: '__component' })
const body = script.content.replace(/^import .*$/gm, '')
const imports = Object.keys(script.imports)
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
class TrackedTarget extends EventTarget {
  listeners = new Set()
  addEventListener(type, listener, options) { this.listeners.add(listener); super.addEventListener(type, listener, options) }
  removeEventListener(type, listener, options) { this.listeners.delete(listener); super.removeEventListener(type, listener, options) }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
async function settle() {
  for (let i = 0; i < 12; i++) await Promise.resolve()
  await vue.nextTick()
}
function snapshot(id = 'config', values = { enabled: true, lines: ['', 'hello'], password: '' }) {
  return {
    id, name: `${id}.yml`, label: id, fingerprint: `version-${id}`, reloadRequired: true,
    fields: [
      { key: 'enabled', label: '启用', group: '检测', type: 'bool' },
      { key: 'lines', label: '消息', group: '消息', type: 'string-list' },
      { key: 'password', label: '密码', group: '连接', type: 'text', secret: true, configured: true },
      { key: 'config-flavor', label: '配置模式', type: 'text', readOnly: true }
    ],
    values: { ...values, 'config-flavor': 'V2' }
  }
}
async function environment(context, active = true) {
  const document = Object.assign(new TrackedTarget(), { hidden: false })
  const window = new TrackedTarget()
  globalThis.document = document
  globalThis.window = window
  const allowed = vue.ref(true)
  const activeProp = vue.ref(active)
  const calls = []
  const api = {
    get: async path => {
      calls.push(['get', path])
      if (path.endsWith('/grim/config')) return {
        files: [{ id: 'config', name: 'config.yml', label: '检测配置', available: true }, { id: 'messages', name: 'messages.yml', label: '消息配置', available: true }],
        selected: snapshot()
      }
      return snapshot(path.split('/').at(-1))
    },
    post: async (path, body) => { calls.push(['post', path, body]); return { ok: true, snapshot: snapshot('config', { enabled: false, lines: ['', 'hello'], password: '' }) } }
  }
  const confirms = []
  const messageBox = { confirm: async (...args) => { confirms.push(args); return true } }
  const messages = []
  const guards = {}
  const timers = new Map()
  let timerId = 0
  context.mock.method(globalThis, 'setTimeout', callback => { const id = ++timerId; timers.set(id, callback); return id })
  context.mock.method(globalThis, 'clearTimeout', id => timers.delete(id))
  const scope = {
    ...vue, ...configuration, api, requireSuccess, t,
    useAuth: () => ({ can: () => allowed.value }),
    ElMessage: { warning: message => messages.push(message), success: message => messages.push(message) },
    ElMessageBox: messageBox,
    onBeforeRouteLeave: callback => { guards.leave = callback },
    onBeforeRouteUpdate: callback => { guards.update = callback },
    GrimConfigField: {}
  }
  for (const name of imports) assert.ok(Object.hasOwn(scope, name), `Missing import: ${name}`)
  const component = new Function(...imports, `${body}; return __component`)(...imports.map(name => scope[name]))
  component.render = () => null
  const root = { setup: () => () => vue.h(component, { active: activeProp.value }) }
  const app = renderer.createApp(root)
  app.mount({ children: [] })
  const state = app._instance.subTree.component.setupState
  context.after(() => { if (app._instance) app.unmount() })
  await settle()
  function visibility(hidden) { document.hidden = hidden; document.dispatchEvent(new Event('visibilitychange')) }
  async function tick() {
    const pending = [...timers]
    timers.clear()
    for (const [, callback] of pending) callback()
    await settle()
  }
  return { app, state, api, calls, confirms, messages, messageBox, allowed, activeProp, timers, document, window, guards, visibility, tick }
}
test('configuration is lazy and revisiting hidden tabs does not refetch or poll', async context => {
  const env = await environment(context, false)
  assert.equal(env.calls.length, 0)
  assert.equal(env.timers.size, 0)
  env.activeProp.value = true
  await settle()
  assert.equal(env.calls.length, 1)
  env.state.updateField(env.state.fields[0], false)
  env.activeProp.value = false
  await settle()
  env.activeProp.value = true
  await settle()
  assert.equal(env.calls.length, 1)
  assert.equal(env.state.draft.enabled, false)
  assert.equal(env.timers.size, 0)
})
test('save sends only changed editable fields and does not automatically reload GrimAC', async context => {
  const env = await environment(context)
  env.state.updateField(env.state.fields[0], false)
  env.state.updateField(env.state.fields[3], 'V3')
  assert.equal(env.state.original.enabled, true)
  await env.state.save()
  const posted = env.calls.filter(call => call[0] === 'post')
  assert.deepEqual(posted, [['post', '/api/ext/anticheat/grim/config/config', { fingerprint: 'version-config', changes: { enabled: false } }]])
  assert.equal(env.state.dirty, false)
  assert.equal(env.state.needsReload, true)
  assert.equal(env.timers.size, 0)
})
test('configured secrets can be explicitly cleared without exposing or accidentally clearing their old values', async context => {
  const env = await environment(context)
  const password = env.state.fields.find(field => field.key === 'password')
  assert.deepEqual(env.state.changes, {})
  env.state.editSecret(password)
  assert.deepEqual(env.state.changes, { password: '' })
  env.state.resetSecret(password)
  assert.deepEqual(env.state.changes, {})
  env.state.editSecret(password)
  await env.state.save()
  assert.deepEqual(env.calls.at(-1)[2].changes, { password: '' })
  assert.equal(env.state.touchedSecrets.password, undefined)
})
test('conflicts keep the draft and block repeated saves until the user reloads', async context => {
  const env = await environment(context)
  env.api.post = async () => { throw Object.assign(new Error('file changed'), { status: 409 }) }
  env.state.updateField(env.state.fields[0], false)
  await env.state.save()
  assert.equal(env.state.conflict, true)
  assert.equal(env.state.draft.enabled, false)
  assert.equal(env.state.dirty, true)
  assert.equal(env.state.disabled, true)
  env.messageBox.confirm = async () => { throw new Error('cancel') }
  const before = env.calls.length
  await env.state.loadFile('config')
  assert.equal(env.calls.length, before)
  assert.equal(env.state.draft.enabled, false)
  env.messageBox.confirm = async () => true
  await env.state.loadFile('config')
  assert.equal(env.state.conflict, false)
  assert.equal(env.state.dirty, false)
})
test('file changes protect drafts and only switch after a successful read', async context => {
  const env = await environment(context)
  env.state.updateField(env.state.fields[0], false)
  env.messageBox.confirm = async () => { throw new Error('cancel') }
  await env.state.loadFile('messages')
  assert.equal(env.state.selectedId, 'config')
  assert.equal(env.state.draft.enabled, false)
  env.messageBox.confirm = async () => true
  env.api.get = async () => { throw Object.assign(new Error('unavailable'), { status: 503 }) }
  await env.state.loadFile('messages')
  assert.equal(env.state.selectedId, 'config')
  assert.equal(env.state.draft.enabled, false)
  env.api.get = async () => snapshot('messages')
  await env.state.loadFile('messages')
  assert.equal(env.state.selectedId, 'messages')
  assert.equal(env.state.dirty, false)
})
test('permission loss clears sensitive drafts and prevents late responses from restoring them', async context => {
  const env = await environment(context)
  const pending = deferred()
  env.api.get = () => pending.promise
  const reading = env.state.loadFile('messages')
  await settle()
  env.allowed.value = false
  await settle()
  assert.equal(env.state.snapshot, null)
  assert.deepEqual(env.state.draft, {})
  pending.resolve(snapshot('messages'))
  await reading
  assert.equal(env.state.snapshot, null)
  assert.deepEqual(env.state.files, [])
})
test('unmounted configuration pages ignore late reads and clean up all listeners and timers', async context => {
  const env = await environment(context)
  const pending = deferred()
  env.api.get = () => pending.promise
  const reading = env.state.loadFile('messages')
  await settle()
  env.app.unmount()
  pending.resolve(snapshot('messages'))
  await reading
  assert.equal(env.state.selectedId, 'config')
  assert.equal(env.document.listeners.size, 0)
  assert.equal(env.window.listeners.size, 0)
  assert.equal(env.timers.size, 0)
})
test('uncertain save responses retain the draft and require verification before another save', async context => {
  const env = await environment(context)
  env.api.post = async () => { throw new Error('connection lost') }
  env.state.updateField(env.state.fields[0], false)
  await env.state.save()
  assert.equal(env.state.uncertain, true)
  assert.equal(env.state.dirty, true)
  assert.equal(env.state.disabled, true)
})
test('explicit reload checks the real result and suspends polling while hidden or inactive', async context => {
  const env = await environment(context)
  env.state.updateField(env.state.fields[0], false)
  await env.state.save()
  env.api.post = async path => { env.calls.push(['post', path]); return { ok: true } }
  env.api.get = async path => { env.calls.push(['get', path]); return { connected: true, reloadStatus: '重载成功' } }
  await env.state.reloadGrim()
  assert.equal(env.state.reloadPending, true)
  assert.equal(env.timers.size, 1)
  env.visibility(true)
  assert.equal(env.timers.size, 0)
  env.visibility(false)
  assert.equal(env.timers.size, 1)
  env.activeProp.value = false
  await settle()
  assert.equal(env.timers.size, 0)
  env.activeProp.value = true
  await settle()
  await env.tick()
  assert.equal(env.state.reloadPending, false)
  assert.equal(env.state.reloadStatus, '重载成功')
  assert.equal(env.state.needsReload, false)
  assert.equal(env.timers.size, 0)
})
test('reload status requests stay single-flight and stop after a bounded wait', async context => {
  const env = await environment(context)
  env.api.post = async () => ({ ok: true })
  const pending = deferred()
  let reads = 0
  env.api.get = () => { reads++; return pending.promise }
  await env.state.reloadGrim()
  await env.tick()
  await env.state.readReloadStatus()
  assert.equal(reads, 1)
  pending.resolve({ connected: true, reloadStatus: '正在重载' })
  await settle()
  env.api.get = async () => { reads++; return { connected: true, reloadStatus: '正在重载' } }
  for (let i = 0; i < 29; i++) await env.tick()
  assert.equal(reads, 30)
  assert.equal(env.state.reloadPending, false)
  assert.ok(env.state.reloadError)
  assert.equal(env.timers.size, 0)
})
test('unsaved drafts guard browser close and route navigation', async context => {
  const env = await environment(context)
  const cleanEvent = new Event('beforeunload', { cancelable: true })
  env.window.dispatchEvent(cleanEvent)
  assert.equal(cleanEvent.defaultPrevented, false)
  env.state.updateField(env.state.fields[0], false)
  const dirtyEvent = new Event('beforeunload', { cancelable: true })
  env.window.dispatchEvent(dirtyEvent)
  assert.equal(dirtyEvent.defaultPrevented, true)
  env.messageBox.confirm = async () => { throw new Error('stay') }
  assert.equal(await env.guards.leave(), false)
  env.messageBox.confirm = async () => true
  assert.equal(await env.guards.leave(), true)
})
