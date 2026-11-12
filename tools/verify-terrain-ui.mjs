import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'

// All API traffic is intercepted. This verifies the UI without starting a Paper server.
const { chromium } = process.env.PLAYWRIGHT_MODULE
  ? await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href)
  : await import('playwright')
const browser = await chromium.launch({
  executablePath: process.env.TERRAIN_BROWSER || undefined,
  headless: true
})
const base = process.env.TERRAIN_UI_URL || 'http://127.0.0.1:5187'
const output = new URL('../artifacts/terrain-native/', import.meta.url)
await mkdir(output, { recursive: true })
const config = {
  height: 640, verticalScale: 1.8, horizontalScale: 1.2,
  rollingHills: true, undergroundRivers: true, lavaTunnels: true, junglePillars: true
}
const presets = [
  { id: 'classic', name: '经典山脉', config: { ...config, height: 384, verticalScale: 1.125, horizontalScale: 1 } },
  { id: 'alpine', name: '宏伟山脉', config: { ...config } },
  { id: 'monumental', name: '极高山脉', config: { ...config, height: 1024, verticalScale: 2.8, horizontalScale: 1.5 } }
]
function world(name, options = config, seed = '123456789') {
  return {
    name, key: `lc_terrain:${name}`, seed, config: { ...options }, autoLoad: true,
    loaded: true, players: 0, minY: -64, maxY: options.height - 64,
    status: 'ready', error: ''
  }
}
function fixture() {
  return {
    snapshot: {
      supported: true, gameVersion: '26.3',
      upstream: { name: 'Tectonic', version: '3.0.29', license: 'MIT' },
      defaults: { ...config }, presets: structuredClone(presets),
      worlds: [world('mountain', presets[2].config)], busy: false, error: ''
    },
    writes: [], failRead: false, failAfterWrite: false, abortWrite: false, mismatchAfterWrite: false
  }
}
const errors = []
const results = []
async function open(state, permissions = ['*'], locale = 'zh_CN') {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1040 } })
  await context.addInitScript(value => {
    localStorage.setItem('lc-token', 'fixture-token')
    localStorage.setItem('lc-locale', value)
  }, locale)
  await context.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let body
    let status = 200
    if (path === '/api/auth/me') body = { username: 'fixture', role: 'admin', permissions }
    else if (path === '/api/auth/info') body = { brand: 'LunarCore', gameLogin: false }
    else if (path === '/api/panels') body = [{
      namespace: 'terrain', id: 'tectonic', title: 'Tectonic 宏伟地形',
      group: '服务器', icon: 'mountain', sections: []
    }]
    else if (path === '/api/ext/terrain/status') {
      body = state.snapshot
      if (state.failRead) {
        body = { error: 'Fixture readback unavailable' }
        status = 503
      }
    } else if (request.method() === 'POST' && path.startsWith('/api/ext/terrain/')) {
      const data = request.postDataJSON()
      state.writes.push({ path, data })
      if (state.abortWrite) return route.abort('connectionreset')
      if (path === '/api/ext/terrain/worlds') {
        state.snapshot.worlds.push(world(data.name, data.config, data.seed || '123456789'))
        if (state.mismatchAfterWrite) state.snapshot.worlds.at(-1).config.horizontalScale = 2.9
        status = 202
        body = { ok: true, name: data.name, message: '世界创建已排队' }
      } else if (path.endsWith('/unload')) {
        const item = state.snapshot.worlds.find(row => path.includes(`/${row.name}/`))
        Object.assign(item, { loaded: false, autoLoad: false, status: 'unloaded' })
        body = { ok: true, message: '地形世界已保存并卸载' }
      } else if (path.endsWith('/load')) {
        const item = state.snapshot.worlds.find(row => path.includes(`/${row.name}/`))
        Object.assign(item, { loaded: true, autoLoad: true, status: 'ready' })
        status = 202
        body = { ok: true, name: item.name, message: '世界加载已排队' }
      } else {
        status = 202
        body = { ok: true, name: path.split('/').at(-2), message: '传送请求已提交，请核对玩家位置' }
      }
      if (state.failAfterWrite) state.failRead = true
    } else {
      body = { error: `Unmocked fixture request: ${path}` }
      status = 404
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${base}/panel/terrain/tectonic`, { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: /Tectonic/ }).waitFor()
  return { context, page }
}
async function noOverflow(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), true,
    'terrain workspace has no horizontal page overflow')
}
async function confirmAction(page, command, cancel = false) {
  await page.getByRole('button', { name: command, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.waitFor()
  await dialog.getByRole('button', { name: cancel ? '取消' : '确认执行', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
}
const confirmCreate = (page, cancel = false) => confirmAction(page, '创建世界', cancel)
try {
  const state = fixture()
  const { context, page } = await open(state)
  await noOverflow(page)
  await page.screenshot({ path: fileURLToPath(new URL('ui-desktop-mock.png', output)), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await noOverflow(page)
  await page.screenshot({ path: fileURLToPath(new URL('ui-mobile-mock.png', output)), fullPage: true, animations: 'disabled' })
  await page.locator('.worlds-section').scrollIntoViewIfNeeded()
  await page.screenshot({ path: fileURLToPath(new URL('ui-mobile-worlds-mock.png', output)), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 1440, height: 1040 })
  await page.getByLabel('世界名称', { exact: true }).fill('fixture_peak')
  await page.getByLabel('世界种子', { exact: true }).fill('9223372036854775807')
  await page.getByLabel('地形预设', { exact: true }).focus()
  await page.getByLabel('地形预设', { exact: true }).press('ArrowDown')
  await page.getByRole('option', { name: '极高山脉', exact: true }).click()
  await confirmCreate(page, true)
  assert.equal(state.writes.length, 0, 'cancelled confirmation has no side effects')
  await confirmCreate(page)
  await page.getByText('fixture_peak', { exact: true }).first().waitFor()
  assert.equal(state.writes.length, 1)
  assert.equal(state.writes[0].data.seed, '9223372036854775807', '64-bit seed stays a string')
  assert.equal(state.writes[0].data.config.height, 1024, 'monumental preset reaches the backend')
  results.push('desktop/mobile layout, cancel, native preset and exact 64-bit seed')

  await confirmAction(page, '保存并卸载')
  await page.getByRole('button', { name: '加载', exact: true }).waitFor()
  assert.equal(state.writes.at(-1).path, '/api/ext/terrain/worlds/fixture_peak/unload')
  await confirmAction(page, '加载')
  await page.getByRole('button', { name: '保存并卸载', exact: true }).waitFor()
  assert.equal(state.writes.at(-1).path, '/api/ext/terrain/worlds/fixture_peak/load')
  await page.getByLabel('传送在线玩家', { exact: true }).fill('Steve')
  await confirmAction(page, '传送')
  await page.waitForFunction(() => document.querySelector('#terrain-player').value === '')
  assert.deepEqual(state.writes.at(-1), {
    path: '/api/ext/terrain/worlds/fixture_peak/teleport', data: { player: 'Steve' }
  })
  results.push('unload, reload and teleport submit the correct requests and read back state')

  await page.getByLabel('世界名称', { exact: true }).fill('uncertain_peak')
  state.failAfterWrite = true
  await confirmCreate(page)
  await page.getByText('请求结果尚未核对，请刷新世界列表后再执行操作。', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '创建世界', exact: true }).isDisabled(), true)
  assert.equal(state.writes.length, 5)
  state.failRead = false
  state.failAfterWrite = false
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.getByRole('button', { name: '创建世界', exact: true }).waitFor()
  assert.equal(await page.getByLabel('世界名称', { exact: true }).inputValue(), 'uncertain_peak',
    'refresh preserves the creation draft')
  await page.getByLabel('世界名称', { exact: true }).fill('recovered_peak')
  assert.equal(await page.getByRole('button', { name: '创建世界', exact: true }).isEnabled(), true,
    'successful readback unlocks a valid new request')
  results.push('readback failure locks writes; refresh preserves the draft')
  await context.close()

  const readOnly = await open(fixture(), ['terrain.view'])
  assert.equal(await readOnly.page.getByRole('button', { name: '创建世界', exact: true }).isDisabled(), true)
  assert.equal(await readOnly.page.getByRole('button', { name: '保存并卸载', exact: true }).isDisabled(), true)
  await readOnly.context.close()
  results.push('viewer permissions prevent management writes')

  const occupiedState = fixture()
  occupiedState.snapshot.worlds[0].players = 2
  const occupied = await open(occupiedState)
  assert.equal(await occupied.page.getByRole('button', { name: '保存并卸载', exact: true }).isDisabled(), true)
  await occupied.context.close()
  results.push('occupied worlds cannot be unloaded')

  for (const failure of ['abortWrite', 'mismatchAfterWrite']) {
    const failedState = fixture()
    failedState[failure] = true
    const failed = await open(failedState)
    await failed.page.getByLabel('世界名称', { exact: true }).fill(`test_${failure.toLowerCase()}`)
    await confirmCreate(failed.page)
    await failed.page.getByText('请求结果尚未核对，请刷新世界列表后再执行操作。', { exact: true }).waitFor()
    assert.equal(await failed.page.getByRole('button', { name: '保存并卸载', exact: true }).isDisabled(), true)
    assert.equal(failedState.writes.length, 1)
    await failed.context.close()
  }
  results.push('transport failure and mismatched generation settings both lock management writes')

  const unsupportedState = fixture()
  unsupportedState.snapshot.supported = false
  unsupportedState.snapshot.error = '原生地形生成器需要 Paper 26.3 build 202'
  const unsupported = await open(unsupportedState)
  assert.equal(await unsupported.page.getByRole('button', { name: '创建世界', exact: true }).isDisabled(), true)
  await unsupported.page.getByText(unsupportedState.snapshot.error, { exact: true }).waitFor()
  await unsupported.context.close()
  results.push('unsupported server blocks creation and shows its reason')

  const malformedState = fixture()
  malformedState.snapshot.worlds[0].seed = 9007199254740992
  const malformed = await open(malformedState)
  assert.equal(await malformed.page.getByRole('button', { name: '创建世界', exact: true }).isDisabled(), true)
  await malformed.context.close()
  results.push('malformed numeric seed response fails closed')

  const english = await open(fixture(), ['*'], 'en_US')
  await english.page.setViewportSize({ width: 390, height: 844 })
  await noOverflow(english.page)
  await english.page.screenshot({ path: fileURLToPath(new URL('ui-mobile-en-mock.png', output)), fullPage: true, animations: 'disabled' })
  await english.page.locator('.worlds-section').scrollIntoViewIfNeeded()
  await english.page.screenshot({ path: fileURLToPath(new URL('ui-mobile-worlds-en-mock.png', output)), fullPage: true, animations: 'disabled' })
  await english.context.close()
  assert.deepEqual(errors, [], 'no browser page errors')
  results.push('English mobile layout and zero browser page errors')
  await writeFile(new URL('ui-mock-results.json', output), JSON.stringify({ mode: 'mock-api', results }, null, 2))
  console.log(`Terrain UI mock verification passed:\n${results.map(item => `- ${item}`).join('\n')}`)
} finally {
  await browser.close()
}
