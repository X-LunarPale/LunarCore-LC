import assert from 'node:assert/strict'
import test from 'node:test'
import english from '../src/en.js'
import hongKong from '../src/zh-hk.js'
import { locale, syncPreferences, t } from '../src/i18n.js'

test('the structures frame follows the panel locale and preserves unknown content', () => {
  const settings = new Map([['lc-locale', 'zh_HK']])
  globalThis.window = { navigator: { languages: ['en-GB'] } }
  globalThis.localStorage = { getItem: key => settings.get(key) ?? null }
  globalThis.document = { documentElement: { classList: { toggle() {} } } }

  syncPreferences()
  assert.equal(locale.value, 'zh_HK')
  assert.equal(document.documentElement.lang, 'zh-HK')
  assert.equal(document.title, '結構美化')
  assert.equal(t('保存结构设置'), '儲存結構設定')
  assert.equal(t('已选 {count} 个世界：{names}', { count: 1, names: '新世界' }), '已選取 1 個世界：新世界')
  assert.equal(t('服务器自定义提示'), '服务器自定义提示')
  assert.equal(t('constructor'), 'constructor')

  settings.set('lc-locale', 'en_US')
  syncPreferences()
  assert.equal(document.documentElement.lang, 'en-US')
  assert.equal(t('保存结构设置'), 'Save structure settings')
})

test('automatic mode uses the first supported browser language and falls back to Simplified Chinese', () => {
  const settings = new Map()
  const browser = { languages: ['fr-FR', 'zh-HK', 'en-GB'] }
  globalThis.window = { navigator: browser }
  globalThis.localStorage = { getItem: key => settings.get(key) ?? null }
  globalThis.document = { documentElement: { classList: { toggle() {} } } }

  syncPreferences()
  assert.equal(document.documentElement.lang, 'zh-HK')

  settings.set('lc-locale', 'en_US')
  syncPreferences()
  assert.equal(document.documentElement.lang, 'en-US')

  settings.delete('lc-locale')
  syncPreferences()
  assert.equal(document.documentElement.lang, 'zh-HK')

  browser.languages = ['fr-FR']
  syncPreferences()
  assert.equal(document.documentElement.lang, 'zh-CN')
  assert.equal(t('保存结构设置'), '保存结构设置')
})

test('Hong Kong translations cover the catalog and preserve placeholders', () => {
  assert.deepEqual(Object.keys(hongKong).sort(), Object.keys(english).sort())
  const placeholders = text => text.match(/\{\w+\}/g)?.sort() ?? []
  for (const [source, translation] of Object.entries(hongKong)) {
    assert.ok(translation.trim(), source)
    assert.deepEqual(placeholders(translation), placeholders(source), source)
  }
})
