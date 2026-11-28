import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as panel from '../src/utils/panel.js'
import * as format from '../src/utils/format.js'
import { formatVersion, locale, setLocale, t } from '../src/i18n.js'

const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
  insert(node, parent) { node.parent = parent; parent.children.push(node) },
  remove(node) { node.parent?.children.splice(node.parent.children.indexOf(node), 1) },
  setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
  parentNode: node => node.parent, nextSibling: () => null, patchProp() {}
})

const defaults = {
  '/api/users': { items: [] }, '/api/roles': { items: [] }, '/api/permissions': [],
  '/api/server/whitelist': { enabled: false, enforced: true, items: [] },
  '/api/market': { items: [], pending: [], state: 'idle' },
  '/api/config': { content: 'language: zh_CN\n', path: 'config.yml', fingerprint: 'initial-version' },
  '/api/console/history': []
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
async function settle() { for (let index = 0; index < 10; index++) await Promise.resolve(); await vue.nextTick() }

async function mount(context, name) {
  const { descriptor } = parse(await readFile(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: name, genDefaultAs: '__component' })
  const messages = { success: [], error: [], warning: [], info: [] }
  const api = {
    get: async path => defaults[path] || { items: [], total: 0 },
    post: async () => ({ ok: true, message: 'Saved' }),
    put: async () => ({ ok: true, message: 'Saved' }),
    del: async () => ({ ok: true, message: 'Saved' })
  }
  const auth = { user: { username: 'admin' }, can: () => true, fetchMe: async () => {}, setToken() {} }
  const appStore = { loadPanels: async () => {} }
  const messageBox = { confirm: async () => true }
  const message = Object.assign(options => messages[options.type].push(options.message),
    Object.fromEntries(Object.keys(messages).map(level => [level, text => messages[level].push(text)])))
  const previousWindow = globalThis.window
  globalThis.window = new EventTarget()
  const scope = {
    ...vue, ...panel, ...format, api, formatVersion, locale, t,
    useAuth: () => auth, useApp: () => appStore, onBeforeRouteLeave() {},
    ElMessage: message,
    ElMessageBox: messageBox,
    PlayerCell: {}, MinecraftTextPreview: {}, CodeEditor: {}, TotpSettings: {}, ModuleMarket: {},
    useAutoRefresh(callback, options) { if (options.immediate) vue.onMounted(callback) },
    socket: { connected: true, onStatus: () => () => {}, subscribe: () => () => {} }
  }
  const imports = Object.keys(script.imports)
  for (const name of imports) assert.ok(Object.hasOwn(scope, name), `Missing import: ${name}`)
  const component = new Function(...imports, `${script.content.replace(/^import .*$/gm, '')};return __component`)(...imports.map(name => scope[name]))
  component.render = () => null
  const app = renderer.createApp(component)
  app.mount({ children: [] })
  context.after(() => { app.unmount(); globalThis.window = previousWindow })
  await settle()
  return { state: app._instance.setupState, api, auth, appStore, messages, messageBox }
}

test('broadcasts reject repeat clicks and keep a newer draft after the request completes', async context => {
  const env = await mount(context, 'Server')
  const response = deferred()
  let writes = 0
  env.api.post = async () => { writes++; return response.promise }
  env.state.broadcastText = 'First message'
  const sending = env.state.broadcast()
  await env.state.broadcast()
  assert.equal(writes, 1)
  env.state.broadcastText = 'Next message'
  response.resolve({ ok: true, message: 'Sent' })
  await sending
  assert.equal(env.state.broadcastText, 'Next message')
  assert.equal(env.state.sending, false)
})

test('whitelist confirmation blocks conflicting writes and cancellation sends no mutation', async context => {
  const env = await mount(context, 'Server')
  const confirmation = deferred()
  env.messageBox.confirm = () => confirmation.promise
  let writes = 0
  env.api.post = async () => { writes++; return { ok: true } }
  env.state.wl.name = 'PlayerOne'
  const changing = env.state.toggleWl(true)
  assert.equal(env.state.whitelistBusy, true)
  await env.state.addWl()
  confirmation.reject(new Error('cancel'))
  await changing
  assert.equal(writes, 0)
  assert.equal(env.state.whitelistBusy, false)
})

test('account saves stay locked until readback and rejected writes keep the editor open', async context => {
  const env = await mount(context, 'Accounts')
  Object.assign(env.state.userDlg, { visible: true, editing: false, username: 'helper', password: 'test-password', role: 'viewer' })
  const response = deferred()
  let writes = 0
  env.api.post = () => { writes++; return response.promise }
  const saving = env.state.saveUser()
  await env.state.saveUser()
  assert.equal(writes, 1)
  assert.equal(env.state.busy, 'user')
  response.resolve({ ok: false, error: 'Rejected' })
  await saving
  assert.equal(env.state.userDlg.visible, true)
  assert.equal(env.state.busy, '')
  assert.deepEqual(env.messages.success, [])
  assert.deepEqual(env.messages.error, ['Rejected'])
})

test('market removal confirmation blocks competing installs and releases the lock on cancel', async context => {
  const env = await mount(context, 'ModuleMarket')
  const confirmation = deferred()
  env.messageBox.confirm = () => confirmation.promise
  let writes = 0
  env.api.post = async () => { writes++; return { ok: true } }
  const changing = env.state.action({ id: 'chat', name: 'Chat' }, 'remove')
  await env.state.action({ id: 'economy', name: 'Economy' }, 'install')
  assert.equal(env.state.busy, true)
  confirmation.reject(new Error('cancel'))
  await changing
  assert.equal(writes, 0)
  assert.equal(env.state.busy, false)
})

test('QQAuth reload warns about disconnecting players and only submits after confirmation', async context => {
  const env = await mount(context, 'Modules')
  const qqauth = { id: 'qqauth', name: 'QQAuth' }
  const confirmations = [], writes = []
  let panelReads = 0
  env.api.post = async path => { writes.push(path); return { ok: true } }
  env.appStore.loadPanels = async () => { panelReads++ }
  const confirmation = deferred()
  env.messageBox.confirm = message => { confirmations.push(message); return confirmation.promise }
  const pending = env.state.op(qqauth, 'reload')
  await env.state.op(qqauth, 'reload')
  assert.deepEqual(confirmations, [t('重载 QQauth 会撤销在线玩家认证并断开连接。')])
  assert.deepEqual(writes, [])
  confirmation.reject(new Error('cancel'))
  await pending
  assert.deepEqual(writes, [])
  assert.equal(env.state.busy, '')

  env.messageBox.confirm = async () => true
  await env.state.op(qqauth, 'reload')
  assert.deepEqual(writes, ['/api/modules/qqauth/reload'])
  assert.equal(panelReads, 1)
})

test('market translates bundled module text and known errors when switching to Hong Kong Chinese', async context => {
  const env = await mount(context, 'ModuleMarket')
  const previousLocale = locale.value
  context.after(() => setLocale(previousLocale))
  const entry = { id: 'economy', name: '经济', nameEn: 'Economy', description: '余额、转账与交易记录。' }
  const error = '模块下载失败，请检查网络与发布附件 / Download failed; check network and release assets'
  setLocale('en_US')
  assert.equal(env.state.name(entry), 'Economy')
  assert.equal(env.state.errorText(error), 'Download failed; check network and release assets')
  setLocale('zh_HK')
  assert.equal(env.state.name(entry), '經濟')
  assert.equal(env.state.description(entry), '結餘、轉帳與交易記錄。')
  assert.equal(env.state.errorText(error), '模組下載失敗，請檢查網絡與發佈附件')
  assert.equal(env.state.name({ id: 'custom', name: '自定义模块' }), '自定义模块')
  assert.equal(env.state.errorText('远端返回 / Custom'), '远端返回 / Custom')
})

test('a slow player-history response cannot replace a newer search result', async context => {
  const env = await mount(context, 'Players')
  const first = deferred()
  let reads = 0
  env.api.get = () => ++reads === 1 ? first.promise : Promise.resolve({ items: [{ name: 'NewPlayer' }], total: 1 })
  env.state.historyQuery = 'Old'
  env.state.searchHistory()
  env.state.historyQuery = 'New'
  env.state.searchHistory()
  await settle()
  assert.equal(env.state.hist.items[0].name, 'NewPlayer')
  first.resolve({ items: [{ name: 'OldPlayer' }], total: 1 })
  await settle()
  assert.equal(env.state.hist.items[0].name, 'NewPlayer')
  assert.equal(env.state.hist.loading, false)
})

test('online refreshes use the submitted search instead of a draft being typed', async context => {
  const env = await mount(context, 'Players')
  const queries = []
  env.api.get = async (_, query) => { queries.push(query.q); return { items: [] } }
  env.state.onlineQuery = 'Draft'
  await env.state.loadOnline()
  assert.deepEqual(queries, [''])
  env.state.searchOnline()
  await settle()
  assert.deepEqual(queries, ['', 'Draft'])
})

test('core configuration loads on first visit and ignores validation for a replaced draft', async context => {
  const env = await mount(context, 'Config')
  assert.equal(env.state.loaded, false)
  env.state.activeTab = 'core'
  await settle()
  assert.equal(env.state.loaded, true)
  const response = deferred()
  env.api.post = () => response.promise
  const validating = env.state.validate()
  env.state.content = 'language: en_US\n'
  await settle()
  response.resolve({ ok: false, error: 'Error from the older draft' })
  await validating
  assert.equal(env.state.validation, null)
  assert.equal(env.state.validating, false)
})

test('configuration reload locks its confirmation and keeps the draft on cancellation', async context => {
  const env = await mount(context, 'Config')
  env.state.activeTab = 'core'
  await settle()
  env.state.content = 'language: en_US\n'
  const confirmation = deferred()
  env.messageBox.confirm = () => confirmation.promise
  let reads = 0
  env.api.get = async () => { reads++; return defaults['/api/config'] }
  const loading = env.state.load()
  await env.state.load()
  assert.equal(env.state.loading, true)
  confirmation.reject(new Error('cancel'))
  await loading
  assert.equal(reads, 0)
  assert.equal(env.state.content, 'language: en_US\n')
  assert.equal(env.state.loaded, true)
  assert.equal(env.state.loading, false)
})

test('a config conflict keeps the draft and blocks saving until the latest copy is read', async context => {
  const env = await mount(context, 'Config')
  env.state.activeTab = 'core'
  await settle()
  const submitted = 'language: en_US\n'
  const writes = []
  env.state.content = submitted
  env.api.post = async (_, body) => {
    writes.push(body)
    if (writes.length === 1) throw Object.assign(new Error('Conflict'), { status: 409 })
    return { ok: true, message: 'Saved', fingerprint: 'saved-version' }
  }
  await env.state.save()
  assert.deepEqual(writes, [{ content: submitted, fingerprint: 'initial-version' }])
  assert.equal(env.state.conflict, true)
  assert.equal(env.state.content, submitted)
  assert.equal(env.state.dirty, true)
  await env.state.save()
  assert.equal(writes.length, 1)

  env.api.get = async () => { throw new Error('Read unavailable') }
  await env.state.load()
  assert.equal(env.state.conflict, true)
  assert.equal(env.state.loaded, true)
  assert.equal(env.state.content, submitted)
  env.api.get = async () => ({ content: 'language: zh_HK\n', path: 'config.yml', fingerprint: 'latest-version' })
  await env.state.load()
  assert.equal(env.state.conflict, false)
  assert.equal(env.state.content, 'language: zh_HK\n')
  env.state.content = submitted
  await env.state.save()
  assert.deepEqual(writes[1], { content: submitted, fingerprint: 'latest-version' })
  assert.equal(env.state.fingerprint, 'saved-version')
  assert.equal(env.state.dirty, false)
})

test('console rejection retains the command for correction and repeat execution is blocked', async context => {
  const env = await mount(context, 'Console')
  const response = deferred()
  let writes = 0
  env.api.post = () => { writes++; return response.promise }
  env.state.command = 'unknown-command'
  const sending = env.state.send()
  await env.state.send()
  response.resolve({ ok: false, message: 'Unknown command' })
  await sending
  assert.equal(writes, 1)
  assert.equal(env.state.command, 'unknown-command')
  assert.equal(env.state.sending, false)
  assert.deepEqual(env.messages.warning, ['Unknown command'])
})
