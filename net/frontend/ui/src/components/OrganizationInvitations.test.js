import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ invitations: vi.fn(), invite: vi.fn() }))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: () => api }))
import OrganizationInvitations from './OrganizationInvitations.vue'

beforeEach(() => {
  vi.resetAllMocks()
  api.invitations.mockResolvedValue({ organizations: [
    { id: 'default', name: 'Default', oidcConfigured: true },
    { id: 'acme', name: 'Acme', oidcConfigured: true },
    { id: 'pending', name: 'Pending', oidcConfigured: false },
  ] })
})

it('lets an admin invite into any configured organization and copy its link', async () => {
  const copy = vi.fn().mockResolvedValue()
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: copy } })
  api.invite.mockResolvedValue({ url: 'https://orion.test/#invite=secret&organization=acme', expiresAt: 1800000000 })
  const wrapper = mount(OrganizationInvitations, { props: { token: 'admin' } })
  await flushPromises()
  expect(wrapper.findAll('option')).toHaveLength(3)
  expect(wrapper.findAll('option')[2].attributes('disabled')).toBeDefined()
  await wrapper.get('select').setValue('acme')
  await wrapper.get('input[type=email]').setValue('alice@example.test')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.invite).toHaveBeenCalledWith({ organization: 'acme', email: 'alice@example.test' })
  expect(wrapper.get('input[readonly]').element.value).toContain('organization=acme')
  await wrapper.get('.secondary-button').trigger('click')
  await flushPromises()
  expect(copy).toHaveBeenCalledWith('https://orion.test/#invite=secret&organization=acme')
  expect(wrapper.text()).toContain('Copied')
})

it('reports rejected invitations without exposing an old usable link', async () => {
  api.invite.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }))
  const wrapper = mount(OrganizationInvitations, { props: { token: 'expired' } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('Forbidden')
  expect(wrapper.find('input[readonly]').exists()).toBe(false)
  expect(wrapper.emitted('authorization-error')).toHaveLength(1)
})
