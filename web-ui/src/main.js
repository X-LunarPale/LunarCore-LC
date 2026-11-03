import { createApp, watch } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles.css'
import App from './App.vue'
import router, { updateTitle } from './router'
import Icon from './components/Icon.vue'
import { setAuthHooks, url } from './api'
import { useAuth } from './stores/auth'
import { useApp } from './stores/app'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(ElementPlus)
app.component('Icon', Icon)

const auth = useAuth()
const panelApp = useApp()
watch(() => [panelApp.info?.brand, panelApp.info?.logoUrl], () => {
  updateTitle(router.currentRoute.value)
  const favicon = document.querySelector('link[rel="icon"]')
  if (favicon) {
    favicon.href = url(panelApp.info?.logoUrl || '/favicon.svg')
    favicon.removeAttribute('type')
  }
})
setAuthHooks(
  () => auth.token,
  () => {
    auth.logout(false)
    if (router.currentRoute.value.name !== 'login') {
      router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  }
)

app.use(router)
app.mount('#app')
