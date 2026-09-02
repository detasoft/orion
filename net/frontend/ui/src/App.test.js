import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const client = {
  createRepository: vi.fn(),
  createOrUpdateUser: vi.fn(),
  lifecycleState: vi.fn(),
  routes: vi.fn(),
  transports: vi.fn(),
}

vi.mock('./lib/orion-api.js', () => ({
  createOrionClient: vi.fn(() => client),
  formatRelativeDate: vi.fn(() => 'just now'),
  normalizeRepositoryName: vi.fn((name) => name.trim().replace(/\.git$/, '')),
}))

import App from './App.vue'

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

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.clearAllMocks()
  client.routes.mockResolvedValue({
    routes: [{ urlPattern: '/api/admin/routes', methods: ['GET'], authorization: 'admin' }],
  })
  client.lifecycleState.mockResolvedValue('RUNNING')
  client.transports.mockResolvedValue({
    http: { enabled: true, url: 'http://localhost:8000' },
    https: { enabled: true, url: 'https://localhost:8443' },
    ssh: { enabled: true, url: 'ssh://localhost:8022' },
    nativeGit: { enabled: true, url: 'git://localhost:9419' },
  })
  client.createRepository.mockResolvedValue({ status: 'ok' })
})

describe('Orion connection', () => {
  it('shows verified server data after connecting', async () => {
    const wrapper = mountApp()

    await connect(wrapper)

    expect(client.routes).toHaveBeenCalledOnce()
    expect(client.lifecycleState).toHaveBeenCalledOnce()
    expect(client.transports).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Registered routes')
    expect(wrapper.text()).toContain('RUNNING')
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

  it('creates a repository through the connected API and marks it as session-local', async () => {
    client.transports.mockResolvedValue({
      http: { enabled: true, url: 'https://git.example' },
      https: { enabled: true, url: 'https://git.example' },
      ssh: { enabled: true, url: 'ssh://git.example:2222' },
      nativeGit: { enabled: true, url: 'git://git.example:9418' },
    })
    const wrapper = mountApp()
    await connect(wrapper)

    await wrapper.get('.primary-button.compact').trigger('click')
    await wrapper.get('input[placeholder="team/project"]').setValue('platform/my repo#?')
    await wrapper.get('form.modal').trigger('submit')
    await flushPromises()
    await wrapper.findAll('.primary-nav .nav-item')[1].trigger('click')

    expect(client.createRepository).toHaveBeenCalledWith('platform/my repo#?')
    expect(wrapper.text()).toContain('platform/my repo#?')
    expect(wrapper.text()).toContain('created in this session')
    expect(wrapper.text()).toContain('ssh://alice@git.example:2222/platform/my%20repo%23%3F.git')
    expect(wrapper.text()).toContain('https://git.example/r/platform/my%20repo%23%3F')
    expect(wrapper.text()).toContain('git://git.example:9418/platform/my%20repo%23%3F')
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
