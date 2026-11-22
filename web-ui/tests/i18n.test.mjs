import assert from 'node:assert/strict'
import { readdir } from 'node:fs/promises'
import test from 'node:test'
import { AUTO_LOCALE, LOCALE_STORAGE_KEY, resolveLocale } from '../src/utils/locale-preference.js'

const stored = new Map()
const browser = { languages: ['zh-CN'] }
globalThis.window = Object.assign(new EventTarget(), { navigator: browser })
globalThis.localStorage = {
  getItem: key => stored.get(key) ?? null,
  setItem: (key, value) => stored.set(key, value),
  removeItem: key => stored.delete(key)
}

const { locale, localeTag, localePreference, setLocale, t } = await import('../src/i18n.js')
globalThis.document = { documentElement: { lang: '' } }

test('initial language uses the first supported browser preference', () => {
  assert.equal(localePreference.value, AUTO_LOCALE)
  assert.equal(resolveLocale(AUTO_LOCALE, { languages: ['fr-FR', 'zh-HK', 'en-US'] }), 'zh_HK')
  assert.equal(resolveLocale(AUTO_LOCALE, { languages: ['en-GB', 'zh-HK'] }), 'en_US')
  assert.equal(resolveLocale(AUTO_LOCALE, { language: 'zh-Hant-TW' }), 'zh_HK')
  assert.equal(resolveLocale(AUTO_LOCALE, { languages: ['zh-Hans-HK'] }), 'zh_CN')
  assert.equal(resolveLocale(AUTO_LOCALE, { languages: ['de-DE'] }), 'zh_CN')
})

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

test('manual selection stays in effect until follow system is selected again', () => {
  try {
    browser.languages = ['zh-HK']
    setLocale(AUTO_LOCALE)
    assert.equal(locale.value, 'zh_HK')
    assert.equal(t('跟随系统'), '跟隨系統')

    setLocale('zh_HK')
    browser.languages = ['en-GB']
    window.dispatchEvent(new Event('languagechange'))
    assert.equal(locale.value, 'zh_HK')
    assert.equal(localePreference.value, 'zh_HK')
    assert.equal(stored.get(LOCALE_STORAGE_KEY), 'zh_HK')

    setLocale(AUTO_LOCALE)
    assert.equal(locale.value, 'en_US')
    assert.equal(stored.has(LOCALE_STORAGE_KEY), false)
    assert.equal(document.documentElement.lang, 'en-US')
    assert.equal(t('跟随系统'), 'Follow system')

    browser.languages = ['zh-HK']
    window.dispatchEvent(new Event('languagechange'))
    assert.equal(locale.value, 'zh_HK')
  } finally {
    browser.languages = ['zh-CN']
    setLocale('zh_CN')
  }
})

test('changing a preference in another tab updates the selected language', () => {
  try {
    browser.languages = ['en-US']
    stored.set(LOCALE_STORAGE_KEY, 'zh_HK')
    const changedPreference = Object.assign(new Event('storage'), { key: LOCALE_STORAGE_KEY })
    window.dispatchEvent(changedPreference)
    assert.equal(localePreference.value, 'zh_HK')
    assert.equal(locale.value, 'zh_HK')

    stored.delete(LOCALE_STORAGE_KEY)
    window.dispatchEvent(changedPreference)
    assert.equal(localePreference.value, AUTO_LOCALE)
    assert.equal(locale.value, 'en_US')
  } finally {
    browser.languages = ['zh-CN']
    setLocale('zh_CN')
  }
})

test('Hong Kong Chinese updates the page language and survives a reload', async () => {
  try {
    setLocale('zh_HK')
    assert.equal(localeTag.value, 'zh-HK')
    assert.equal(document.documentElement.lang, 'zh-HK')
    assert.equal(t('语言'), '語言')
    assert.equal(t('请求失败（HTTP {status}）', { status: 403 }), '請求失敗（HTTP 403）')
    assert.equal(t('我的生存世界'), '我的生存世界')
    assert.equal(t('constructor'), 'constructor')
    assert.equal(stored.get('lc-locale'), 'zh_HK')

    document.documentElement.lang = 'zh-CN'
    const reloaded = await import('../src/i18n.js?reloaded')
    assert.equal(reloaded.locale.value, 'zh_HK')
    assert.equal(document.documentElement.lang, 'zh-HK')
    assert.equal(reloaded.t('语言'), '語言')
  } finally {
    setLocale('zh_CN')
  }
})

test('invalid language values cannot change the selection', () => {
  setLocale('fr_FR')
  setLocale('constructor')
  assert.equal(locale.value, 'zh_CN')
})

test('dictionaries preserve placeholders and English fragments agree on shared messages', async () => {
  const directory = new URL('../src/locales/', import.meta.url)
  const files = (await readdir(directory)).filter(name => name.endsWith('.js'))
  const englishMessages = new Map()
  const placeholders = message => [...new Set([...message.matchAll(/\{(\w+)\}/g)].map(match => match[1]))].sort()
  for (const file of files) {
    const { default: dictionary } = await import(new URL(file, directory))
    for (const [source, translation] of Object.entries(dictionary)) {
      assert.equal(typeof translation, 'string', `${file}: ${source}`)
      assert.ok(translation.trim(), `${file}: empty translation for ${source}`)
      assert.deepEqual(placeholders(translation), placeholders(source), `${file}: placeholders in ${source}`)
      if (file.startsWith('en-')) {
        if (englishMessages.has(source)) assert.equal(translation, englishMessages.get(source), `Conflicting translation for ${source}`)
        englishMessages.set(source, translation)
      }
    }
  }
})
