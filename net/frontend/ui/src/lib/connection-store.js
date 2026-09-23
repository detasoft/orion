const SSH_USERNAME_KEY = 'orion.ui.ssh-username'
const TOKEN_KEY = 'orion.ui.token'
const OIDC_KEY = 'orion.ui.oidc'

export function loadConnectionSettings(local = localStorage, session = sessionStorage) {
  let oidc
  try {
    const stored = JSON.parse(session.getItem(OIDC_KEY))
    if (stored && Number.isFinite(stored.expiresAt) && stored.organization && stored.userId) oidc = stored
  } catch { /* Ignore invalid saved session metadata. */ }
  return {
    ...(oidc ? { oidc } : {}),
    sshUsername: local.getItem(SSH_USERNAME_KEY) ?? '',
    token: session.getItem(TOKEN_KEY) ?? '',
  }
}

export function saveConnectionSettings(settings, local = localStorage, session = sessionStorage) {
  if (settings.oidc && settings.token) {
    session.setItem(OIDC_KEY, JSON.stringify(settings.oidc))
  } else {
    session.removeItem(OIDC_KEY)
  }
  const sshUsername = settings.sshUsername.trim()
  if (sshUsername) {
    local.setItem(SSH_USERNAME_KEY, sshUsername)
  } else {
    local.removeItem(SSH_USERNAME_KEY)
  }

  if (settings.token) {
    session.setItem(TOKEN_KEY, settings.token)
  } else {
    session.removeItem(TOKEN_KEY)
  }
}
