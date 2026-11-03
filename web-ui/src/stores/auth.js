import { defineStore } from 'pinia'
import { api, socket } from '../api'
import { useApp } from './app'

const TOKEN_KEY = 'lbr-token'

function matchGlob(pattern, node) {
  if (pattern === '*' || pattern === node) return true
  if (!pattern.includes('*')) return false
  if (pattern.endsWith('.*') && node === pattern.slice(0, -2)) return true
  const re = new RegExp('^' + pattern.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$')
  return re.test(node)
}

export const useAuth = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null
  }),
  getters: {
    loggedIn: (s) => !!s.token
  },
  actions: {
    setToken(token) {
      if ((token || '') !== this.token) socket.close()
      this.token = token || ''
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
      if (token) socket.connect()
    },
    async login(username, password) {
      const res = await api.post('/api/auth/login', { username, password }, { noAuthRedirect: true })
      if (res.token) { this.setToken(res.token); this.user = res.user }
      return res
    },
    async gameLogin(code) {
      const res = await api.post('/api/auth/game', { code }, { noAuthRedirect: true })
      if (res.token) { this.setToken(res.token); this.user = res.user }
      return res
    },
    async completeTotp(challenge, code) {
      const res = await api.post('/api/auth/totp/complete', { challenge, code }, { noAuthRedirect: true })
      this.setToken(res.token)
      this.user = res.user
      return res
    },
    async fetchMe() {
      if (!this.token) return null
      this.user = await api.get('/api/auth/me')
      return this.user
    },
    async logout(remote = true) {
      try { localStorage.removeItem('lbr-cmd-history') } catch { /* storage unavailable */ }
      useApp().clearPanelCache()
      if (remote && this.token) {
        try {
          await api.post('/api/auth/logout', {}, { noAuthRedirect: true })
        } catch {
          /* ignore */
        }
      }
      socket.close()
      this.setToken('')
      this.user = null
    },
    can(node) {
      if (!node) return true
      const perms = this.user?.permissions || []
      let allowed = false
      for (const p of perms) {
        if (!p) continue
        if (p.startsWith('-')) {
          if (matchGlob(p.slice(1), node)) return false
        } else if (matchGlob(p, node)) allowed = true
      }
      return allowed
    }
  }
})
