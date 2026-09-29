import { afterEach, expect, it, vi } from 'vitest'
import { consumeDevLogin } from './dev-login.js'

afterEach(() => {
  vi.unstubAllEnvs()
  sessionStorage.clear()
  localStorage.clear()
  window.history.replaceState(null, '', '/')
})

it('consumes a development login link once, preserving the SSH username', () => {
  vi.stubEnv('DEV', true)
  localStorage.setItem('orion.ui.ssh-username', 'alice')
  sessionStorage.setItem('orion.ui.oidc', '{"organization":"old"}')
  window.history.replaceState(null, '', '/?view=dev#dev-token=secret%2Bvalue')
  consumeDevLogin()
  expect(sessionStorage.getItem('orion.ui.token')).toBe('secret+value')
  expect(sessionStorage.getItem('orion.ui.oidc')).toBeNull()
  expect(localStorage.getItem('orion.ui.ssh-username')).toBe('alice')
  expect(window.location.href).not.toContain('secret')
  expect(window.location.search).toBe('?view=dev')
  sessionStorage.removeItem('orion.ui.token')
  consumeDevLogin()
  expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
})

it('does not accept development login links in production', () => {
  vi.stubEnv('DEV', false)
  window.history.replaceState(null, '', '/#dev-token=secret')
  consumeDevLogin()
  expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
})

it('preserves normal navigation and an existing connection', () => {
  vi.stubEnv('DEV', true)
  sessionStorage.setItem('orion.ui.token', 'existing')
  window.history.replaceState(null, '', '/#/repositories')
  consumeDevLogin()
  expect(window.location.hash).toBe('#/repositories')
  expect(sessionStorage.getItem('orion.ui.token')).toBe('existing')
})
