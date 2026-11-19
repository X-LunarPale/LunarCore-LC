export function loginDestination(redirect, resolve) {
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//') || redirect.startsWith('/\\')) return '/'
  const route = resolve(redirect)
  return route.name && route.name !== 'login' && route.name !== 'notfound' ? route.fullPath : '/'
}
