export const LOCALE_STORAGE_KEY = 'lc-locale'
export const AUTO_LOCALE = 'auto'
export const LOCALE_TAGS = { zh_CN: 'zh-CN', zh_HK: 'zh-HK', en_US: 'en-US' }

export function readLocalePreference() {
  try {
    const value = localStorage.getItem(LOCALE_STORAGE_KEY)
    if (Object.hasOwn(LOCALE_TAGS, value)) return value
  } catch { /* storage unavailable */ }
  return AUTO_LOCALE
}

export function resolveLocale(preference, browser = globalThis.window?.navigator) {
  if (Object.hasOwn(LOCALE_TAGS, preference)) return preference

  const languages = browser?.languages?.length ? browser.languages : [browser?.language || '']
  for (const tag of languages) {
    const [language, detail] = tag.toLowerCase().split(/[-_]/)
    if (language === 'en') return 'en_US'
    if (language === 'zh') {
      return ['hant', 'hk', 'mo', 'tw'].includes(detail) ? 'zh_HK' : 'zh_CN'
    }
  }
  return 'zh_CN'
}
