import { loadConnectionSettings, saveConnectionSettings } from './connection-store.js'

export function consumeDevLogin() {
  if (!import.meta.env.DEV) return
  const fragment = new URLSearchParams(window.location.hash.slice(1))
  if (!fragment.has('dev-token')) return
  const token = fragment.get('dev-token').trim()
  window.history.replaceState(null, '', window.location.pathname + window.location.search)
  if (token) saveConnectionSettings({ sshUsername: loadConnectionSettings().sshUsername, token })
}
