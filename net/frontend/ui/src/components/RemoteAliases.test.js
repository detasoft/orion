import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RemoteAliases from './RemoteAliases.vue'

const { remoteAliases, mutateRemoteAlias } = vi.hoisted(() => ({
  remoteAliases: vi.fn(), mutateRemoteAlias: vi.fn(),
}))
vi.mock('../lib/orion-api.js', () => ({
  createOrionClient: () => ({ remoteAliases, mutateRemoteAlias }),
}))

beforeEach(() => {
  remoteAliases.mockReset()
  mutateRemoteAlias.mockReset()
})

const alias = {
  scope: 'system', alias: 'configuration', upstream: 'https://git.example/config.git',
  transport: 'https', ref: 'refs/heads/main', endpoint: null,
  status: 'success', observedAt: '2026-09-15T07:00:00Z',
}

describe('Remote aliases', () => {
  async function ready() {
    remoteAliases.mockResolvedValue({ aliases: [alias], revision: 'read-revision' })
    const wrapper = mount(RemoteAliases, { props: { token: 'admin-token' } })
    await flushPromises()
    return wrapper
  }

  function button(wrapper, label) {
    return wrapper.findAll('button').find((item) => item.text() === label)
  }

  it('creates an alias and distinguishes a saved credential from failed authentication', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', revision: 'saved-revision',
      alias: { ...alias, alias: 'backup', status: 'authentication-failed' } })
    expect(button(wrapper, 'Add alias')).toBeDefined()
    await button(wrapper, 'Add alias').trigger('click')
    await wrapper.get('[name="alias"]').setValue('backup')
    await wrapper.get('[name="upstream"]').setValue('https://git.example/config.git')
    await wrapper.get('[name="ref"]').setValue('main')
    await wrapper.get('[name="credential"]').setValue('private-credential')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias).toHaveBeenCalledWith({ action: 'create', scope: 'system',
      revision: 'read-revision', alias: 'backup', upstream: 'https://git.example/config.git',
      ref: 'main', credentialKind: 'TOKEN', credential: 'private-credential' })
    expect(wrapper.text()).toContain('Saved. Authentication failed')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('private-credential')
    await button(wrapper, 'Add alias').trigger('click')
    expect(wrapper.get('[name="credential"]').element.value).toBe('')
    wrapper.unmount()
  })

  it('edits metadata without resending the sanitized upstream or replacing credentials', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', revision: 'next', alias: { ...alias, ref: 'dev' } })
    expect(button(wrapper, 'Edit')).toBeDefined()
    await button(wrapper, 'Edit').trigger('click')
    expect(wrapper.find('[name="credential"]').exists()).toBe(false)
    await wrapper.get('[name="ref"]').setValue('dev')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias).toHaveBeenCalledWith({ action: 'update', scope: 'system',
      revision: 'read-revision', alias: 'configuration', ref: 'dev' })
    wrapper.unmount()
  })

  it('clears a replacement credential after rejection and never displays server details', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockRejectedValue(Object.assign(new Error('private-credential'), { status: 400 }))
    expect(button(wrapper, 'Replace credential')).toBeDefined()
    await button(wrapper, 'Replace credential').trigger('click')
    await wrapper.get('[name="credential"]').setValue('private-credential')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias).toHaveBeenCalledWith({ action: 'replace-credential', scope: 'system',
      revision: 'read-revision', alias: 'configuration', ref: alias.ref, credential: 'private-credential' })
    expect(wrapper.get('[name="credential"]').element.value).toBe('')
    expect(wrapper.text()).toContain('Check the alias settings')
    expect(wrapper.html()).not.toContain('private-credential')
    wrapper.unmount()
  })

  it('reloads a conflicting revision and requires reopening the edit form', async () => {
    const wrapper = await ready()
    remoteAliases.mockResolvedValue({ aliases: [{ ...alias, ref: 'external' }], revision: 'external-revision' })
    mutateRemoteAlias.mockRejectedValue(Object.assign(new Error('private error'), { status: 409 }))
    expect(button(wrapper, 'Edit')).toBeDefined()
    await button(wrapper, 'Edit').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Configuration changed')
    expect(wrapper.find('form').exists()).toBe(false)
    await button(wrapper, 'Edit').trigger('click')
    expect(wrapper.get('[name="ref"]').element.value).toBe('external')
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', alias, revision: 'saved-revision' })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias.mock.lastCall[0].revision).toBe('external-revision')
    wrapper.unmount()
  })

  it('retries the selected alias with the current revision and displays the fresh result', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'retried', revision: 'read-revision',
      alias: { ...alias, status: 'unavailable' } })
    expect(button(wrapper, 'Retry')).toBeDefined()
    await button(wrapper, 'Retry').trigger('click')
    await flushPromises()
    expect(mutateRemoteAlias).toHaveBeenCalledWith({ action: 'retry', scope: 'system',
      revision: 'read-revision', alias: 'configuration' })
    expect(wrapper.text()).toContain('Retry finished. Unavailable')
    wrapper.unmount()
  })

  it('discards a canceled credential and clears the form when the token changes', async () => {
    const wrapper = await ready()
    await button(wrapper, 'Replace credential').trigger('click')
    await wrapper.get('[name="credential"]').setValue('canceled-private-value')
    await button(wrapper, 'Cancel').trigger('click')
    await button(wrapper, 'Replace credential').trigger('click')
    expect(wrapper.get('[name="credential"]').element.value).toBe('')
    await wrapper.get('[name="credential"]').setValue('old-token-private-value')
    await wrapper.setProps({ token: 'new-token' })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(mutateRemoteAlias).not.toHaveBeenCalled()
    expect(wrapper.html()).not.toContain('private-value')
    wrapper.unmount()
  })

  it('blocks duplicate submissions and ignores a mutation response after a token change', async () => {
    const wrapper = await ready()
    let finish
    mutateRemoteAlias.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    await button(wrapper, 'Replace credential').trigger('click')
    await wrapper.get('[name="credential"]').setValue('private-value')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(mutateRemoteAlias).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[name="credential"]').element.value).toBe('')
    expect(wrapper.get('fieldset').element.disabled).toBe(true)
    remoteAliases.mockResolvedValue({ aliases: [], revision: 'new-session' })
    await wrapper.setProps({ token: 'new-token' })
    await flushPromises()
    finish({ status: 'saved', alias, revision: 'old-response' })
    await flushPromises()
    expect(wrapper.text()).toContain('No remote aliases configured')
    expect(wrapper.text()).not.toContain('Saved.')
    expect(button(wrapper, 'Add alias').element.disabled).toBe(false)
    wrapper.unmount()
  })

  it('clears data and the replacement form when mutation authorization is denied', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockRejectedValue(Object.assign(new Error('private server detail'), { status: 403 }))
    await button(wrapper, 'Replace credential').trigger('click')
    await wrapper.get('[name="credential"]').setValue('private-value')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('authorization-error')).toHaveLength(1)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.find('.proxy-row').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('private-value')
    expect(wrapper.text()).not.toContain('private server detail')
    expect(button(wrapper, 'Add alias').element.disabled).toBe(true)
    wrapper.unmount()
  })

  it.each([
    ['PASSWORD', 'http://git.example/repo.git', 'http-user', 'password'],
    ['PRIVATE_KEY', 'ssh://git@git.example/repo.git', '', 'private-key\nsecond-line'],
    ['PASSWORD', 'ssh://git@git.example/repo.git', '', 'password'],
    ['NONE', 'file:///srv/git/repo.git', '', ''],
  ])('creates %s bindings using only the selected authentication fields', async (kind, upstream, username, secret) => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', alias, revision: 'next' })
    await button(wrapper, 'Add alias').trigger('click')
    await wrapper.get('[name="alias"]').setValue('backup')
    await wrapper.get('[name="upstream"]').setValue(upstream)
    await wrapper.get('[name="credentialKind"]').setValue(kind)
    if (username) await wrapper.get('[name="username"]').setValue(username)
    if (secret) await wrapper.get('[name="credential"]').setValue(secret)
    else expect(wrapper.find('[name="credential"]').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const command = mutateRemoteAlias.mock.lastCall[0]
    expect(command.credentialKind).toBe(kind)
    expect(command.credential).toBe(secret || undefined)
    expect(command.username).toBe(username || undefined)
    expect(command.scope).toBe('system')
    wrapper.unmount()
  })

  it('omits the HTTP username after switching to bearer authentication', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', alias, revision: 'next' })
    await button(wrapper, 'Add alias').trigger('click')
    await wrapper.get('[name="alias"]').setValue('backup')
    await wrapper.get('[name="upstream"]').setValue('https://git.example/repo.git')
    await wrapper.get('[name="credentialKind"]').setValue('PASSWORD')
    await wrapper.get('[name="username"]').setValue('old-http-user')
    await wrapper.get('[name="credentialKind"]').setValue('TOKEN')
    await wrapper.get('[name="credential"]').setValue('bearer-token')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias.mock.lastCall[0]).not.toHaveProperty('username')
    wrapper.unmount()
  })

  it('omits the HTTP username after switching a password binding to SSH', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', alias, revision: 'next' })
    await button(wrapper, 'Add alias').trigger('click')
    await wrapper.get('[name="alias"]').setValue('backup')
    await wrapper.get('[name="upstream"]').setValue('https://git.example/repo.git')
    await wrapper.get('[name="credentialKind"]').setValue('PASSWORD')
    await wrapper.get('[name="username"]').setValue('old-http-user')
    await wrapper.get('[name="upstream"]').setValue('ssh://git@git.example/repo.git')
    expect(wrapper.find('[name="username"]').exists()).toBe(false)
    await wrapper.get('[name="credential"]').setValue('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias.mock.lastCall[0]).toMatchObject({ credentialKind: 'PASSWORD', credential: 'password' })
    expect(mutateRemoteAlias.mock.lastCall[0]).not.toHaveProperty('username')
    wrapper.unmount()
  })

  it('uses the existing upstream when replacing HTTP password credentials', async () => {
    const wrapper = await ready()
    mutateRemoteAlias.mockResolvedValue({ status: 'saved', alias, revision: 'next' })
    await button(wrapper, 'Replace credential').trigger('click')
    await wrapper.get('[name="credentialKind"]').setValue('PASSWORD')
    await wrapper.get('[name="username"]').setValue('replacement-user')
    await wrapper.get('[name="credential"]').setValue('replacement-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutateRemoteAlias.mock.lastCall[0]).toMatchObject({
      credentialKind: 'PASSWORD', username: 'replacement-user', credential: 'replacement-password',
    })
    expect(mutateRemoteAlias.mock.lastCall[0]).not.toHaveProperty('upstream')
    wrapper.unmount()
  })

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
