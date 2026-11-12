import assert from 'node:assert/strict'
import { readdir } from 'node:fs/promises'
import test from 'node:test'

const stored = new Map()
globalThis.localStorage = {
  getItem: key => stored.get(key) ?? null,
  setItem: (key, value) => stored.set(key, value)
}

const { locale, setLocale, t } = await import('../src/i18n.js')

test('language changes live and persists without translating server content', () => {
  assert.equal(locale.value, 'zh_CN')
  assert.equal(t('登录控制台'), '登录控制台')
  setLocale('en_US')
  assert.equal(locale.value, 'en_US')
  assert.equal(stored.get('lc-locale'), 'en_US')
  assert.equal(t('登录控制台'), 'Sign In')
  assert.equal(t('请求失败（HTTP {status}）', { status: 403 }), 'Request failed (HTTP 403)')
  assert.equal(t('来自服务器的未知文案'), '来自服务器的未知文案')
  setLocale('zh_CN')
  assert.equal(t('请求失败（HTTP {status}）', { status: 403 }), '请求失败（HTTP 403）')
})

test('invalid language values cannot change the selection', () => {
  setLocale('fr_FR')
  assert.equal(locale.value, 'zh_CN')
})

test('dictionaries preserve placeholders and agree on shared messages', async () => {
  const directory = new URL('../src/locales/', import.meta.url)
  const files = (await readdir(directory)).filter(name => name.endsWith('.js'))
  const messages = new Map()
  const placeholders = message => [...new Set([...message.matchAll(/\{(\w+)\}/g)].map(match => match[1]))].sort()
  for (const file of files) {
    const { default: dictionary } = await import(new URL(file, directory))
    for (const [source, translation] of Object.entries(dictionary)) {
      assert.equal(typeof translation, 'string', `${file}: ${source}`)
      assert.ok(translation.trim(), `${file}: empty translation for ${source}`)
      assert.deepEqual(placeholders(translation), placeholders(source), `${file}: placeholders in ${source}`)
      if (messages.has(source)) assert.equal(translation, messages.get(source), `Conflicting translation for ${source}`)
      messages.set(source, translation)
    }
  }
})
