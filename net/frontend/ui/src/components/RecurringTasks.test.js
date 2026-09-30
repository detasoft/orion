import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import RecurringTasks from './RecurringTasks.vue'

enableAutoUnmount(afterEach)
const client = { acmeConfiguration: vi.fn(), recurringTasks: vi.fn() }
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: vi.fn(() => client) }))
const scheduled = { state: 'scheduled', nextAttempt: '2026-10-01T12:00:00Z',
  lastAttempt: '2026-09-01T12:00:00Z', lastSuccess: '2026-09-01T12:00:00Z',
  message: '', activationError: '' }

beforeEach(() => {
  vi.resetAllMocks()
  client.acmeConfiguration.mockResolvedValue({ renewal: scheduled })
  client.recurringTasks.mockResolvedValue({ gitPackCleanup: { state: 'scheduled',
    lastAttempt: '2026-10-01T11:00:00Z', nextAttempt: '2026-10-01T12:00:00Z',
    deleted: 0, observed: 2, skipped: 0, message: '' } })
})

it('shows the renewal schedule and links to the stable task journal', async () => {
  const wrapper = mount(RecurringTasks, { props: { token: 'admin' } })
  await flushPromises()
  expect(wrapper.text()).toContain('Scheduled')
  expect(wrapper.text()).toContain(scheduled.nextAttempt)
  expect(wrapper.text()).toContain(scheduled.lastAttempt)
  expect(wrapper.text()).toContain('Succeeded')
  expect(wrapper.get('a').attributes('href')).toBe('#/logs?task=acme-certificate')
  expect(wrapper.text()).toContain('Git pack cleanup')
  expect(wrapper.text()).toContain('2 observed')
  expect(wrapper.get('a[href="#/logs?task=git-pack-cleanup"]').exists()).toBe(true)
})

it('refreshes a failed attempt without presenting the previous success as its result', async () => {
  const wrapper = mount(RecurringTasks, { props: { token: 'admin' } })
  await flushPromises()
  client.acmeConfiguration.mockResolvedValueOnce({ renewal: { ...scheduled, state: 'retrying',
    lastAttempt: '2026-09-29T12:00:00Z', message: 'Certificate renewal failed.',
    activationError: 'Could not activate the saved certificate.' } })
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Retrying')
  expect(wrapper.text()).toContain('Certificate renewal failed.')
  expect(wrapper.text()).not.toContain('Succeeded')
  expect(wrapper.get('[role="alert"]').text()).toContain('Could not activate')
})

it('keeps certificate status visible when Git cleanup status is unavailable', async () => {
  client.recurringTasks.mockRejectedValueOnce(new Error('Cleanup status unavailable'))
  const wrapper = mount(RecurringTasks, { props: { token: 'admin' } })
  await flushPromises()
  expect(wrapper.text()).toContain('Certificate renewal')
  expect(wrapper.text()).toContain('Cleanup status unavailable')
  expect(wrapper.find('article[aria-label="Git pack cleanup"]').exists()).toBe(false)
})

it.each(['disabled', 'awaiting_certificate', 'stopped', 'issuing', 'unavailable'])(
  'shows %s without inventing execution dates', async state => {
    client.acmeConfiguration.mockResolvedValueOnce({ renewal: { state, nextAttempt: '',
      lastAttempt: '', lastSuccess: '', message: '', activationError: '' } })
    const wrapper = mount(RecurringTasks, { props: { token: 'admin' } })
    await flushPromises()
    expect(wrapper.find('article[aria-label="ACME certificate renewal"] time').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Succeeded')
    expect(wrapper.get('a').attributes('href')).toBe('#/logs?task=acme-certificate')
  })

it('clears previous status on authorization loss and ignores an obsolete response', async () => {
  let resolveOld
  client.acmeConfiguration.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const wrapper = mount(RecurringTasks, { props: { token: 'old' } })
  client.acmeConfiguration.mockRejectedValueOnce(Object.assign(new Error('Denied'), { status: 403 }))
  await wrapper.setProps({ token: 'new' })
  await flushPromises()
  resolveOld({ renewal: scheduled })
  await flushPromises()
  expect(wrapper.text()).not.toContain(scheduled.nextAttempt)
  expect(wrapper.get('[role="alert"]').text()).toBe('Denied')
  expect(wrapper.emitted('authorization-error')).toHaveLength(1)
})
