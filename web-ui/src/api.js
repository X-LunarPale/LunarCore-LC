// HTTP / WebSocket 客户端
import { t } from './i18n.js'
export const BASE = (window.__LC_BASE__ || '').replace(/\/+$/, '')

let tokenGetter = () => null
let unauthorizedHandler = () => {}

export function setAuthHooks(getToken, onUnauthorized) {
  tokenGetter = getToken
  unauthorizedHandler = onUnauthorized
}

/** 把面板/接口中的路径转换为完整 URL（处理 base-path）。 */
export function url(path) {
  if (/^https?:\/\//i.test(path)) return path
  if (!path.startsWith('/')) path = '/' + path
  return BASE + path
}

/** WebPanel endpoint：以 / 开头为绝对路径，否则相对于 /api/ext/{namespace}/ */
export function resolveEndpoint(endpoint, namespace) {
  if (!endpoint) return ''
  if (/^https?:\/\//i.test(endpoint) || endpoint.startsWith('/')) return endpoint
  return `/api/ext/${namespace}/${endpoint.replace(/^\.?\//, '')}`
}

export class ApiError extends Error {
  constructor(status, message, data) {
    super(message)
    this.status = status
    this.data = data
  }
}

export async function request(method, path, body, options = {}) {
  const headers = { Accept: 'application/json' }
  const token = tokenGetter()
  if (token) headers.Authorization = 'Bearer ' + token
  let payload
  if (body !== undefined && body !== null && method !== 'GET') {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  let fullPath = path
  if (options.query) {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(options.query)) {
      if (v !== undefined && v !== null && v !== '') qs.append(k, v)
    }
    const s = qs.toString()
    if (s) fullPath += (fullPath.includes('?') ? '&' : '?') + s
  }
  // Panel metadata must never send the session token or request body to another origin.
  const target = new URL(url(fullPath), window.location.href)
  if (target.origin !== window.location.origin || !/^https?:$/.test(target.protocol) || target.username || target.password) {
    throw new ApiError(0, t('禁止请求外部接口地址'))
  }
  let res, text
  const controller = new AbortController()
  const timeoutMs = Number.isFinite(options.timeoutMs) && options.timeoutMs > 0 ? options.timeoutMs : 30000
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    res = await fetch(target.href, { method, headers, body: payload, redirect: 'error', signal: controller.signal })
    text = await res.text()
  } catch (e) {
    if (controller.signal.aborted) throw new ApiError(0, t('请求超时，操作结果可能尚未确认，请刷新核对后再重试'))
    throw new ApiError(0, t('无法连接到服务器：{error}', { error: e.message }))
  } finally {
    clearTimeout(timer)
  }
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!res.ok) {
    // Ignore an old request's 401 after a password change or a new login.
    if (res.status === 401 && !options.noAuthRedirect && token === tokenGetter()) unauthorizedHandler()
    const msg = (data && typeof data === 'object' && (data.error || data.message)) || t('请求失败（HTTP {status}）', { status: res.status })
    throw new ApiError(res.status, msg, data)
  }
  return data
}

export const api = {
  get: (p, query, opt) => request('GET', p, null, { ...opt, query }),
  post: (p, body, opt) => request('POST', p, body ?? {}, opt),
  put: (p, body, opt) => request('PUT', p, body ?? {}, opt),
  del: (p, body, opt) => request('DELETE', p, body, opt)
}

// ---------------------------------------------------------------- WebSocket

class Socket {
  constructor() {
    this.ws = null
    this.handlers = new Map() // topic -> Set<fn>
    this.retry = 0
    this.timer = null
    this.closedByUser = false
    this.statusListeners = new Set()
    this.connected = false
  }

  wsUrl() {
    const loc = window.location
    const proto = loc.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${proto}//${loc.host}${BASE}/api/ws?token=${encodeURIComponent(tokenGetter() || '')}`
  }

  connect() {
    if (this.ws && (this.ws.readyState === 0 || this.ws.readyState === 1)) return
    if (!tokenGetter()) return
    this.closedByUser = false
    const ws = new WebSocket(this.wsUrl())
    this.ws = ws
    ws.onopen = () => {
      if (this.ws !== ws) return
      this.retry = 0
      this.setConnected(true)
      for (const topic of this.handlers.keys()) this.sendRaw({ op: 'sub', topic })
      clearInterval(this.ping)
      this.ping = setInterval(() => this.sendRaw({ op: 'ping' }), 30000)
    }
    ws.onmessage = (ev) => {
      if (this.ws !== ws) return
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch {
        return
      }
      if (msg.topic === '_backlog') {
        this.emit(msg.data.topic, msg.data.items, true)
      } else {
        this.emit(msg.topic, msg.data, false)
      }
    }
    ws.onclose = (ev) => {
      if (this.ws !== ws) return
      clearInterval(this.ping)
      this.setConnected(false)
      this.ws = null
      if (ev.code === 4001) {
        // Password rotation can close the old socket while the replacement JWT is arriving.
        // The next REST check owns invalidation; never clear a newer token here.
        clearTimeout(this.timer)
        this.timer = setTimeout(() => {
          request('GET', '/api/auth/me').then(() => this.connect()).catch(() => {})
        }, 500)
        return
      }
      if (this.closedByUser) return
      const delay = Math.min(30000, 1000 * Math.pow(2, this.retry++))
      clearTimeout(this.timer)
      this.timer = setTimeout(() => this.connect(), delay)
    }
  }

  setConnected(v) {
    this.connected = v
    for (const fn of this.statusListeners) fn(v)
  }

  onStatus(fn) {
    this.statusListeners.add(fn)
    fn(this.connected)
    return () => this.statusListeners.delete(fn)
  }

  close() {
    this.closedByUser = true
    this.setConnected(false)
    clearTimeout(this.timer)
    clearInterval(this.ping)
    if (this.ws) this.ws.close()
    this.ws = null
  }

  sendRaw(obj) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj))
  }

  emit(topic, data, backlog) {
    const set = this.handlers.get(topic)
    if (set) for (const fn of set) fn(data, backlog)
  }

  /** 订阅 topic，返回取消函数。handler(data, isBacklog) */
  subscribe(topic, handler) {
    let set = this.handlers.get(topic)
    if (!set) {
      set = new Set()
      this.handlers.set(topic, set)
      this.sendRaw({ op: 'sub', topic })
    }
    set.add(handler)
    this.connect()
    return () => {
      set.delete(handler)
      if (set.size === 0) {
        this.handlers.delete(topic)
        this.sendRaw({ op: 'unsub', topic })
      }
    }
  }
}

export const socket = new Socket()
