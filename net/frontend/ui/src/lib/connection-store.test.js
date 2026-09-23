import { describe, expect, it } from 'vitest'
import { loadConnectionSettings, saveConnectionSettings } from './connection-store.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value),
  }
}

describe('connection storage', () => {
  it('keeps the SSH username separately from the session-only token', () => {
    const local = memoryStorage()
    const session = memoryStorage()

    saveConnectionSettings({ sshUsername: ' alice ', token: 'secret' }, local, session)

    expect(loadConnectionSettings(local, session)).toEqual({
      sshUsername: 'alice',
      token: 'secret',
    })
    expect(local.getItem('orion.ui.token')).toBeNull()
  })

  it('restores renewal metadata after a reload and removes it for manual credentials', () => {
    const local = memoryStorage()
    const session = memoryStorage()
    const oidc = { expiresAt: 100, organization: 'acme', userId: 'alice' }
    saveConnectionSettings({ sshUsername: '', token: 'oidc-token', oidc }, local, session)
    expect(loadConnectionSettings(local, session)).toEqual({ sshUsername: '', token: 'oidc-token', oidc })
    saveConnectionSettings({ sshUsername: '', token: 'manual-token' }, local, session)
    expect(loadConnectionSettings(local, session)).toEqual({ sshUsername: '', token: 'manual-token' })
    expect(session.getItem('orion.ui.oidc')).toBeNull()
  })

  it('ignores malformed renewal metadata', () => {
    const local = memoryStorage()
    const session = memoryStorage()
    session.setItem('orion.ui.oidc', '{broken')
    expect(loadConnectionSettings(local, session)).toEqual({ sshUsername: '', token: '' })
  })

  it('clears previous values when the form is emptied', () => {
    const local = memoryStorage()
    const session = memoryStorage()
    saveConnectionSettings({ sshUsername: 'alice', token: 'secret' }, local, session)

    saveConnectionSettings({ sshUsername: '', token: '' }, local, session)

    expect(loadConnectionSettings(local, session)).toEqual({ sshUsername: '', token: '' })
  })
})
