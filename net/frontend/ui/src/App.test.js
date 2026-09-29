import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

enableAutoUnmount(afterEach)

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
  keyMaterial: vi.fn(),
  issueAcmeCertificate: vi.fn(),
  acmeConfiguration: vi.fn(),
  saveAcmeConfiguration: vi.fn(),
  repositories: vi.fn(),
  remoteAliases: vi.fn(),
  routes: vi.fn(),
  serverLogs: vi.fn(),
  sessions: vi.fn(),
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
  await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
  await wrapper.get('input[placeholder="Your Orion username"]').setValue('alice')
  await wrapper.get('form.modal').trigger('submit')
  await wrapper.find('.server-card').trigger('click')
  await wrapper.get('input[placeholder="Bearer token"]').setValue('token')
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
  window.history.replaceState(null, '', '/')
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
  client.keyMaterial.mockResolvedValue({ entries: [] })
  client.issueAcmeCertificate.mockResolvedValue('-----BEGIN CERTIFICATE-----')
  client.acmeConfiguration.mockResolvedValue({ revision: '1', provider: 'letsencrypt', domains: [], presets: [] })
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
  client.serverLogs.mockResolvedValue({ cursor: 'process:1', gap: false, entries: [] })
})

describe('Orion navigation', () => {
  it('restores a selected terminal session only after administrator authentication', async () => {
    window.history.replaceState(null, '', '/#/terminal?session=session-1')
    sessionStorage.setItem('orion.ui.token', 'token')
    const identity = deferred()
    client.me.mockReturnValueOnce(identity.promise)
    const wrapper = mount(App, { global: { stubs: { SessionTerminal: {
      props: ['token', 'sessionId'], template: '<div class="terminal-stub" />',
    } } } })
    expect(wrapper.find('.terminal-stub').exists()).toBe(false)
    identity.resolve({ userId: 'admin', organization: '', admin: true })
    await flushPromises()
    const terminal = wrapper.getComponent('.terminal-stub')
    expect(terminal.props('sessionId')).toBe('session-1')
    terminal.vm.$emit('select-session', 'session-2')
    await flushPromises()
    expect(window.location.hash).toBe('#/terminal?session=session-2')
    const changed = new Promise((resolve) => window.addEventListener('hashchange', resolve, { once: true }))
    window.history.back()
    await changed
    await flushPromises()
    expect(terminal.props('sessionId')).toBe('session-1')
    terminal.vm.$emit('select-session', '')
    await flushPromises()
    expect(window.location.hash).toBe('#/terminal')
  })

  it('does not offer the terminal to an authenticated non-administrator', async () => {
    window.history.replaceState(null, '', '/#/terminal?session=session-1')
    client.me.mockResolvedValue({ userId: 'reader', organization: '', admin: false })
    const wrapper = mountApp()
    await connect(wrapper)
    expect(wrapper.findAll('.primary-nav .nav-item').map((item) => item.text())).not.toContain('Terminal')
    expect(window.location.hash).toBe('#/overview')
    expect(client.sessions).not.toHaveBeenCalled()
  })

  it('exposes section URLs as links and marks the current page', async () => {
    const wrapper = mountApp()
    const repositories = wrapper.get('.primary-nav a[href="#/repositories"]')
    await repositories.trigger('click')
    expect(repositories.attributes('aria-current')).toBe('page')
    expect(wrapper.get('.primary-nav a[href="#/overview"]').attributes('aria-current')).toBeUndefined()
    expect(wrapper.get('.page-heading h1').text()).toBe('Repositories')
  })

  it.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }])(
    'leaves modified navigation clicks to the browser: %j', async (options) => {
      const wrapper = mountApp()
      const link = wrapper.get('.primary-nav a[href="#/repositories"]')
      let prevented
      link.element.addEventListener('click', (event) => {
        prevented = event.defaultPrevented
        event.preventDefault()
      }, { once: true })
      await link.trigger('click', options)
      expect(prevented).toBe(false)
      expect(window.location.hash).toBe('#/overview')
      expect(wrapper.get('.page-heading h1').text()).toBe('Overview')
    },
  )

  it('explains how to open a protected section without a connection', async () => {
    window.history.replaceState(null, '', '/#/key-material')
    const wrapper = mountApp()
    await flushPromises()
    expect(wrapper.get('.empty-state').text()).toContain('Connect as an administrator')
    expect(wrapper.get('.empty-state').text()).toContain('server card')
    expect(client.keyMaterial).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('#/key-material')
  })

  it.each([
    ['overview', 'Overview'], ['repositories', 'Repositories'], ['remote-aliases', 'Remote aliases'],
    ['pending-decisions', 'Pending decisions'], ['people', 'People'], ['activity', 'Activity'],
    ['terminal', 'Terminal'],
  ])('restores %s from its URL on reload', async (route, title) => {
    const wrapper = mountApp()
    await wrapper.findAll('.primary-nav .nav-item').find((item) => item.text() === title).trigger('click')
    expect(window.location.hash).toBe(`#/${route}`)
    wrapper.unmount()

    const reloaded = mountApp()
    await flushPromises()
    expect(reloaded.get('.page-heading h1').text()).toBe(title)
    expect(reloaded.get('.primary-nav .active').text()).toBe(title)
  })

  it('follows browser back and forward without adding duplicate entries', async () => {
    const wrapper = mountApp()
    const select = (title) => wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === title).trigger('click')
    await select('Repositories')
    await wrapper.get('.search-box input').setValue('filter')
    await select('Terminal')
    await select('Terminal')

    for (const [direction, title, route] of [
      ['back', 'Repositories', 'repositories'], ['back', 'Overview', 'overview'],
      ['forward', 'Repositories', 'repositories'], ['forward', 'Terminal', 'terminal'],
    ]) {
      const changed = new Promise((resolve) => window.addEventListener('hashchange', resolve, { once: true }))
      window.history[direction]()
      await changed
      await flushPromises()
      expect(window.location.hash).toBe(`#/${route}`)
      expect(wrapper.get('.page-heading h1').text()).toBe(title)
      if (route === 'repositories') expect(wrapper.get('.search-box input').element.value).toBe('')
    }
  })

  it('normalizes unknown routes while preserving the UI alias and query', () => {
    window.history.replaceState(null, '', '/ui?theme=dark#/missing')
    const wrapper = mountApp()
    expect(wrapper.get('.page-heading h1').text()).toBe('Overview')
    expect(window.location.pathname + window.location.search + window.location.hash)
      .toBe('/ui?theme=dark#/overview')
  })

  it('stops handling URL changes after unmount', () => {
    const wrapper = mountApp()
    wrapper.unmount()
    window.history.replaceState(null, '', '/#/missing')
    window.dispatchEvent(new Event('hashchange'))
    expect(window.location.hash).toBe('#/missing')
  })

  it('keeps the administrator route while restoring authentication', async () => {
    window.history.replaceState(null, '', '/#/key-material')
    sessionStorage.setItem('orion.ui.token', 'token')
    const identity = deferred()
    client.me.mockReturnValueOnce(identity.promise)
    const wrapper = mountApp()
    expect(window.location.hash).toBe('#/key-material')
    expect(client.keyMaterial).not.toHaveBeenCalled()
    identity.resolve({ userId: 'admin', organization: '', admin: true })
    await vi.waitFor(() => expect(wrapper.text()).toContain('Issue ACME certificate'))
    expect(wrapper.get('.primary-nav .active').text()).toBe('Key material')
    expect(window.location.hash).toBe('#/key-material')
  })

  it.each([
    [{ userId: 'alice', organization: 'acme', admin: false }, 'repositories', 'Repositories'],
    [{ userId: 'alice', organization: '', admin: false }, 'overview', 'Overview'],
  ])('redirects unavailable routes after authentication: %j', async (identity, route, title) => {
    window.history.replaceState(null, '', '/#/key-material')
    sessionStorage.setItem('orion.ui.token', 'token')
    client.me.mockResolvedValue(identity)
    const wrapper = mountApp()
    await flushPromises()
    expect(window.location.hash).toBe(`#/${route}`)
    expect(wrapper.get('.page-heading h1').text()).toBe(title)
    expect(client.keyMaterial).not.toHaveBeenCalled()

    window.location.hash = '#/key-material'
    await vi.waitFor(() => expect(window.location.hash).toBe(`#/${route}`))
    expect(client.keyMaterial).not.toHaveBeenCalled()
  })

  it.each(['invite', 'onboarding'])('consumes %s links before initializing the route', async (parameter) => {
    window.history.replaceState(null, '', `/ui#${parameter}=secret&organization=acme`)
    const wrapper = mount(App, { global: { stubs: { OrganizationSignIn: {
      props: ['invitation', 'ticket', 'organization'], template: '<div class="sign-in" />',
    } } } })
    await flushPromises()
    const signIn = wrapper.getComponent('.sign-in')
    expect(signIn.props(parameter === 'invite' ? 'invitation' : 'ticket')).toBe('secret')
    expect(signIn.props('organization')).toBe('acme')
    expect(window.location.hash).toBe('#/overview')
  })
})

describe('Orion connection', () => {
  it('starts console logs only after saving the administrator setting and stops when disabled', async () => {
    vi.useFakeTimers()
    const wrapper = mountApp()
    try {
      await connect(wrapper)
      expect(client.serverLogs).not.toHaveBeenCalled()
      const open = () => wrapper.findAll('.sidebar-bottom .nav-item')
        .find((item) => item.text() === 'Settings').trigger('click')
      await open()
      await wrapper.get('input[aria-label="Server logs in browser console"]').setValue(true)
      await wrapper.get('.close-button').trigger('click')
      expect(client.serverLogs).not.toHaveBeenCalled()
      await open()
      expect(wrapper.get('input[aria-label="Server logs in browser console"]').element.checked).toBe(false)
      await wrapper.get('input[aria-label="Server logs in browser console"]').setValue(true)
      await wrapper.get('form.modal').trigger('submit')
      await flushPromises()
      expect(client.serverLogs).toHaveBeenCalledOnce()
      expect(sessionStorage.getItem('orion.ui.token')).toBe('token')
      const signal = client.serverLogs.mock.calls[0][1]
      await vi.advanceTimersByTimeAsync(1000)
      expect(client.serverLogs.mock.calls[1][0]).toBe('process:1')
      await open()
      await wrapper.get('input[aria-label="Server logs in browser console"]').setValue(false)
      await wrapper.get('form.modal').trigger('submit')
      expect(signal.aborted).toBe(true)
      await vi.advanceTimersByTimeAsync(2000)
      expect(client.serverLogs).toHaveBeenCalledTimes(2)
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('cancels logs on a token change and does not print the previous connection response', async () => {
    const pending = deferred()
    client.serverLogs.mockReturnValueOnce(pending.promise)
    const output = vi.spyOn(console, 'log').mockImplementation(() => {})
    const wrapper = mountApp()
    try {
      await connect(wrapper)
      await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
      await wrapper.get('input[aria-label="Server logs in browser console"]').setValue(true)
      await wrapper.get('form.modal').trigger('submit')
      const signal = client.serverLogs.mock.calls[0][1]
      await wrapper.get('.server-card').trigger('click')
      await wrapper.get('input[placeholder="Bearer token"]').setValue('replacement')
      await wrapper.get('form.modal').trigger('submit')
      await flushPromises()
      expect(signal.aborted).toBe(true)
      pending.resolve({ cursor: 'old:1', gap: false, entries: [{ level: 'INFO', text: 'old server output' }] })
      await flushPromises()
      expect(output).not.toHaveBeenCalled()
      expect(client.serverLogs).toHaveBeenCalledTimes(2)
      const currentSignal = client.serverLogs.mock.calls[1][1]
      wrapper.unmount()
      expect(currentSignal.aborted).toBe(true)
    } finally {
      wrapper.unmount()
      output.mockRestore()
    }
  })

  it('clears rejected credentials when a log request loses authorization', async () => {
    client.serverLogs.mockRejectedValueOnce(Object.assign(new Error('Access denied'), { status: 403 }))
    const wrapper = mountApp()
    try {
      await connect(wrapper)
      await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
      await wrapper.get('input[aria-label="Server logs in browser console"]').setValue(true)
      await wrapper.get('form.modal').trigger('submit')
      await flushPromises()
      expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
      expect(wrapper.get('.server-card').text()).toContain('Not connected')
      expect(wrapper.get('.toast').text()).toContain('Access denied')
    } finally {
      wrapper.unmount()
    }
  })

  it('does not expose server log controls to an organization user', async () => {
    sessionStorage.setItem('orion.ui.token', 'organization-token')
    client.me.mockResolvedValue({ userId: 'alice', organization: 'acme', admin: false })
    const wrapper = mountApp()
    try {
      await flushPromises()
      await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
      expect(wrapper.find('input[aria-label="Server logs in browser console"]').exists()).toBe(false)
      expect(client.serverLogs).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['', '   '])('requires a token before testing the connection with %j', async (token) => {
    const wrapper = mountApp()
    try {
      await wrapper.get('.server-card').trigger('click')
      await wrapper.get('input[placeholder="Bearer token"]').setValue(token)
      await wrapper.get('form.modal .secondary-button').trigger('click')
      await flushPromises()

      expect(wrapper.get('.toast').text()).toBe('Enter an Admin API token before testing the connection.')
      expect(wrapper.get('.connection-result').text()).toBe('Connection failed')
      expect(client.routes).not.toHaveBeenCalled()
      expect(client.lifecycleState).not.toHaveBeenCalled()
      expect(client.transports).not.toHaveBeenCalled()
      expect(wrapper.get('.server-card').text()).toContain('Not connected')
    } finally {
      wrapper.unmount()
    }
  })

  it('verifies a supplied token without saving the draft connection', async () => {
    const wrapper = mountApp()
    try {
      await wrapper.get('.server-card').trigger('click')
      await wrapper.get('input[placeholder="Bearer token"]').setValue('  candidate-token  ')
      await wrapper.get('form.modal .secondary-button').trigger('click')
      await flushPromises()

      expect(createOrionClient).toHaveBeenLastCalledWith({ token: 'candidate-token' })
      expect(client.routes).toHaveBeenCalledOnce()
      expect(client.lifecycleState).toHaveBeenCalledOnce()
      expect(client.transports).toHaveBeenCalledOnce()
      expect(wrapper.get('.connection-result').text()).toBe('Connection verified')
      expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
      expect(wrapper.get('.server-card').text()).toContain('Not connected')
    } finally {
      wrapper.unmount()
    }
  })

  it('opens a token-only connection form from either connection entry point', async () => {
    const wrapper = mountApp()
    for (const entry of [wrapper.get('.connection-empty .primary-button'), wrapper.get('.server-card')]) {
      await entry.trigger('click')
      expect(wrapper.get('[role="dialog"] h2').text()).toBe('Connect to Orion')
      expect(wrapper.findAll('.modal input')).toHaveLength(1)
      expect(wrapper.get('input[placeholder="Bearer token"]').attributes('type')).toBe('password')
      expect(wrapper.get('[role="dialog"]').text()).not.toContain('SSH username')
      await wrapper.get('.close-button').trigger('click')
    }
    wrapper.unmount()
  })

  it('saves SSH clone settings without connecting to the server', async () => {
    const wrapper = mountApp()
    await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
    expect(wrapper.get('[role="dialog"] h2').text()).toBe('Settings')
    expect(wrapper.findAll('.modal input')).toHaveLength(1)
    expect(wrapper.find('input[placeholder="Bearer token"]').exists()).toBe(false)
    await wrapper.get('input[placeholder="Your Orion username"]').setValue('  alice  ')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    expect(localStorage.getItem('orion.ui.ssh-username')).toBe('alice')
    expect(sessionStorage.getItem('orion.ui.token')).toBeNull()
    expect(client.me).not.toHaveBeenCalled()
    expect(wrapper.get('.server-card').text()).toContain('Not connected')
    wrapper.unmount()
  })

  it('updates SSH clone URLs without resetting the verified connection', async () => {
    localStorage.setItem('orion.ui.ssh-username', 'alice')
    sessionStorage.setItem('orion.ui.token', 'token')
    client.repositories.mockResolvedValue({ repositories: [{ name: 'team/project' }] })
    const wrapper = mountApp()
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')
    expect(wrapper.text()).toContain('ssh://alice@localhost:8022/team/project.git')
    const disposals = client.dispose.mock.calls.length
    for (const username of ['bob', '']) {
      await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
      await wrapper.get('input[placeholder="Your Orion username"]').setValue(username)
      await wrapper.get('form.modal').trigger('submit')
      await flushPromises()
      expect(wrapper.text()).toContain('team/project')
      expect(wrapper.text()).not.toContain('ssh://alice@')
      if (username) expect(wrapper.text()).toContain('ssh://bob@localhost:8022/team/project.git')
      else expect(wrapper.text()).not.toContain('ssh://')
      expect(wrapper.get('.server-card').text()).toContain('Connected')
      expect(sessionStorage.getItem('orion.ui.token')).toBe('token')
    }
    expect(client.me).toHaveBeenCalledOnce()
    expect(client.repositories).toHaveBeenCalledOnce()
    expect(client.dispose).toHaveBeenCalledTimes(disposals)
    wrapper.unmount()
  })

  it('preserves credentials renewed while SSH settings are open', async () => {
    sessionStorage.setItem('orion.ui.token', 'old-token')
    sessionStorage.setItem('orion.ui.oidc', JSON.stringify({ expiresAt: 1, organization: 'acme', userId: 'alice' }))
    client.me.mockResolvedValue({ userId: 'alice', organization: 'acme', admin: false })
    const wrapper = mountApp()
    await flushPromises()
    const options = createOrionClient.mock.calls.at(-1)[0]
    await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
    await wrapper.get('input[placeholder="Your Orion username"]').setValue('alice')
    options.onToken({ token: 'renewed', expiresAt: 200, organization: 'acme', userId: 'alice' })
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    expect(sessionStorage.getItem('orion.ui.token')).toBe('renewed')
    expect(JSON.parse(sessionStorage.getItem('orion.ui.oidc'))).toEqual({
      expiresAt: 200, organization: 'acme', userId: 'alice',
    })
    expect(client.me).toHaveBeenCalledOnce()
    expect(wrapper.get('.server-card').text()).toContain('Connected')
    wrapper.unmount()
  })

  it('shows the key material viewer only to a connected administrator', async () => {
    const wrapper = mountApp()
    expect(wrapper.text()).not.toContain('Key material')
    await connect(wrapper)
    const navigation = wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Key material')
    expect(navigation).toBeDefined()
    await navigation.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('Issue ACME certificate'))
    expect(client.keyMaterial).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

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
      state: 'PENDING', error: '', selectedAction: -1, retryable: false,
    }] })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.findAll('.primary-nav .nav-item')
      .find((item) => item.text() === 'Pending decisions').trigger('click')
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(client.decisions).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Review host key')
    client.decisions.mockResolvedValueOnce({ decisions: [] })
    await wrapper.findAll('button').find((button) => button.text() === 'Accept key').trigger('click')
    await flushPromises()
    expect(client.resolveDecision).toHaveBeenCalledWith('request-1', 'accept', expect.any(AbortSignal))
    expect(wrapper.text()).toContain('Decision submitted.')
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
    expect(wrapper.find('select[aria-label="Session"]').exists()).toBe(false)
    expect(client.sessions).not.toHaveBeenCalled()
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
    expect(wrapper.text()).toContain('https://git.example/r/platform/my-repo.git')
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

  it.each([
    ['HTTP', 'http', 'project', 'project'],
    ['HTTPS', 'https', 'project', 'project'],
    ['HTTP', 'http', 'platform/my repo', 'platform/my%20repo'],
    ['HTTPS', 'https', 'platform/my repo', 'platform/my%20repo'],
  ])('copies a token-free %s clone command for %s %s', async (label, scheme, name, path) => {
    client.transports.mockResolvedValue({
      [scheme]: { enabled: true, url: `${scheme}://git.example` },
    })
    const writeText = vi.fn()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const wrapper = mountApp()
    await connect(wrapper)
    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue(name)
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    expect(wrapper.get('.clone-url code').text()).toBe(`${scheme}://git.example/r/${path}.git`)
    await wrapper.get(`[aria-label="Copy token-free ${label} clone command"]`).trigger('click')

    expect(writeText).toHaveBeenCalledWith(
      'git --config-env=http.extraHeader=ORION_AUTH_HEADER'
        + ` clone "${scheme}://git.example/r/${path}.git"`,
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

  it('discards an unsaved token draft when the connection dialog closes', async () => {
    const wrapper = mountApp()
    await connect(wrapper)

    await wrapper.find('.server-card').trigger('click')
    await wrapper.get('input[placeholder="Bearer token"]').setValue('unsaved-token')
    await wrapper.get('.close-button').trigger('click')
    await wrapper.find('.server-card').trigger('click')

    expect(wrapper.get('input[placeholder="Bearer token"]').element.value).toBe('token')
    expect(sessionStorage.getItem('orion.ui.token')).toBe('token')
    expect(localStorage.getItem('orion.ui.ssh-username')).toBe('alice')
    wrapper.unmount()
  })

  it('discards an unsaved SSH username when clone settings close', async () => {
    const wrapper = mountApp()
    await connect(wrapper)
    for (const dismiss of ['close', 'escape', 'backdrop']) {
      await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
      await wrapper.get('input[placeholder="Your Orion username"]').setValue('unsaved-user')
      if (dismiss === 'close') await wrapper.get('.close-button').trigger('click')
      else if (dismiss === 'escape') await wrapper.get('form.modal').trigger('keydown', { key: 'Escape' })
      else await wrapper.get('.modal-layer').trigger('mousedown')
      expect(wrapper.find('form.modal').exists()).toBe(false)
      expect(localStorage.getItem('orion.ui.ssh-username')).toBe('alice')
    }
    await wrapper.findAll('.sidebar-bottom .nav-item').find((item) => item.text() === 'Settings').trigger('click')
    expect(wrapper.get('input[placeholder="Your Orion username"]').element.value).toBe('alice')
    expect(sessionStorage.getItem('orion.ui.token')).toBe('token')
    wrapper.unmount()
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
