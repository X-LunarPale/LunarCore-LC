import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { checkSettings, checkStatus, controlKeys, motdKeys, normalKeys, normalizeWhitelist, pick, same, validateText } from './maintenance.js'

for (const file of [new URL('./MaintenanceWorkspace.vue', import.meta.url), new URL('../../views/PanelView.vue', import.meta.url)]) {
  const source = fs.readFileSync(file, 'utf8')
  const { descriptor, errors } = parse(source, { filename: file.pathname })
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: 'maintenance-verification' })
  const result = compileTemplate({ source: descriptor.template.content, filename: file.pathname, id: 'maintenance-verification', compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(result.errors, [])
  console.log(`SFC parse passed: ${file.pathname.split('/').pop()}`)
}
const normal = { enabled: true, line1: 'Normal 1', line2: 'Normal 2' }
const saved = { enabled: false, allowJoin: false, kickOnline: false, whitelist: 'trusted', joinMessage: 'Join', kickMessage: 'Kick', line1: 'Maintenance 1', line2: 'Maintenance 2' }
const draft = { ...saved, enabled: true, whitelist: 'new_user', line1: 'Draft 1' }
const motdBody = { ...saved, ...pick(draft, motdKeys) }
assert.equal(motdBody.enabled, false)
assert.equal(motdBody.whitelist, 'trusted')
const controlsBody = { ...saved, ...pick(draft, controlKeys) }
assert.equal(controlsBody.line1, 'Maintenance 1')
assert.equal(controlsBody.enabled, true)
assert.deepEqual(checkSettings(normal), normal)
assert.deepEqual(checkSettings(saved, true), saved)
assert.equal(Object.keys(controlsBody).length, 8)
assert.equal(Object.keys(pick(normal, normalKeys)).length, 3)
assert.equal(same(saved, draft, controlKeys), false)
assert.equal(normalizeWhitelist('Trusted\nTRUSTED\n0123456789abcdef0123456789ABCDEF'), 'trusted\n01234567-89ab-cdef-0123-456789abcdef')
assert.throws(() => normalizeWhitelist('invalid name'))
assert.throws(() => normalizeWhitelist(Array(501).fill('user').join('\n')))
assert.throws(() => validateText('a\nb', '简介'))
assert.throws(() => validateText('x'.repeat(513), '简介'))
validateText('<gold>维护 {online}/{max}', '简介')
assert.throws(() => checkSettings({ enabled: false }))
assert.throws(() => checkStatus({ enabled: false }))
assert.throws(() => checkStatus([{ enabled: false, allowJoin: true, kickOnline: false, online: -1, eligible: 0 }]))
assert.equal(checkStatus([{ enabled: false, allowJoin: true, kickOnline: false, online: 1, eligible: 0 }]).online, 1)
console.log('Maintenance API shapes, isolated save payloads, whitelist and text validation passed')

// Exercise the actual component handlers with in-memory API and confirmation doubles.
const vue = await import('vue')
const componentSource = fs.readFileSync(new URL('./MaintenanceWorkspace.vue', import.meta.url), 'utf8')
const setup = parse(componentSource).descriptor.scriptSetup.content.replace(/^import .*$/gm, '').replace(/^defineProps\(.*$/gm, '')
const server = { normal: { ...normal }, maintenance: { ...saved } }
const calls = []
let confirmations = []
let rejectConfirmation = false
const fakeApi = {
  async get(path) {
    calls.push(['GET', path])
    if (path.endsWith('/status')) return [{ enabled: server.maintenance.enabled, allowJoin: server.maintenance.allowJoin, kickOnline: server.maintenance.kickOnline, online: 3, eligible: server.maintenance.enabled ? 2 : 0 }]
    return { ...(path.endsWith('/settings') ? server.normal : server.maintenance) }
  },
  async post(path, body) {
    calls.push(['POST', path, { ...body }])
    if (path.endsWith('/kick')) return { ok: true, message: 'Kicked' }
    if (path.endsWith('/settings')) server.normal = { ...body }
    else server.maintenance = { ...body }
    return { ok: true, message: 'Saved' }
  }
}
const fakeMessageBox = {
  async confirm(text, title) { confirmations.push({ text, title }); if (rejectConfirmation) throw new Error('cancel') },
  async alert() {}
}
const helpers = { checkSettings, checkStatus, controlKeys, motdKeys, normalKeys, normalizeWhitelist, pick, same, validateText }
const t = (source, params = {}) => source.replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))
const deps = { ...helpers, t, locale: { value: 'zh_CN' }, computed: vue.computed, reactive: vue.reactive, ref: vue.ref, onMounted() {}, onBeforeUnmount() {}, onBeforeRouteLeave() {}, onBeforeRouteUpdate() {}, api: fakeApi, useAuth: () => ({ can: () => true }), ElMessageBox: fakeMessageBox, requireSuccess(result) { if (result?.ok === false) throw new Error(result.message); return result } }
const factory = new Function(...Object.keys(deps), `${setup}\nreturn { load, save, kick, leave, normal, maintenance, dirty, normalDirty, controlDirty, motdDirty, pending, uncertain, error, savedMaintenance };`)
const workspace = factory(...Object.values(deps))
await workspace.load()
workspace.maintenance.enabled = true
workspace.maintenance.kickOnline = true
workspace.maintenance.line1 = 'Draft maintenance MOTD'
await workspace.save('motd')
assert.equal(server.maintenance.enabled, false)
assert.equal(server.maintenance.kickOnline, false)
assert.equal(workspace.controlDirty.value, true)
assert.equal(workspace.motdDirty.value, false)
assert.equal(confirmations.length, 0)
rejectConfirmation = true
await workspace.save('control')
assert.equal(server.maintenance.enabled, false)
assert.equal(workspace.pending.value, '')
assert.match(confirmations.at(-1).text, /立即.*踢出/)
rejectConfirmation = false
await workspace.save('control')
assert.equal(server.maintenance.enabled, true)
assert.equal(server.maintenance.kickOnline, true)
workspace.maintenance.whitelist = 'unsaved_user'
await workspace.kick()
assert.deepEqual(calls.at(-2), ['POST', '/api/ext/motd/maintenance/kick', { row: {} }])
assert.match(confirmations.at(-1).text, /未保存修改不会参与/)
assert.match(confirmations.at(-1).text, /Kick/)
assert.equal(server.maintenance.whitelist, 'trusted')
workspace.normal.line1 = 'Normal changed'
await workspace.save('normal')
assert.equal(workspace.controlDirty.value, true)
assert.equal(workspace.normalDirty.value, false)
rejectConfirmation = true
assert.equal(await workspace.leave(), false)
rejectConfirmation = false
server.maintenance.allowJoin = true
const beforeConflict = calls.filter(call => call[0] === 'POST').length
await workspace.save('control')
assert.equal(workspace.uncertain.value, true)
assert.equal(calls.filter(call => call[0] === 'POST').length, beforeConflict)
assert.match(workspace.error.value, /其他操作修改/)
await workspace.load()
workspace.normal.line2 = 'Uncertain result'
const originalPost = fakeApi.post
fakeApi.post = async () => { throw new Error('请求超时') }
await workspace.save('normal')
assert.equal(workspace.uncertain.value, true)
assert.equal(workspace.normalDirty.value, true)
assert.match(workspace.error.value, /重新加载核对/)
fakeApi.post = originalPost
await workspace.load()
workspace.maintenance.enabled = false
let releaseConfirmation
const originalConfirm = fakeMessageBox.confirm
fakeMessageBox.confirm = () => new Promise(resolve => { releaseConfirmation = resolve })
const firstKick = workspace.kick()
await Promise.resolve()
await Promise.resolve()
const beforeDuplicate = calls.filter(call => call[0] === 'POST').length
await workspace.kick()
await workspace.save('control')
assert.equal(calls.filter(call => call[0] === 'POST').length, beforeDuplicate)
releaseConfirmation()
await firstKick
fakeMessageBox.confirm = originalConfirm
console.log('Component handler checks passed: isolated saves, kick confirmation/cancellation, saved-only manual kick, unsaved leave guard, concurrent-setting conflict, uncertain results, duplicate submission prevention')
