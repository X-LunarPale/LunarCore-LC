import assert from 'node:assert/strict'
import test from 'node:test'
import { restartOutcome } from '../src/restart.js'

const ticket = { serverStartedAt: 1000, requestedAt: 10000 }

test('a queued response, disconnect or old process never proves a completed restart', () => {
  assert.equal(restartOutcome(ticket, { serverStartedAt: 1000, restartQueued: true }, 12000), 'waiting')
  assert.equal(restartOutcome(ticket, null, 60000), 'waiting')
  assert.equal(restartOutcome(ticket, { serverStartedAt: 1000, restartRequired: false }, 12000), 'waiting')
  assert.equal(restartOutcome(ticket, { serverStartedAt: 2000, restartRequired: false }, 15000), 'applied')
  assert.equal(restartOutcome(ticket, { serverStartedAt: 2000, restartRequired: true }, 15000), 'restarted-pending')
})

test('cancelled queues and timeouts terminate monitoring without retrying restart', () => {
  assert.equal(restartOutcome(ticket, { serverStartedAt: 1000, restartQueued: false }, 12000), 'waiting')
  assert.equal(restartOutcome(ticket, { serverStartedAt: 1000, restartQueued: false }, 18000), 'cancelled')
  assert.equal(restartOutcome(ticket, null, 130000), 'timeout')
  assert.equal(restartOutcome(ticket, { available: false }, 130000), 'timeout')
  assert.equal(restartOutcome(null, null, 130000), 'idle')
})
