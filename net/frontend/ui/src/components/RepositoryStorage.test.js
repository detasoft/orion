import { mount, flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import RepositoryStorage from './RepositoryStorage.vue'

const connection = { name: 'archive', region: 'us-east-1', endpoint: '', accessKeyId: 'id',
  pathStyleAccess: true, credentialsConfigured: true, canUse: true, canChange: true }
function setup(client = {}) {
  const api = { storageConnections: vi.fn().mockResolvedValue({ revision: 'v1', connections: [connection] }),
    saveStorageConnection: vi.fn().mockResolvedValue({ revision: 'v2', connections: [connection] }), ...client }
  return { api, wrapper: mount(RepositoryStorage, { props: { client: api, organization: 'acme', admin: false } }) }
}
it('selects authorized S3 storage', async () => {
  const { wrapper, api } = setup()
  await wrapper.get('[name=storage]').setValue('s3')
  await flushPromises()
  expect(api.storageConnections).toHaveBeenCalledWith('acme')
  await wrapper.get('[name=connection]').setValue('archive')
  await wrapper.get('[name=location]').setValue('s3://bucket/prefix')
  expect(wrapper.emitted('change').at(-1)[0]).toEqual({ connectionScope: 'organization', connection: 'archive',
    location: 's3://bucket/prefix' })
})
it('clears write-only credentials after save and editor closure', async () => {
  const { wrapper, api } = setup()
  await wrapper.get('[name=storage]').setValue('s3')
  await flushPromises()
  await wrapper.get('[data-action=add-connection]').trigger('click')
  await wrapper.get('[name=connection-name]').setValue('archive')
  await wrapper.get('[name=access-key-id]').setValue('id')
  await wrapper.get('[name=secret-key]').setValue('private-secret')
  await wrapper.get('[data-action=save-connection]').trigger('click')
  await flushPromises()
  expect(api.saveStorageConnection).toHaveBeenCalledTimes(1)
  expect(wrapper.find('[name=secret-key]').exists()).toBe(false)
  await wrapper.get('[data-action=edit-connection]').trigger('click')
  expect(wrapper.get('[name=secret-key]').element.value).toBe('')
  await wrapper.get('[name=secret-key]').setValue('discard-me')
  await wrapper.get('[data-action=cancel-connection]').trigger('click')
  await wrapper.get('[data-action=edit-connection]').trigger('click')
  expect(wrapper.get('[name=secret-key]').element.value).toBe('')
})
it('shows bounded loading, retries failures and ignores responses from previous servers', async () => {
  let resolveOld
  const old = new Promise((resolve) => { resolveOld = resolve })
  const { wrapper } = setup({ storageConnections: vi.fn().mockReturnValue(old) })
  await wrapper.get('[name=storage]').setValue('s3')
  expect(wrapper.get('[role=status]').text()).toContain('Loading')
  const next = { storageConnections: vi.fn().mockRejectedValue(new Error('Connection unavailable')) }
  await wrapper.setProps({ client: next })
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('Connection unavailable')
  resolveOld({ revision: 'old', connections: [connection] })
  await flushPromises()
  expect(wrapper.find('[name=connection]').text()).not.toContain('archive')
  next.storageConnections.mockResolvedValue({ revision: 'new', connections: [connection] })
  await wrapper.get('[data-action=reload-connections]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[name=connection]').text()).toContain('archive')
})

it('allows a nonadmin to replace default-chain credentials with explicit credentials', async () => {
  const { wrapper } = setup({ storageConnections: vi.fn().mockResolvedValue({ revision: 'v1',
    connections: [{ ...connection, credentialsConfigured: false, accessKeyId: '', canUse: false }] }) })
  await wrapper.get('[name=storage]').setValue('s3')
  await flushPromises()
  await wrapper.get('[name=connection]').setValue('archive')
  await wrapper.get('[data-action=edit-connection]').trigger('click')
  expect(wrapper.get('[name=secret-key]').exists()).toBe(true)
  expect(wrapper.get('[name=access-key-id]').element.value).toBe('')
  expect(wrapper.text()).not.toContain('Use server AWS credentials')
})

it('shows a failed save, clears credentials and permits explicit reload', async () => {
  const { wrapper } = setup({ saveStorageConnection: vi.fn().mockRejectedValue(new Error('Revision changed')) })
  await wrapper.get('[name=storage]').setValue('s3')
  await flushPromises()
  await wrapper.get('[name=connection]').setValue('archive')
  await wrapper.get('[data-action=edit-connection]').trigger('click')
  await wrapper.get('[name=secret-key]').setValue('private-secret')
  await wrapper.get('[data-action=save-connection]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toBe('Revision changed')
  expect(wrapper.get('[name=secret-key]').element.value).toBe('')
  expect(wrapper.get('[data-action=reload-connections]').attributes('disabled')).toBeUndefined()
})
