import assert from 'node:assert/strict'
import test from 'node:test'
import { api, extensionBase } from '../src/api.js'

test('extension base preserves reverse proxy prefixes and rejects unrelated paths', () => {
  assert.equal(extensionBase('/ext/structures/index.html'), '')
  assert.equal(extensionBase('/console/lc/ext/structures/index.html'), '/console/lc')
  assert.throws(() => extensionBase('/other/index.html'))
})

test('requests use the latest token, same-origin URL and never follow redirects', async () => {
  const originals = { fetch: globalThis.fetch, location: globalThis.location, localStorage: globalThis.localStorage }
  let token = 'first', calls = []
  globalThis.location = { origin: 'https://panel.example', pathname: '/lc/ext/structures/index.html' }
  globalThis.localStorage = { getItem: () => token }
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), ...options })
    return { ok: true, status: 200, json: async () => ({ revision: 1 }) }
  }
  try {
    await api.get('/api/ext/structures/config')
    token = 'second'
    await api.post('/api/ext/structures/config', { revision: 1 })
    assert.equal(calls[0].url, 'https://panel.example/lc/api/ext/structures/config')
    assert.equal(calls[0].headers.Authorization, 'Bearer first')
    assert.equal(calls[1].headers.Authorization, 'Bearer second')
    assert.equal(calls[1].redirect, 'error')
    assert.equal(calls[1].body, '{"revision":1}')
    await assert.rejects(api.get('https://outside.example/'), /禁止/)
    assert.equal(calls.length, 2)
    token = ''
    await assert.rejects(api.post('/api/ext/structures/restart'), e => e.status === 401)
    assert.equal(calls.length, 2)
    token = 'expired'
    globalThis.fetch = async () => ({ ok: false, status: 401, json: async () => ({}) })
    await assert.rejects(api.get('/api/ext/structures/status'), e => e.status === 401)
    globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ error: 'stale revision' }) })
    await assert.rejects(api.post('/api/ext/structures/config', {}), /stale revision/)
  } finally { Object.assign(globalThis, originals) }
})
