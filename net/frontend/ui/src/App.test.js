import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const client = {
  me: vi.fn(),
  refreshSession: vi.fn(),
  dispose: vi.fn(),
  logout: vi.fn(),
  oidcSettings: vi.fn(),
  invitations: vi.fn(),
  createRepository: vi.fn(),
  createOrUpdateUser: vi.fn(),
  decisions: vi.fn(),
  resolveDecision: vi.fn(),
  lifecycleState: vi.fn(),
  repositories: vi.fn(),
  remoteAliases: vi.fn(),
  routes: vi.fn(),
  transports: vi.fn(),
}

vi.mock('./lib/orion-api.js', () => ({
  createOrionClient: vi.fn(() => client),
  formatRelativeDate: vi.fn(() => 'just now'),
}))

import App from './App.vue'
import { createOrionClient } from './lib/orion-api.js'

function mountApp() {
  return mount(App, { attachTo: document.body })
}

async function connect(wrapper) {
  await wrapper.find('.server-card').trigger('click')
  const fields = wrapper.findAll('.modal input')
  await fields[0].setValue('token')
  await fields[1].setValue('alice')
  await wrapper.get('form.modal').trigger('submit')
  await flushPromises()
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

async function startRepository(wrapper, name) {
  await wrapper.get('.primary-button.compact').trigger('click')
  await wrapper.get('input[placeholder="team/project"]').setValue(name)
  await wrapper.get('form.modal').trigger('submit')
}

async function replaceToken(wrapper, token) {
  await wrapper.get('.close-button').trigger('click')
  await wrapper.get('.server-card').trigger('click')
  await wrapper.get('input[placeholder="Bearer token"]').setValue(token)
  await wrapper.get('form.modal').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.clearAllMocks()
  for (const method of Object.values(client)) method.mockReset()
  client.refreshSession.mockResolvedValue()
  client.logout.mockResolvedValue('')
  client.oidcSettings.mockResolvedValue({ revision: '1', organizations: [] })
  client.me.mockResolvedValue({ userId: 'admin', organization: '', admin: true })
  client.invitations.mockResolvedValue({ organizations: [] })
  client.routes.mockResolvedValue({
    routes: [{ urlPattern: '/api/admin/routes', methods: ['GET'], authorization: 'admin' }],
  })
  client.lifecycleState.mockResolvedValue('RUNNING')
  client.repositories.mockResolvedValue({ repositories: [] })
  client.remoteAliases.mockResolvedValue({ aliases: [] })
  client.decisions.mockResolvedValue({ decisions: [] })
  client.resolveDecision.mockResolvedValue('')
  client.transports.mockResolvedValue({
    http: { enabled: true, url: 'http://localhost:8000' },
    https: { enabled: true, url: 'https://localhost:8443' },
    ssh: { enabled: true, url: 'ssh://localhost:8022' },
    nativeGit: { enabled: true, url: 'git://localhost:9419' },
  })
  client.createRepository.mockResolvedValue({ status: 'ok' })
})

describe('Orion connection', () => {
  it('checks session renewal in the background and stops after unmount', async () => {
    vi.useFakeTimers()
    const wrapper = mountApp()
    try {
      await vi.advanceTimersByTimeAsync(30000)
      expect(client.refreshSession).toHaveBeenCalledOnce()
      window.dispatchEvent(new Event('focus'))
      await flushPromises()
      expect(client.refreshSession).toHaveBeenCalledTimes(2)
      wrapper.unmount()
      await vi.advanceTimersByTimeAsync(60000)
      expect(client.refreshSession).toHaveBeenCalledTimes(2)
      expect(client.dispose).toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('revokes the browser session when signing out of OIDC', async () => {
    sessionStorage.setItem('orion.ui.token', 'oidc-token')
    sessionStorage.setItem('orion.ui.oidc', JSON.stringify({ expiresAt: 100, organization: 'acme', userId: 'alice' }))
    client.me.mockResolvedValue({ userId: 'alice', organization: 'acme', admin: false })
    const wrapper = mountApp()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Sign out').trigger('click')
    await flushPromises()
    expect(client.logout).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
    expect(sessionStorage.getItem('orion.ui.oidc')).toBeNull()
    expect(wrapper.text()).toContain('Sign in with OIDC')
    wrapper.unmount()
  })

  it('persists renewed credentials and clears them when the session expires', async () => {
    sessionStorage.setItem('orion.ui.token', 'old')
    sessionStorage.setItem('orion.ui.oidc', JSON.stringify({ expiresAt: 1, organization: 'acme', userId: 'alice' }))
    client.me.mockResolvedValue({ userId: 'alice', organization: 'acme', admin: false })
    const wrapper = mountApp()
    await flushPromises()
    const options = createOrionClient.mock.calls.at(-1)[0]
    expect(options.oidc).toEqual({ expiresAt: 1, organization: 'acme', userId: 'alice' })
    options.onToken({ token: 'new', expiresAt: 200, organization: 'acme', userId: 'alice' })
    expect(sessionStorage.getItem('orion.ui.token')).toBe('new')
    expect(JSON.parse(sessionStorage.getItem('orion.ui.oidc')).expiresAt).toBe(200)
    options.onExpired()
    await flushPromises()
    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
    expect(sessionStorage.getItem('orion.ui.oidc')).toBeNull()
    expect(wrapper.text()).toContain('Your session has ended')
    wrapper.unmount()
  })

  it('reports a failed logout without pretending that the session was revoked', async () => {
    sessionStorage.setItem('orion.ui.token', 'oidc-token')
    sessionStorage.setItem('orion.ui.oidc', JSON.stringify({ expiresAt: 100, organization: 'acme', userId: 'alice' }))
    client.me.mockResolvedValue({ userId: 'alice', organization: 'acme', admin: false })
    client.logout.mockRejectedValueOnce(new TypeError('Offline'))
    const wrapper = mountApp()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Sign out').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Could not sign out')
    expect(sessionStorage.getItem('orion.ui.token')).toBe('oidc-token')
    wrapper.unmount()
  })

  it('connects organization users without requesting system administration data', async () => {
    client.me.mockResolvedValue({ userId: 'alice', organization: 'acme', admin: false })
    client.repositories.mockResolvedValue({ repositories: [{ name: 'acme/team/project' }] })
    const wrapper = mountApp()
    await connect(wrapper)
    expect(client.routes).not.toHaveBeenCalled()
    expect(client.transports).not.toHaveBeenCalled()
    expect(client.lifecycleState).not.toHaveBeenCalled()
    expect(wrapper.findAll('.primary-nav .nav-item').map((item) => item.text())).toEqual(['Repositories'])
    expect(wrapper.text()).toContain('acme/team/project')
    expect(wrapper.find('[aria-label="New repository"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('requires a connection before loading pending decisions', async () => {
    const wrapper = mountApp()
    const decisions = wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Pending decisions')
    expect(decisions).toBeDefined()
    await decisions.trigger('click')
    expect(wrapper.text()).toContain('Connect to Orion first')
    expect(client.decisions).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens pending decisions and records an answer after connecting', async () => {
    client.decisions.mockResolvedValue({ decisions: [{
      id: 'request-1', title: 'Review host key', description: 'Unknown host',
      scope: 'system', createdAt: '2026-09-23T12:00:00Z', actions: { accept: 'Accept key' },
    }] })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Pending decisions').trigger('click')
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(client.decisions).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Review host key')
    await wrapper.findAll('button').find((button) => button.text() === 'Accept key').trigger('click')
    await flushPromises()
    expect(client.resolveDecision).toHaveBeenCalledWith('request-1', 'accept', expect.any(AbortSignal))
    expect(wrapper.text()).toContain('Decision recorded.')
    expect(wrapper.text()).not.toContain('Review host key')
    wrapper.unmount()
  })

  it.each([401, 403])('clears the connection when pending decisions rejects credentials with %s', async (status) => {
    client.decisions.mockRejectedValueOnce(Object.assign(new Error('Expired token'), { status }))
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Pending decisions').trigger('click')
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
    expect(wrapper.get('.server-card').text()).toContain('Not connected')
    expect(wrapper.text()).toContain('Connect to Orion first')
    expect(wrapper.find('.decision-actions').exists()).toBe(false)
    wrapper.unmount()
  })

  it('offers remote aliases separately and requires a connection', async () => {
    const wrapper = mountApp()
    const aliases = wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Remote aliases')

    expect(aliases).toBeDefined()
    await aliases.trigger('click')
    expect(wrapper.text()).toContain('Connect to Orion first')
    expect(client.remoteAliases).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('loads the Remote aliases section after an authenticated connection', async () => {
    client.remoteAliases.mockResolvedValue({ aliases: [{
      scope: 'system', alias: 'configuration', upstream: 'https://git.example/config.git',
      transport: 'https', ref: 'refs/heads/main', endpoint: null, status: 'success', observedAt: null,
    }] })
    const wrapper = mountApp()
    await connect(wrapper)
    const aliases = wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Remote aliases')
    await aliases.trigger('click')
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(client.remoteAliases).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('configuration')
    expect(wrapper.text()).toContain('No public Git endpoint')
    wrapper.unmount()
  })

  it('offers the terminal and requires a connection before opening a session', async () => {
    const wrapper = mountApp()
    const terminal = wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Terminal')

    expect(terminal).toBeDefined()
    await terminal.trigger('click')
    expect(wrapper.text()).toContain('Connect to Orion first')
    expect(wrapper.find('input[aria-label="Session ID"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows verified server data after connecting', async () => {
    client.repositories.mockResolvedValue({
      repositories: [{ name: 'internal/configuration' }, { name: 'existing/project' }],
    })
    const wrapper = mountApp()

    await connect(wrapper)

    expect(client.routes).toHaveBeenCalledOnce()
    expect(client.lifecycleState).toHaveBeenCalledOnce()
    expect(client.repositories).toHaveBeenCalledOnce()
    expect(client.transports).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Registered routes')
    expect(wrapper.text()).toContain('RUNNING')
    expect(wrapper.text()).toContain('2 repositories available')
  })

  it('restores the session token and verifies the saved connection on reload', async () => {
    localStorage.setItem('orion.ui.ssh-username', 'alice')
    sessionStorage.setItem('orion.ui.token', 'token')

    const wrapper = mountApp()
    await flushPromises()

    expect(client.routes).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Server routes')
    expect(localStorage.getItem('orion.ui.token')).toBeNull()
  })

  it('adds a newly created repository to the discovered list', async () => {
    client.transports.mockResolvedValue({
      http: { enabled: true, url: 'https://git.example' },
      https: { enabled: true, url: 'https://git.example' },
      ssh: { enabled: true, url: 'ssh://git.example:2222' },
      nativeGit: { enabled: true, url: 'git://git.example:9418' },
    })
    const wrapper = mountApp()
    await connect(wrapper)

    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/my-repo')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    expect(client.createRepository).toHaveBeenCalledWith('platform/my-repo')
    expect(wrapper.text()).toContain('platform/my-repo')
    expect(wrapper.text()).toContain('reported by Orion')
    expect(wrapper.text()).toContain('ssh://alice@git.example:2222/platform/my-repo.git')
    expect(wrapper.text()).toContain('https://git.example/r/platform/my-repo')
    expect(wrapper.text()).toContain('git://git.example:9418/platform/my-repo')
    expect(wrapper.findAll('.clone-url')).toHaveLength(3)
  })

  it('clears verified state when the saved token is removed', async () => {
    const wrapper = mountApp()
    await connect(wrapper)

    await wrapper.find('.server-card').trigger('click')
    const fields = wrapper.findAll('.modal input')
    await fields[0].setValue('')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Not connected')
    expect(wrapper.text()).toContain('Connect to an Orion server')
    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
  })

  it.each([
    ['replacement', 'success'], ['replacement', 401], ['replacement', 403],
    ['', 'success'], ['', 401], ['', 403],
  ])('ignores old creation %s/%s after replacing credentials', async (token, outcome) => {
    const pending = deferred()
    client.createRepository.mockReturnValueOnce(pending.promise)
    const wrapper = mountApp()
    await connect(wrapper)
    await startRepository(wrapper, 'old/project')
    await replaceToken(wrapper, token)
    const currentToast = wrapper.get('.toast').text()

    if (outcome === 'success') pending.resolve({ created: true })
    else pending.reject(Object.assign(new Error('Old credentials rejected'), { status: outcome }))
    await flushPromises()

    expect(sessionStorage.getItem('orion.ui.token')).toBe(token || null)
    expect(wrapper.get('.toast').text()).toBe(currentToast)
    expect(wrapper.text()).not.toContain('old/project')
    expect(wrapper.get('.server-card').text()).toContain(token ? 'Connected' : 'Not connected')
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')
    expect(wrapper.findAll('.repository-row')).toHaveLength(0)
    wrapper.unmount()
  })

  it('keeps a new creation pending when the old creation finishes', async () => {
    const oldRequest = deferred()
    const newRequest = deferred()
    client.createRepository.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
    const wrapper = mountApp()
    await connect(wrapper)
    await startRepository(wrapper, 'old/project')
    await replaceToken(wrapper, 'replacement')
    await wrapper.get('.primary-button.compact').trigger('click')
    expect(wrapper.get('form.modal .primary-button').element.disabled).toBe(false)
    await wrapper.get('input[placeholder="team/project"]').setValue('new/project')
    await wrapper.get('form.modal').trigger('submit')

    oldRequest.resolve({ created: false })
    await flushPromises()
    expect(wrapper.get('form.modal .primary-button').element.disabled).toBe(true)
    expect(wrapper.get('input[placeholder="team/project"]').element.value).toBe('new/project')
    newRequest.resolve({ created: true })
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')
    expect(wrapper.text()).toContain('new/project')
    expect(wrapper.text()).not.toContain('old/project')
    wrapper.unmount()
  })

  it('invalidates pending draft verification when credentials are rejected', async () => {
    const creation = deferred()
    const verification = deferred()
    client.createRepository.mockReturnValueOnce(creation.promise)
    const wrapper = mountApp()
    await connect(wrapper)
    await startRepository(wrapper, 'old/project')
    await wrapper.get('.close-button').trigger('click')
    await wrapper.get('.server-card').trigger('click')
    client.routes.mockReturnValueOnce(verification.promise)
    await wrapper.get('button.secondary-button').trigger('click')

    creation.reject(Object.assign(new Error('Expired'), { status: 403 }))
    await flushPromises()
    verification.resolve({ routes: [] })
    await flushPromises()

    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
    expect(wrapper.text()).not.toContain('Connection verified')
    expect(wrapper.text()).not.toContain('Checking')
    expect(wrapper.get('.toast').text()).toContain('no longer valid')
    wrapper.unmount()
  })

  it('does not duplicate a repository the server reports as already existing', async () => {
    client.createRepository
      .mockResolvedValueOnce({ status: 'ok', created: true })
      .mockResolvedValueOnce({ status: 'ok', created: false })
    const wrapper = mountApp()
    await connect(wrapper)

    for (let attempt = 0; attempt < 2; attempt += 1) {
      await wrapper.get('.primary-button.compact').trigger('click')
      await wrapper.get('input[placeholder="team/project"]').setValue('platform/console')
      await wrapper.get('form.modal').trigger('submit')
      await flushPromises()
    }

    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')
    expect(wrapper.findAll('.repository-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('Repository already exists')
  })

  it('copies an authenticated HTTP clone command without exposing the Admin token', async () => {
    const writeText = vi.fn()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/console')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    await wrapper.get('[aria-label="Copy token-free HTTP clone command"]').trigger('click')

    expect(writeText).toHaveBeenCalledWith(
      'git --config-env=http.extraHeader=ORION_AUTH_HEADER'
        + ' clone "http://localhost:8000/r/platform/console"',
    )
    expect(writeText.mock.calls[0][0]).not.toContain('token')
    expect(wrapper.text()).toContain('POSIX shells and PowerShell')
    expect(wrapper.text()).toContain('cmd.exe is not')
  })

  it('ignores an invalid advertised clone URL without breaking the repository list', async () => {
    client.transports.mockResolvedValue({
      http: { enabled: true, url: 'not a url' },
      https: { enabled: false, url: null },
      ssh: { enabled: false, url: null },
      nativeGit: { enabled: false, url: null },
    })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/console')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    expect(wrapper.text()).toContain('platform/console')
    expect(wrapper.findAll('.clone-url')).toHaveLength(0)
  })

  it('discards an unsaved settings draft when the dialog closes', async () => {
    const wrapper = mountApp()
    await connect(wrapper)

    await wrapper.find('.server-card').trigger('click')
    const fields = wrapper.findAll('.modal input')
    await fields[0].setValue('unsaved-token')
    await fields[1].setValue('unsaved-user')
    await wrapper.get('.close-button').trigger('click')
    await wrapper.find('.server-card').trigger('click')

    const reopenedFields = wrapper.findAll('.modal input')
    expect(reopenedFields[0].element.value).toBe('token')
    expect(reopenedFields[1].element.value).toBe('alice')
    expect(sessionStorage.getItem('orion.ui.token')).toBe('token')
  })

  it('keeps active server data when testing an invalid unsaved draft', async () => {
    const wrapper = mountApp()
    await connect(wrapper)

    client.routes.mockRejectedValueOnce(new Error('Invalid token'))
    await wrapper.find('.server-card').trigger('click')
    await wrapper.get('input[placeholder="Bearer token"]').setValue('invalid-token')
    await wrapper.get('button.secondary-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Connection failed')
    await wrapper.get('.close-button').trigger('click')
    expect(wrapper.text()).toContain('Registered routes')
    expect(wrapper.text()).toContain('RUNNING')
  })

  it('disconnects and removes an expired token after an authenticated request', async () => {
    const expired = Object.assign(new Error('Expired token'), { status: 403 })
    client.createRepository.mockRejectedValueOnce(expired)
    const wrapper = mountApp()
    await connect(wrapper)

    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/console')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()

    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
    expect(wrapper.text()).toContain('Not connected')
    expect(wrapper.text()).toContain('no longer valid')
  })

  it('reports clipboard rejection instead of claiming success', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('Clipboard denied')) },
    })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/console')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    await wrapper.get('[aria-label="Copy token-free HTTP clone command"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.toast').classes()).toContain('error')
    expect(wrapper.text()).toContain('Clipboard denied')
  })

  it('reports when the Clipboard API is unavailable', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/console')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    await wrapper.get('[aria-label="Copy token-free HTTP clone command"]').trigger('click')

    expect(wrapper.get('.toast').classes()).toContain('error')
    expect(wrapper.text()).toContain('Clipboard is not available')
  })
})
