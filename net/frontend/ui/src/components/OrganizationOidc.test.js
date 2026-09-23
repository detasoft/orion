import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ oidcSettings: vi.fn(), saveOidcProvider: vi.fn() }))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: () => api }))
import OrganizationOidc from './OrganizationOidc.vue'

beforeEach(() => {
  vi.resetAllMocks()
  api.oidcSettings.mockResolvedValue({ revision: 'v1', organizations: [
    { id: 'default', providers: [] },
    { id: 'acme', providers: [{ id: 'google', issuer: 'https://accounts.google.com', clientId: 'client' }] },
  ] })
  api.saveOidcProvider.mockResolvedValue({ saved: true })
})

it('creates a provider in an organization without OIDC and clears the secret', async () => {
  let submitted
  api.saveOidcProvider.mockImplementation(async (input) => { submitted = { ...input }; return { saved: true } })
  const wrapper = mount(OrganizationOidc, { props: { token: 'admin' } })
  await flushPromises()
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue('corporate')
  await inputs[1].setValue('https://sso.example.test/realms/employees')
  await inputs[2].setValue('orion')
  await inputs[3].setValue('private-secret')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(submitted).toEqual({ organization: 'default', revision: 'v1', id: 'corporate',
    issuer: 'https://sso.example.test/realms/employees', clientId: 'orion', clientSecret: 'private-secret' })
  expect(wrapper.get('input[type=password]').element.value).toBe('')
  expect(wrapper.emitted('saved')).toHaveLength(1)
  expect(wrapper.get('[role=status]').text()).toContain('Provider saved')
})

it('preserves an existing secret unless replaced and requires a secret for changed clients', async () => {
  const wrapper = mount(OrganizationOidc, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.findAll('select')[0].setValue('acme')
  await wrapper.findAll('select')[1].setValue('google')
  expect(wrapper.get('input[type=password]').element.value).toBe('')
  expect(wrapper.get('input[type=password]').attributes('required')).toBeUndefined()
  expect(wrapper.findAll('input')[0].attributes('readonly')).toBeDefined()
  await wrapper.findAll('input')[2].setValue('new-client')
  expect(wrapper.get('input[type=password]').attributes('required')).toBeDefined()
  await wrapper.get('input[type=password]').setValue('replacement')
  await wrapper.findAll('select')[0].setValue('default')
  expect(wrapper.get('input[type=password]').element.value).toBe('')
})

it('reports a revision conflict, clears the entered secret and reloads explicitly', async () => {
  api.saveOidcProvider.mockRejectedValue(Object.assign(new Error('Reload providers'), { status: 409 }))
  const wrapper = mount(OrganizationOidc, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('input[type=password]').setValue('private-secret')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toBe('Reload providers')
  expect(wrapper.get('input[type=password]').element.value).toBe('')
  expect(wrapper.emitted('saved')).toBeUndefined()
  await wrapper.get('.secondary-button').trigger('click')
  await flushPromises()
  expect(api.oidcSettings).toHaveBeenCalledTimes(2)
})
