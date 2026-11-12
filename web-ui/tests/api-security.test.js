import assert from 'node:assert/strict'
import { test } from 'node:test'

globalThis.window = { __LC_BASE__: '', location: new URL('https://panel.example/lc/') }
const { request, setAuthHooks, socket } = await import('../src/api.js')
setAuthHooks(() => 'secret-session-token', () => {})

test('external panel endpoints never receive a token or body', async () => {
  let calls = 0
  globalThis.fetch = async () => { calls++; throw new Error('must not fetch') }
  for (const path of ['https://attacker.example/api', 'http://panel.example/api', '//attacker.example/api', '/\\attacker.example/api']) {
    await assert.rejects(request('POST', path, { password: 'sensitive' }), /禁止请求外部接口地址/)
  }
  assert.equal(calls, 0)
})

test('same-origin API keeps authorization and rejects redirects', async () => {
  globalThis.fetch = async (url, options) => {
    assert.equal(url, 'https://panel.example/api/auth/me')
    assert.equal(options.headers.Authorization, 'Bearer secret-session-token')
    assert.equal(options.redirect, 'error')
    return { ok: true, text: async () => '{"ok":true}' }
  }
  assert.deepEqual(await request('GET', '/api/auth/me'), { ok: true })
})

test('old HTTP 401 cannot sign out a newly replaced session', async () => {
  let token = 'old', unauthorized = 0, finish
  setAuthHooks(() => token, () => unauthorized++)
  globalThis.fetch = () => new Promise(resolve => { finish = resolve })
  const pending = request('GET', '/api/auth/me')
  token = 'new'
  finish({ ok: false, status: 401, text: async () => '{"error":"expired"}' })
  await assert.rejects(pending, /expired/)
  assert.equal(unauthorized, 0)
  globalThis.fetch = async () => ({ ok: false, status: 401, text: async () => '{}' })
  await assert.rejects(request('GET', '/api/auth/me'))
  assert.equal(unauthorized, 1)
})

test('old socket close cannot clobber a new connection; 4001 schedules a session check', () => {
  let unauthorized = 0
  setAuthHooks(() => 'token', () => unauthorized++)
  globalThis.WebSocket = class { constructor() { this.readyState = 0 } close() {} }
  socket.connect()
  const old = socket.ws
  socket.close()
  socket.connect()
  const current = socket.ws
  old.onclose({ code: 4001 })
  assert.equal(socket.ws, current)
  assert.equal(unauthorized, 0)
  current.onclose({ code: 4001 })
  assert.equal(unauthorized, 0)
  assert.ok(socket.timer)
  socket.close()
})

test('explicit socket close immediately publishes disconnected state', () => {
  socket.setConnected(true)
  const states = []
  const off = socket.onStatus(value => states.push(value))
  socket.close()
  assert.equal(socket.connected, false)
  assert.deepEqual(states, [true, false])
  off()
})

test('request timeout aborts a stalled response body without retrying mutations', async () => {
  let calls = 0
  globalThis.fetch = async (_url, options) => {
    calls++
    return { ok: true, text: () => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }) }
  }
  await assert.rejects(request('POST', '/api/console/exec', { command: 'test' }, { timeoutMs: 10 }), /请求超时.*核对/)
  assert.equal(calls, 1)
})
