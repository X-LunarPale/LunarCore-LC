import { createApp, computed, h } from 'vue'
import ElementPlus, { ElConfigProvider } from 'element-plus'
import en from 'element-plus/es/locale/lang/en'
import zh from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import Workspace from './StructuresWorkspace.vue'
import { locale, syncPreferences } from './i18n.js'

syncPreferences()
window.addEventListener('storage', syncPreferences)
const App = { setup() {
  const language = computed(() => locale.value === 'en_US' ? en : zh)
  return () => h(ElConfigProvider, { locale: language.value }, () => h(Workspace))
} }
createApp(App).use(ElementPlus).mount('#app')
