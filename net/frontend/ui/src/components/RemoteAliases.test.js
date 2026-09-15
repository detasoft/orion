import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RemoteAliases from './RemoteAliases.vue'

const { remoteAliases } = vi.hoisted(() => ({ remoteAliases: vi.fn() }))
vi.mock('../lib/orion-api.js', () => ({
  createOrionClient: () => ({ remoteAliases }),
}))

beforeEach(() => {
  remoteAliases.mockReset()
})

const alias = {
  scope: 'system', alias: 'configuration', upstream: 'https://git.example/config.git',
  transport: 'https', ref: 'refs/heads/main', endpoint: null,
  status: 'success', observedAt: '2026-09-15T07:00:00Z',
}

describe('Remote aliases', () => {
  it('shows the safe alias projection separately from clone endpoints', async () => {
    remoteAliases.mockResolvedValue({ aliases: [alias] })
    const wrapper = mount(RemoteAliases, { props: { token: 'admin-token' } })
    await flushPromises()

    expect(wrapper.text()).toContain('configuration')
    expect(wrapper.text()).toContain('system')
    expect(wrapper.text()).toContain('https://git.example/config.git')
    expect(wrapper.text()).toContain('refs/heads/main')
    expect(wrapper.text()).toContain('Success')
    expect(wrapper.text()).toContain('No public Git endpoint')
    expect(wrapper.text()).toContain('Writes complete after the upstream accepts them')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('.clone-url').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('admin-token')
    wrapper.unmount()
  })

  it('distinguishes empty data from a failed list request and can reload', async () => {
    remoteAliases.mockRejectedValueOnce(new Error('private server detail'))
      .mockResolvedValueOnce({ aliases: [] })
    const wrapper = mount(RemoteAliases, { props: { token: 'token' } })
    await flushPromises()
    expect(wrapper.text()).toContain('Could not load remote aliases')
    expect(wrapper.text()).not.toContain('private server detail')
    expect(wrapper.text()).not.toContain('No remote aliases configured')

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('No remote aliases configured')
    expect(wrapper.text()).not.toContain('Could not load')
    expect(remoteAliases).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('clears aliases and reports authorization failure', async () => {
    remoteAliases.mockResolvedValueOnce({ aliases: [alias] })
      .mockRejectedValueOnce(Object.assign(new Error('forbidden'), { status: 403 }))
    const wrapper = mount(RemoteAliases, { props: { token: 'token' } })
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('configuration')
    expect(wrapper.emitted('authorization-error')).toHaveLength(1)
    wrapper.unmount()
  })

  it('ignores a pending response from the previous token', async () => {
    let finishOldRequest
    remoteAliases.mockReturnValueOnce(new Promise((resolve) => { finishOldRequest = resolve }))
      .mockResolvedValueOnce({ aliases: [] })
    const wrapper = mount(RemoteAliases, { props: { token: 'old-token' } })
    await wrapper.setProps({ token: 'new-token' })
    await flushPromises()
    finishOldRequest({ aliases: [alias] })
    await flushPromises()

    expect(wrapper.text()).toContain('No remote aliases configured')
    expect(wrapper.text()).not.toContain('configuration')
    wrapper.unmount()
  })

  it.each([
    ['not-checked', 'Not checked'], ['unavailable', 'Unavailable'],
    ['authentication-failed', 'Authentication failed'], ['conflict', 'Conflict'],
  ])('shows the observed %s status', async (status, label) => {
    remoteAliases.mockResolvedValue({ aliases: [{ ...alias, status, observedAt: null }] })
    const wrapper = mount(RemoteAliases, { props: { token: 'token' } })
    await flushPromises()
    expect(wrapper.text()).toContain(label)
    expect(wrapper.find('time').exists()).toBe(false)
    wrapper.unmount()
  })
})
