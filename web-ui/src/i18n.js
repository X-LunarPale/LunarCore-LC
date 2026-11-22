import { computed, ref, watch } from 'vue'
import enShell from './locales/en-shell.js'
import enCoreViews from './locales/en-core-views.js'
import enAdminViews from './locales/en-admin-views.js'
import enWorkspaces from './locales/en-workspaces.js'
import enPanel from './locales/en-panel.js'
import enBranding from './locales/en-branding.js'
import enSurvival from './locales/en-survival.js'
import enTerrain from './locales/en-terrain.js'
import enAiChat from './locales/en-ai-chat.js'
import enQQAuth from './locales/en-qqauth.js'
import enGrimConfig from './locales/en-grim-config.js'
import enGrimSchema from './locales/en-grim-schema.js'
import enSecurity from './locales/en-security.js'
import enMarket from './locales/en-market.js'
import enMaintenanceRewrite from './locales/en-maintenance-rewrite.js'
import enShellRewrite from './locales/en-shell-rewrite.js'
import enViewsRewrite from './locales/en-views-rewrite.js'
import enPanelsRewrite from './locales/en-panels-rewrite.js'
import zhHk from './locales/zh-hk.js'
import { AUTO_LOCALE, LOCALE_STORAGE_KEY, LOCALE_TAGS, readLocalePreference, resolveLocale } from './utils/locale-preference.js'

const en = { ...enCoreViews, ...enAdminViews, ...enWorkspaces, ...enPanel, ...enBranding, ...enSurvival, ...enTerrain, ...enAiChat, ...enQQAuth, ...enGrimConfig, ...enGrimSchema, ...enSecurity, ...enMarket, ...enShell, ...enMaintenanceRewrite, ...enShellRewrite, ...enViewsRewrite, ...enPanelsRewrite }
const dictionaries = { zh_HK: zhHk, en_US: en }

export const localePreference = ref(readLocalePreference())
export const locale = ref(resolveLocale(localePreference.value))
export const localeTag = computed(() => LOCALE_TAGS[locale.value])
watch(localeTag, tag => {
  if (typeof document !== 'undefined') document.documentElement.lang = tag
}, { immediate: true, flush: 'sync' })

function applyLocale(preference = localePreference.value) {
  localePreference.value = preference
  locale.value = resolveLocale(preference)
}

if (globalThis.window?.addEventListener) {
  window.addEventListener('languagechange', () => applyLocale())
  window.addEventListener('storage', event => {
    if (event.key === LOCALE_STORAGE_KEY || event.key === null) applyLocale(readLocalePreference())
  })
}

export function setLocale(value) {
  if (value !== AUTO_LOCALE && !Object.hasOwn(LOCALE_TAGS, value)) return
  applyLocale(value)
  try {
    if (value === AUTO_LOCALE) localStorage.removeItem(LOCALE_STORAGE_KEY)
    else localStorage.setItem(LOCALE_STORAGE_KEY, value)
  } catch { /* storage unavailable */ }
}

export function t(source, params = {}) {
  if (source == null) return ''
  const text = String(source)
  const messages = dictionaries[locale.value]
  const message = messages && Object.hasOwn(messages, text) ? messages[text] : text
  return message.replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))
}

export function formatVersion(version) {
  return version ? `v${String(version).replace(/-preview$/, ` ${t('测试预览版')}`)}` : ''
}
