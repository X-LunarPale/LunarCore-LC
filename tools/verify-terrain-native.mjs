import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'

const directory = process.env.TERRAIN_TEST_HOME
if (!directory) throw new Error('Set TERRAIN_TEST_HOME to an isolated terrain test server')
const base = 'http://127.0.0.1:8879'
const passwordText = await readFile(`${directory}/plugins/LunarCore/web-admin-password.txt`, 'utf8')
const password = passwordText.match(/^密码：(.+)$/m)?.[1]?.trim()
if (!password) throw new Error('Isolated test administrator password is unavailable')
const login = await fetch(base + '/api/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password })
})
assert.equal(login.status, 200, 'isolated server login')
const { token } = await login.json()
async function call(path, body, expected = 200) {
  const response = await fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const data = await response.json()
  assert.equal(response.status, expected, `${path}: ${JSON.stringify(data)}`)
  return data
}

const output = new URL('../artifacts/terrain-native/', import.meta.url)
await mkdir(output, { recursive: true })
const mode = process.argv[2] || 'verify'
if (mode === 'stop') {
  await call('/api/console/exec', { command: 'stop' }).catch(() => {})
  console.log('Isolated terrain test server stop requested')
} else if (mode === 'probe') {
  const name = process.argv[3] || 'monumental'
  await call('/api/console/exec', { command: `terrainprobe ${name}` })
  console.log(`Native terrain sampling started: ${name}`)
} else if (mode === 'browser') {
  const { chromium } = process.env.PLAYWRIGHT_MODULE
    ? await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href)
    : await import('playwright')
  const browser = await chromium.launch({
    executablePath: process.env.TERRAIN_BROWSER || undefined, headless: true
  })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1040 } })
    await context.addInitScript(value => localStorage.setItem('lbr-token', value), token)
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto((process.env.TERRAIN_UI_URL || base) + '/panel/terrain/tectonic', { waitUntil: 'networkidle' })
    await page.getByRole('heading', { name: /Tectonic/ }).waitFor()
    await page.screenshot({ path: fileURLToPath(new URL('desktop.png', output)), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: fileURLToPath(new URL('mobile.png', output)), fullPage: true })
    assert.ok(await page.evaluate(() => document.body.scrollWidth <= innerWidth + 2), 'mobile has no horizontal overflow')
    assert.deepEqual(errors, [], 'browser has no page errors')
    console.log('Real browser terrain workspace passed: desktop 1440, mobile 390')
  } finally {
    await browser.close()
  }
} else {
  let status = await call('/api/ext/terrain/status')
  assert.equal(status.supported, true, `native bridge initialized: ${status.error}`)
  assert.ok((await call('/api/panels')).some(panel => panel.namespace === 'terrain' && panel.id === 'tectonic'))
  const preset = status.presets.find(item => item.id === 'monumental')
  assert.ok(preset)
  await call('/api/ext/terrain/worlds', { name: '../world', seed: '1', config: preset.config }, 400)
  await call('/api/ext/terrain/worlds', { name: 'unsafe_height', seed: '1', config: { ...preset.config, height: 9000 } }, 400)
  await call('/api/ext/terrain/worlds', { name: 'unsafe_seed', seed: 9007199254740992, config: preset.config }, 400)
  if (!status.worlds.some(world => world.name === 'monumental')) {
    await call('/api/ext/terrain/worlds', { name: 'monumental', seed: '9223372036854775807', config: preset.config }, 202)
  }
  let world
  for (let attempt = 0; attempt < 120; attempt++) {
    status = await call('/api/ext/terrain/status')
    world = status.worlds.find(row => row.name === 'monumental')
    if (world?.status === 'failed') throw new Error(world.error)
    if (world?.status === 'ready') break
    await delay(1000)
  }
  assert.equal(world?.status, 'ready', 'native world is ready')
  assert.equal(world.seed, '9223372036854775807', 'long seed remains exact')
  assert.equal(world.minY, -64)
  assert.equal(world.maxY, 960)
  await call('/api/ext/terrain/worlds', { name: 'monumental', seed: '1', config: preset.config }, 400)
  await call('/api/ext/terrain/worlds/monumental/unload', {})
  status = await call('/api/ext/terrain/status')
  assert.equal(status.worlds.find(row => row.name === 'monumental')?.loaded, false)
  await call('/api/ext/terrain/worlds/monumental/load', {}, 202)
  for (let attempt = 0; attempt < 60; attempt++) {
    status = await call('/api/ext/terrain/status')
    world = status.worlds.find(row => row.name === 'monumental')
    if (world?.status === 'failed') throw new Error(world.error)
    if (world?.status === 'ready') break
    await delay(1000)
  }
  assert.equal(world?.status, 'ready')
  assert.equal(world.maxY, 960)
  await writeFile(new URL('status.json', output), JSON.stringify(status, null, 2))
  await call('/api/console/exec', { command: 'terrainprobe monumental' })
  console.log('Native API verification passed: validation, exact seed, create, duplicate protection, height, unload and reload')
}
