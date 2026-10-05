import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ systemUsers: vi.fn(), saveSystemOidcBindings: vi.fn() }))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: () => api }))
import SystemOidcBindings from './SystemOidcBindings.vue'

beforeEach(() => {
  vi.resetAllMocks()
  api.systemUsers.mockResolvedValue({ revision: 'v1', users: [
    { id: 'operator', bindings: [{ issuer: 'https://sso.example.test', subject: 'external-alice' }] },
    { id: 'other', bindings: [] },
  ] })
  api.saveSystemOidcBindings.mockResolvedValue({ saved: true })
})

it('edits bindings only for a selected existing user with the loaded revision', async () => {
  const wrapper = mount(SystemOidcBindings, { props: { token: 'admin' } })
  await flushPromises()
  expect(wrapper.get('[name=user]').element.value).toBe('operator')
  await wrapper.get('[name=subject]').setValue('replacement')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.saveSystemOidcBindings).toHaveBeenCalledWith({ id: 'operator', revision: 'v1',
    bindings: [{ issuer: 'https://sso.example.test', subject: 'replacement' }] })
  wrapper.unmount()
})

it('discards unsaved bindings when switching users and never offers user creation', async () => {
  const wrapper = mount(SystemOidcBindings, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[name=subject]').setValue('unsaved')
  await wrapper.get('[name=user]').setValue('other')
  expect(wrapper.find('[name=subject]').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('unsaved')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.saveSystemOidcBindings).toHaveBeenCalledWith({ id: 'other', revision: 'v1', bindings: [] })
  wrapper.unmount()
})

it('keeps a rejected draft for explicit reload and reports lost authorization', async () => {
  api.saveSystemOidcBindings.mockRejectedValue(Object.assign(new Error('Reload users'), { status: 409 }))
  const wrapper = mount(SystemOidcBindings, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toBe('Reload users')
  api.systemUsers.mockRejectedValue(Object.assign(new Error('Access denied'), { status: 403 }))
  await wrapper.get('[name=reload]').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('authorization-error')).toHaveLength(1)
  wrapper.unmount()
})

it('adds and removes a binding before saving the complete OIDC list', async () => {
  const wrapper = mount(SystemOidcBindings, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('fieldset button').trigger('click')
  await wrapper.findAll('button').find(item => item.text() === 'Add binding').trigger('click')
  await wrapper.get('[name=issuer]').setValue('https://other.example.test')
  await wrapper.get('[name=subject]').setValue('other-subject')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.saveSystemOidcBindings).toHaveBeenCalledWith({ id: 'operator', revision: 'v1',
    bindings: [{ issuer: 'https://other.example.test', subject: 'other-subject' }] })
  wrapper.unmount()
})
