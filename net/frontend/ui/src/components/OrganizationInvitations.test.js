import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ invitations: vi.fn(), invite: vi.fn() }))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: () => api }))
import OrganizationInvitations from './OrganizationInvitations.vue'

beforeEach(() => {
  vi.resetAllMocks()
  api.invitations.mockResolvedValue({ systemProviders: ['corporate'], repositories: ['project', 'docs'], organizations: [
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
  expect(wrapper.findAll('[name=organization] option')).toHaveLength(3)
  expect(wrapper.findAll('[name=organization] option')[2].attributes('disabled')).toBeDefined()
  await wrapper.get('[name=organization]').setValue('acme')
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

it('creates a system invitation with explicit repository access and no administrative option', async () => {
  api.invite.mockResolvedValue({ url: 'https://orion.test/#invite=secret&scope=system', expiresAt: 1800000000 })
  const wrapper = mount(OrganizationInvitations, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[name=scope]').setValue('system')
  await flushPromises()
  await wrapper.get('input[type=email]').setValue('alice@example.test')
  await wrapper.get('[name="access-project"]').setValue('readWrite')
  await wrapper.get('[name="access-docs"]').setValue('read')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.invite).toHaveBeenCalledWith({ scope: 'system', email: 'alice@example.test', provider: 'corporate',
    repositories: [{ repository: 'project', readWrite: true }, { repository: 'docs', readWrite: false }] })
  expect(wrapper.find('[name=organization]').exists()).toBe(false)
  expect(wrapper.text()).toContain('READ+WRITE')
  expect(wrapper.get('input[readonly]').element.value).toContain('scope=system')
  wrapper.unmount()
})

it('defaults system invitations to no repository access and clears the link on scope changes', async () => {
  api.invite.mockResolvedValue({ url: 'https://orion.test/#invite=secret&scope=system', expiresAt: 1800000000 })
  const wrapper = mount(OrganizationInvitations, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[name=scope]').setValue('system')
  await flushPromises()
  await wrapper.get('input[type=email]').setValue('alice@example.test')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.invite).toHaveBeenCalledWith(expect.objectContaining({ repositories: [] }))
  await wrapper.get('[name=scope]').setValue('organization')
  expect(wrapper.find('input[readonly]').exists()).toBe(false)
  wrapper.unmount()
})

it('never grants access implicitly for repository names matching object properties', async () => {
  api.invitations.mockResolvedValue({ organizations: [], systemProviders: ['corporate'],
    repositories: ['constructor'] })
  api.invite.mockResolvedValue({ url: 'https://orion.test/#invite=secret&scope=system', expiresAt: 1800000000 })
  const wrapper = mount(OrganizationInvitations, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[name=scope]').setValue('system')
  await wrapper.get('input[type=email]').setValue('alice@example.test')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.invite).toHaveBeenCalledWith(expect.objectContaining({ repositories: [] }))
  await wrapper.get('[name="access-constructor"]').setValue('read')
  expect(wrapper.find('input[readonly]').exists()).toBe(false)
  wrapper.unmount()
})

it('prevents creating a system invitation when no system provider is configured', async () => {
  api.invitations.mockResolvedValue({ organizations: [], systemProviders: [], repositories: [] })
  const wrapper = mount(OrganizationInvitations, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[name=scope]').setValue('system')
  expect(wrapper.get('.primary-button').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('Configure a system OIDC provider first.')
  wrapper.unmount()
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
