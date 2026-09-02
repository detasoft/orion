const SSH_USERNAME_KEY = 'orion.ui.ssh-username'
const TOKEN_KEY = 'orion.ui.token'

export function loadConnectionSettings(local = localStorage, session = sessionStorage) {
  return {
    sshUsername: local.getItem(SSH_USERNAME_KEY) ?? '',
    token: session.getItem(TOKEN_KEY) ?? '',
  }
}

export function saveConnectionSettings(settings, local = localStorage, session = sessionStorage) {
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
