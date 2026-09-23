const DEFAULT_BASE_URL = ''

export function formatRelativeDate(value, now = new Date()) {
  const date = value instanceof Date ? value : new Date(value)
  const seconds = Math.round((date.getTime() - now.getTime()) / 1000)
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })

  if (Math.abs(seconds) < 60) {
    return formatter.format(seconds, 'second')
  }

  const minutes = Math.round(seconds / 60)
  if (Math.abs(minutes) < 60) {
    return formatter.format(minutes, 'minute')
  }

  const hours = Math.round(minutes / 60)
  if (Math.abs(hours) < 24) {
    return formatter.format(hours, 'hour')
  }

  return formatter.format(Math.round(hours / 24), 'day')
}

export function createOrionClient(options = {}) {
  const baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/$/, '')
  const fetchImpl = options.fetchImpl ?? globalThis.fetch
  let token = options.token ?? ''

  async function openResponse(path, init = {}) {
    const headers = new Headers(init.headers)
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    if (init.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }

    const response = await fetchImpl(`${baseUrl}${path}`, { ...init, headers })
    if (!response.ok) {
      const detail = await response.text()
      const error = new Error(detail || `Orion returned ${response.status}`)
      error.status = response.status
      throw error
    }

    return response
  }

  async function request(path, init = {}) {
    const response = await openResponse(path, init)
    const contentType = response.headers.get('content-type') ?? ''
    return contentType.includes('application/json') ? response.json() : response.text()
  }

  return {
    setToken(value) {
      token = value
    },
    decisions(signal) {
      return request('/api/admin/decisions', { signal })
    },
    resolveDecision(id, action, signal) {
      return request('/api/admin/decisions', {
        method: 'POST', body: JSON.stringify({ id, action }), signal,
      })
    },
    sessionEvents(sessionId, after, signal, follow) {
      const query = new URLSearchParams({ follow: String(follow) })
      if (after !== null) query.set('after', after)
      return openResponse(`/api/admin/sessions/${encodeURIComponent(sessionId)}/events?${query}`, { signal })
    },
    sendSessionCommand(sessionId, command, signal) {
      return request(`/api/admin/sessions/${encodeURIComponent(sessionId)}/commands`, {
        method: 'POST', body: JSON.stringify(command), signal,
      })
    },
    sessionCommandStatus(sessionId, commandId, signal) {
      const query = new URLSearchParams({ commandId })
      return request(`/api/admin/sessions/${encodeURIComponent(sessionId)}/commands?${query}`, { signal })
    },
    routes() {
      return request('/api/admin/routes')
    },
    transports() {
      return request('/api/admin/transports')
    },
    lifecycleState() {
      return request('/api/admin/lifecycle/state')
    },
    remoteAliases() {
      return request('/api/admin/proxies')
    },
    mutateRemoteAlias(command) {
      return request('/api/admin/proxies', { method: 'POST', body: JSON.stringify(command) })
    },
    repositories() {
      return request('/api/admin/repositories')
    },
    createRepository(name) {
      return request('/api/admin/repositories', {
        method: 'POST',
        body: JSON.stringify({ name }),
      })
    },
    createOrUpdateUser(user) {
      return request('/api/admin/users', {
        method: 'POST',
        body: JSON.stringify(user),
      })
    },
  }
}
