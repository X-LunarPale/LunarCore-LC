import { ref } from 'vue'
import { LOCALE_TAGS, readLocalePreference, resolveLocale } from '../../../../web-ui/src/utils/locale-preference.js'
import english from './en.js'
import hongKong from './zh-hk.js'

const dictionaries = { zh_HK: hongKong, en_US: english }
export const locale = ref('zh_CN')

export function syncPreferences() {
  locale.value = resolveLocale(readLocalePreference())
  document.documentElement.lang = LOCALE_TAGS[locale.value]
  document.title = t('结构美化')
  try {
    document.documentElement.classList.toggle('dark', localStorage.getItem('lc-theme') === 'dark')
  } catch { /* storage unavailable */ }
}

export function t(source, params = {}) {
  const text = String(source ?? '')
  const messages = dictionaries[locale.value]
  const message = messages && Object.hasOwn(messages, text) ? messages[text] : text
  return message.replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))
}
