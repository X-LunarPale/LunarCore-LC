import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loginDestination } from '../src/utils/login-destination.js'

test('sign-in preserves valid internal destinations, including their queries', () => {
  const path = '/players?view=online#player-list'
  assert.equal(loginDestination(path, fullPath => ({ name: 'players', fullPath })), path)
})

test('sign-in returns to the dashboard for stale and nested login URLs', () => {
  for (const [path, name] of [['/login%60', 'notfound'], ['/login?redirect=%2Fplayers', 'login']]) {
    assert.equal(loginDestination(path, fullPath => ({ name, fullPath })), '/')
  }
})

test('sign-in ignores missing and external destinations', () => {
  for (const path of [undefined, ['/players'], 'https://example.test', '//example.test', '/\\example.test']) {
    assert.equal(loginDestination(path, () => assert.fail('must not resolve')), '/')
  }
})
