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

  it('clears previous values when the form is emptied', () => {
    const local = memoryStorage()
    const session = memoryStorage()
    saveConnectionSettings({ sshUsername: 'alice', token: 'secret' }, local, session)

    saveConnectionSettings({ sshUsername: '', token: '' }, local, session)

    expect(loadConnectionSettings(local, session)).toEqual({ sshUsername: '', token: '' })
  })
})
