import { ref } from 'vue'
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

const STORAGE_KEY = 'lbr-locale'
const translations = { ...enCoreViews, ...enAdminViews, ...enWorkspaces, ...enPanel, ...enBranding, ...enSurvival, ...enTerrain, ...enAiChat, ...enQQAuth, ...enGrimConfig, ...enGrimSchema, ...enSecurity, ...enMarket, ...enShell }

function storedLocale() {
  try { return localStorage.getItem(STORAGE_KEY) === 'en_US' ? 'en_US' : 'zh_CN' }
  catch { return 'zh_CN' }
}

export const locale = ref(storedLocale())
if (typeof document !== 'undefined') document.documentElement.lang = locale.value === 'en_US' ? 'en' : 'zh-CN'

export function setLocale(value) {
  if (value !== 'zh_CN' && value !== 'en_US') return
  locale.value = value
  if (typeof document !== 'undefined') document.documentElement.lang = value === 'en_US' ? 'en' : 'zh-CN'
  try { localStorage.setItem(STORAGE_KEY, value) } catch { /* storage unavailable */ }
}

export function t(source, params = {}) {
  if (source == null) return ''
  const text = String(source)
  const message = locale.value === 'en_US' ? translations[text] || text : text
  return message.replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))
}

export function formatVersion(version) {
  return version ? `v${String(version).replace(/-preview$/, ` ${t('测试预览版')}`)}` : ''
}
