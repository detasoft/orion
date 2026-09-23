import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ providers: vi.fn(), oidcProfile: vi.fn(), completeOidc: vi.fn() }))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: () => api }))
import OrganizationSignIn from './OrganizationSignIn.vue'

it('loads providers for the invitation organization and prevents switching its scope', async () => {
  api.providers.mockResolvedValue({ providers: ['corporate', 'google'] })
  const wrapper = mount(OrganizationSignIn, { props: { organization: 'acme', invitation: 'secret' } })
  await flushPromises()
  expect(api.providers).toHaveBeenCalledWith('acme')
  expect(wrapper.get('input').attributes('readonly')).toBeDefined()
  expect(wrapper.findAll('option').map((item) => item.text())).toEqual(['corporate', 'google'])
})

it('submits profile details with the verified ticket and returns the token', async () => {
  api.oidcProfile.mockResolvedValue({ first: 'Alice', last: '', email: 'alice@example.test', setup: true })
  api.completeOidc.mockResolvedValue({ token: 'session', organization: 'acme' })
  const wrapper = mount(OrganizationSignIn, { props: { ticket: 'verified-ticket' } })
  await flushPromises()
  await wrapper.findAll('input')[1].setValue('Smith')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.completeOidc).toHaveBeenCalledWith({ ticket: 'verified-ticket', first: 'Alice', last: 'Smith' })
  expect(wrapper.emitted('signed-in')[0]).toEqual([{ token: 'session', organization: 'acme' }])
})

it('shows expired tickets without allowing profile submission', async () => {
  api.oidcProfile.mockRejectedValue(new Error('Start again'))
  const wrapper = mount(OrganizationSignIn, { props: { ticket: 'expired' } })
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toBe('Start again')
  expect(wrapper.find('form').exists()).toBe(false)
})
