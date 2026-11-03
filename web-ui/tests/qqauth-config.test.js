import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { requireSuccess } from '../src/utils/panel.js'
import { t } from '../src/i18n.js'

const source = await readFile(new URL('../src/components/qqauth/QQAuthConfig.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'qqauth-config-test', genDefaultAs: '__component' })
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
function snapshot(overrides = {}) {
  return {
    values: { 'integration.group-id': '12345678', 'binding.per-qq-limits': {} },
    messages: { 'login.bind-required': 'Original {code}' },
    revision: 'initial',
    configuredSecrets: {},
    requiresReload: false,
    ...overrides
  }
}
async function settle() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await vue.nextTick()
}
async function environment(context, initial = snapshot()) {
  globalThis.window = new EventTarget()
  const calls = [], notices = [], confirms = [], events = []
  window.addEventListener('lbr:panel-changed', event => events.push(event.detail))
  const allowed = vue.ref(true)
  const api = {
    get: async path => { calls.push(['get', path]); return structuredClone(initial) },
    post: async (path, data) => { calls.push(['post', path, data]); return structuredClone(initial) }
  }
  const guards = {}
  const messageBox = { confirm: async (...args) => { confirms.push(args); return true } }
  const scope = {
    ...vue, api, t, requireSuccess,
    useAuth: () => ({ can: permission => permission === 'core.modules.manage' && allowed.value }),
    ElMessage: {
      success: text => notices.push(['success', text]),
      error: text => notices.push(['error', text])
    },
    ElMessageBox: messageBox,
    onBeforeRouteLeave: callback => { guards.leave = callback },
    onBeforeRouteUpdate: callback => { guards.update = callback },
    MinecraftTextPreview: {}
  }
  for (const name of imports) assert.ok(Object.hasOwn(scope, name), `Missing import: ${name}`)
  const component = new Function(...imports, `${body}; return __component`)(...imports.map(name => scope[name]))
  component.render = () => null
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  const state = app._instance.setupState
  context.after(() => app.unmount())
  await settle()
  return { state, api, calls, notices, confirms, events, allowed, messageBox, guards }
}

test('message edits save without restarting authentication and report immediate effect', async context => {
  const env = await environment(context)
  env.state.messages['login.bind-required'] = 'Updated {code}'
  env.api.post = async (path, data) => {
    env.calls.push(['post', path, data])
    return snapshot({ messages: { ...data.messages }, revision: 'saved' })
  }
  await env.state.save()
  const posts = env.calls.filter(call => call[0] === 'post')
  assert.equal(posts.length, 1)
  assert.equal(posts[0][1], '/api/ext/qqauth/config')
  assert.equal(posts[0][2].messages['login.bind-required'], 'Updated {code}')
  assert.equal(env.state.pendingReload, false)
  assert.equal(env.state.dirty, false)
  assert.equal(env.state.saved, true)
  assert.deepEqual(env.notices, [['success', '提示消息已生效']])
  assert.equal(env.confirms.length, 0)
})

test('saved configuration applies through real module reload and refreshes the page state', async context => {
  const env = await environment(context)
  env.state.values['integration.group-id'] = '87654321'
  env.api.post = async (path, data) => {
    env.calls.push(['post', path, data])
    return path.endsWith('/reload') ? { ok: true } : snapshot({ values: data.values, requiresReload: true })
  }
  await env.state.save()
  assert.equal(env.state.pendingReload, true)
  assert.deepEqual(env.notices.at(-1), ['success', '已保存，重载模块后生效'])
  env.api.get = async path => { env.calls.push(['get', path]); return snapshot({ revision: 'reloaded' }) }
  await env.state.applyConfig()
  assert.equal(env.confirms.length, 1)
  assert.equal(env.confirms[0][0], '重载 QQauth 会撤销在线玩家认证并断开连接。')
  assert.deepEqual(env.calls.slice(-2), [
    ['post', '/api/modules/qqauth/reload', {}],
    ['get', '/api/ext/qqauth/config']
  ])
  assert.equal(env.state.pendingReload, false)
  assert.equal(env.state.saved, false)
  assert.equal(env.state.revision, 'reloaded')
  assert.deepEqual(env.events, [{ namespace: 'qqauth' }])
})

test('applying configuration is blocked for unsaved drafts, busy state and insufficient permission', async context => {
  const env = await environment(context, snapshot({ requiresReload: true }))
  assert.equal(env.state.pendingReload, true)
  env.state.messages['login.bind-required'] = 'Draft'
  await env.state.applyConfig()
  env.state.messages['login.bind-required'] = 'Original {code}'
  env.state.busy = true
  await env.state.applyConfig()
  env.state.busy = false
  env.allowed.value = false
  await env.state.applyConfig()
  assert.equal(env.confirms.length, 0)
  assert.equal(env.calls.filter(call => call[0] === 'post').length, 0)
  assert.equal(env.state.pendingReload, true)
})

test('cancelled and rejected reloads preserve the pending configuration', async context => {
  const env = await environment(context, snapshot({ requiresReload: true }))
  env.messageBox.confirm = async () => { throw new Error('cancel') }
  await env.state.applyConfig()
  assert.equal(env.calls.length, 1)
  env.messageBox.confirm = async () => true
  env.api.post = async () => ({ ok: false, error: 'reload failed' })
  await env.state.applyConfig()
  assert.equal(env.state.pendingReload, true)
  assert.equal(env.state.busy, false)
  assert.deepEqual(env.notices, [['error', 'reload failed']])
  assert.deepEqual(env.events, [])
})

test('reload does not claim settings are active when the follow-up read fails', async context => {
  const env = await environment(context, snapshot({ requiresReload: true }))
  env.api.post = async () => ({ ok: true })
  env.api.get = async () => { throw new Error('configuration unavailable') }
  await env.state.applyConfig()
  assert.equal(env.state.pendingReload, true)
  assert.equal(env.state.error, 'configuration unavailable')
  assert.equal(env.state.busy, false)
  assert.deepEqual(env.notices, [])
  assert.deepEqual(env.events, [{ namespace: 'qqauth' }])
})

test('rereading protects unsaved drafts and keeps pending reload state from the server', async context => {
  const env = await environment(context)
  env.state.messages['login.bind-required'] = 'Draft'
  env.messageBox.confirm = async () => { throw new Error('keep draft') }
  await env.state.load()
  assert.equal(env.calls.length, 1)
  assert.equal(env.state.messages['login.bind-required'], 'Draft')
  env.messageBox.confirm = async () => true
  env.api.get = async () => snapshot({ requiresReload: true })
  await env.state.load()
  assert.equal(env.state.dirty, false)
  assert.equal(env.state.pendingReload, true)
  assert.equal(env.state.saved, false)
  assert.equal(await env.guards.leave(), true)
})
