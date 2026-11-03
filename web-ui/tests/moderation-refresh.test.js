import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { requireSuccess } from '../src/utils/panel.js'
import { t } from '../src/i18n.js'

const source = await readFile(new URL('../src/components/moderation/ModerationWorkspace.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'moderation-refresh', genDefaultAs: '__component' })
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
  addEventListener(type, listener, options) {
    this.listeners.add(listener)
    super.addEventListener(type, listener, options)
  }
  removeEventListener(type, listener, options) {
    this.listeners.delete(listener)
    super.removeEventListener(type, listener, options)
  }
}

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

async function settle() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await vue.nextTick()
}

const command = id => ({ id, label: id, supported: true, permission: 'anticheat.view', args: [] })

async function environment(context) {
  const document = Object.assign(new TrackedTarget(), { hidden: false })
  globalThis.document = document
  let sequence = 0
  const timers = new Map()
  context.mock.method(globalThis, 'setInterval', callback => {
    const id = ++sequence
    timers.set(id, callback)
    return id
  })
  context.mock.method(globalThis, 'clearInterval', id => timers.delete(id))
  const api = {
    get: async () => ({ output: [], pending: false }),
    post: async () => ({ executionId: 'run-a', output: [] })
  }
  const socket = {
    connected: true,
    listeners: new Set(),
    onStatus(listener) {
      this.listeners.add(listener)
      listener(this.connected)
      return () => this.listeners.delete(listener)
    }
  }
  const messageBox = { confirm: async () => true }
  const scope = {
    ...vue, api, socket, resolveEndpoint: path => path, requireSuccess, t,
    useAuth: () => ({ can: () => true }),
    useApp: () => ({ panels: [] }),
    useAutoRefresh() {},
    ElMessageBox: messageBox,
    TableSection: {}, FormSection: {}, FieldInput: {}, GrimConfigWorkspace: {}
  }
  for (const name of imports) assert.ok(Object.hasOwn(scope, name), `Missing test import: ${name}`)
  const component = new Function(...imports, `${body}; return __component`)(...imports.map(name => scope[name]))
  component.render = () => null
  const app = renderer.createApp(component, { panel: { sections: [] }, namespace: 'anticheat', id: 'anticheat' })
  app.mount({ children: [] })
  context.after(() => { if (app._instance) app.unmount() })
  const state = app._instance.setupState
  state.tab = 'tools'
  await settle()
  async function start(id = 'list') {
    state.chooseCommand(command(id))
    await state.runCommand()
    await settle()
  }
  function fireIntervals(times = 1) {
    for (let i = 0; i < times; i++) for (const callback of [...timers.values()]) callback()
  }
  function visibility(hidden) {
    document.hidden = hidden
    document.dispatchEvent(new Event('visibilitychange'))
  }
  return { app, state, api, socket, document, messageBox, timers, start, fireIntervals, visibility }
}

test('a command response arriving after unmount cannot create an output timer', async context => {
  const env = await environment(context)
  const submitted = deferred()
  env.api.post = () => submitted.promise
  env.state.chooseCommand(command('list'))
  const running = env.state.runCommand()
  assert.equal(env.state.commandBusy, true)
  env.app.unmount()
  submitted.resolve({ executionId: 'late-run', output: ['late output'] })
  await running
  await settle()
  assert.equal(env.timers.size, 0)
  assert.equal(env.state.executionId, '')
  assert.equal(env.document.listeners.size, 0)
  assert.equal(env.socket.listeners.size, 0)
})

test('slow output requests remain single-flight and completed output stops polling', async context => {
  const env = await environment(context)
  const output = deferred()
  const paths = []
  env.api.get = path => { paths.push(path); return output.promise }
  await env.start()
  assert.equal(env.timers.size, 1)
  env.fireIntervals(4)
  await env.state.readOutput()
  assert.deepEqual(paths, ['/api/ext/anticheat/commands/runs/run-a'])
  output.resolve({ pending: true, output: ['partial output'] })
  await settle()
  assert.equal(env.state.commandMessage, 'partial output')
  env.api.get = async path => { paths.push(path); return { pending: false, output: ['complete output'] } }
  env.fireIntervals()
  await settle()
  assert.equal(paths.length, 2)
  assert.equal(env.state.commandMessage, 'complete output')
  assert.equal(env.timers.size, 0)
})

test('switching tools invalidates in-flight output without changing the new selection', async context => {
  const env = await environment(context)
  const output = deferred()
  env.api.get = () => output.promise
  await env.start('first')
  env.fireIntervals()
  env.state.chooseCommand(command('second'))
  assert.equal(env.timers.size, 0)
  output.resolve({ pending: false, output: ['stale first output'] })
  await settle()
  assert.equal(env.state.command.id, 'second')
  assert.equal(env.state.commandMessage, '')
  assert.equal(env.state.executionId, '')
  assert.equal(env.timers.size, 0)
  await env.start('second')
  assert.equal(env.timers.size, 1)
})

test('switching tools while submission is pending ignores the old execution response', async context => {
  const env = await environment(context)
  const submitted = deferred()
  env.api.post = () => submitted.promise
  env.state.chooseCommand(command('first'))
  const running = env.state.runCommand()
  env.state.chooseCommand(command('second'))
  submitted.resolve({ executionId: 'old-run', output: ['old result'] })
  await running
  await settle()
  assert.equal(env.state.command.id, 'second')
  assert.equal(env.state.commandBusy, false)
  assert.equal(env.state.commandMessage, '')
  assert.equal(env.state.executionId, '')
  assert.equal(env.timers.size, 0)
})

test('hidden pages and other tabs suspend output polling and resume pending runs', async context => {
  const env = await environment(context)
  let reads = 0
  env.api.get = async () => { reads++; return { pending: false, output: ['done'] } }
  await env.start()
  env.visibility(true)
  assert.equal(env.timers.size, 0)
  await env.state.readOutput()
  assert.equal(reads, 0)
  env.visibility(false)
  assert.equal(env.timers.size, 1)
  env.state.tab = 'punishments'
  await settle()
  assert.equal(env.timers.size, 0)
  env.state.tab = 'tools'
  await settle()
  assert.equal(env.timers.size, 1)
  env.fireIntervals()
  await settle()
  assert.equal(reads, 1)
  assert.equal(env.timers.size, 0)
  env.visibility(true)
  env.visibility(false)
  assert.equal(env.timers.size, 0)
})

test('pending output retains its sixty-read limit and manual refresh still works', async context => {
  const env = await environment(context)
  let reads = 0
  env.api.get = async () => ({ pending: true, output: [`read ${++reads}`] })
  await env.start()
  for (let i = 0; i < 60; i++) {
    assert.equal(env.timers.size, 1)
    env.fireIntervals()
    await settle()
  }
  assert.equal(reads, 60)
  assert.equal(env.timers.size, 0)
  await env.state.readOutput()
  assert.equal(reads, 61)
  assert.equal(env.state.commandMessage, 'read 61')
  assert.equal(env.timers.size, 0)
})

test('confirmation resolving after unmount cannot submit a command', async context => {
  const env = await environment(context)
  const confirmation = deferred()
  let submissions = 0
  env.messageBox.confirm = () => confirmation.promise
  env.api.post = async () => { submissions++; return { executionId: 'unexpected' } }
  env.state.chooseCommand({ ...command('manage'), permission: 'anticheat.manage' })
  const running = env.state.runCommand()
  env.app.unmount()
  confirmation.resolve(true)
  await running
  assert.equal(submissions, 0)
  assert.equal(env.timers.size, 0)
})

test('output failures release the request slot and retain manual retry', async context => {
  const env = await environment(context)
  env.api.get = async () => { throw new Error('output unavailable') }
  await env.start()
  env.fireIntervals()
  await settle()
  assert.equal(env.state.commandMessage, 'output unavailable')
  assert.equal(env.timers.size, 0)
  env.api.get = async () => ({ pending: false, output: ['recovered'] })
  await env.state.readOutput()
  assert.equal(env.state.commandMessage, 'recovered')
})
