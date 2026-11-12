export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status }
}

// Same-origin extension, including installations beneath a reverse-proxy path prefix.
export function extensionBase(pathname) {
  const marker = '/ext/structures/'
  const index = pathname.lastIndexOf(marker)
  if (index < 0) throw new ApiError(0, '结构管理页面地址无效')
  return pathname.slice(0, index)
}

export async function request(method, path, body) {
  if (!/^\/api\/ext\/structures\/(status|config|restart)$/.test(path))
    throw new ApiError(0, '禁止请求外部接口地址')
  const token = localStorage.getItem('lc-token') || ''
  if (!token) throw new ApiError(401, '登录已失效，请在管理面板重新登录')
  const target = new URL(extensionBase(location.pathname) + path, location.origin)
  if (target.origin !== location.origin) throw new ApiError(0, '禁止请求外部接口地址')
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 30000)
  try {
    const response = await fetch(target, {
      method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error', signal: controller.signal
    })
    const result = await response.json()
    if (response.status === 401) throw new ApiError(401, '登录已失效，请在管理面板重新登录')
    if (!response.ok) throw new ApiError(response.status, result.error || '结构管理请求失败')
    return result
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, controller.signal.aborted ? '请求超时，请刷新核对后再重试' : '无法连接结构管理接口')
  } finally { clearTimeout(timer) }
}

export const api = { get: path => request('GET', path), post: (path, body) => request('POST', path, body) }
