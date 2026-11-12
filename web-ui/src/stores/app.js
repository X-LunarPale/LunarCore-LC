import { defineStore } from 'pinia'
import { api } from '../api'
import { t } from '../i18n.js'

export const useApp = defineStore('app', {
  state: () => ({
    dark: document.documentElement.classList.contains('dark'),
    collapsed: localStorage.getItem('lc-collapsed') === '1',
    panels: [],
    panelsLoaded: false,
    panelsGeneration: 0,
    infoGeneration: 0,
    info: null
  }),
  actions: {
    toggleTheme() {
      this.dark = !this.dark
      document.documentElement.classList.toggle('dark', this.dark)
      localStorage.setItem('lc-theme', this.dark ? 'dark' : 'light')
    },
    toggleCollapse() {
      this.collapsed = !this.collapsed
      localStorage.setItem('lc-collapsed', this.collapsed ? '1' : '0')
    },
    clearPanelCache() {
      this.panelsGeneration++
      this.panels = []
      this.panelsLoaded = false
    },
    async loadPanels() {
      const generation = ++this.panelsGeneration
      try {
        const panels = await api.get('/api/panels')
        if (generation !== this.panelsGeneration) return this.panels
        if (!Array.isArray(panels)) throw new Error(t('菜单接口返回格式错误'))
        this.panels = panels
        this.panelsLoaded = true
        return this.panels
      } catch (error) {
        if (generation !== this.panelsGeneration) return this.panels
        // Keep the last successful menu available during transient API or network failures.
        this.panelsLoaded = this.panels.length > 0
        throw error
      }
    },
    async loadInfo() {
      const generation = ++this.infoGeneration
      try {
        const info = await api.get('/api/auth/info', null, { noAuthRedirect: true })
        if (generation === this.infoGeneration) this.info = info
        return info
      } catch {
        return null
      }
    },
    findPanel(ns, id) {
      return this.panels.find((p) => p.namespace === ns && p.id === id)
    }
  }
})
