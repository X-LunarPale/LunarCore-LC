import { createApp, computed, h } from 'vue'
import ElementPlus, { ElConfigProvider } from 'element-plus'
import en from 'element-plus/es/locale/lang/en'
import zh from 'element-plus/es/locale/lang/zh-cn'
import zhHk from 'element-plus/es/locale/lang/zh-hk'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import Workspace from './StructuresWorkspace.vue'
import { locale, syncPreferences } from './i18n.js'

syncPreferences()
window.addEventListener('storage', syncPreferences)
window.addEventListener('languagechange', syncPreferences)
const elementLocales = { zh_CN: zh, zh_HK: zhHk, en_US: en }
const App = { setup() {
  const language = computed(() => elementLocales[locale.value])
  return () => h(ElConfigProvider, { locale: language.value }, () => h(Workspace))
} }
createApp(App).use(ElementPlus).mount('#app')
