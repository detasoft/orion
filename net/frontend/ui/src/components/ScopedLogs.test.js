import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

const client = { scopedLogFiles: vi.fn(), scopedLog: vi.fn() }
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: vi.fn(() => client) }))
import ScopedLogs from './ScopedLogs.vue'

beforeEach(() => {
  vi.clearAllMocks()
  client.scopedLogFiles.mockResolvedValue([{ id: 'acme-certificate', file: 'current.log', size: 123 }])
  client.scopedLog.mockResolvedValue({ text: 'Starting issuance\nIllegalStateException: failed\n  at issuer',
    nextOffset: 123, more: false, version: 'one' })
})

it('opens a task file and appends pages without parsing multiline events', async () => {
  client.scopedLog.mockResolvedValueOnce({ text: 'First\n  at ', nextOffset: 12, more: true, version: 'one' })
  const wrapper = mount(ScopedLogs, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[aria-label="Log file"]').setValue('0')
  await flushPromises()
  expect(wrapper.get('pre').text()).toBe('First\n  at')
  client.scopedLog.mockResolvedValueOnce({ text: 'issuer\n', nextOffset: 19, more: false, version: 'one' })
  await wrapper.get('[aria-label="Read more log text"]').trigger('click')
  await flushPromises()
  expect(client.scopedLog).toHaveBeenLastCalledWith('tasks', 'acme-certificate', 'current.log',
    12, 'one', expect.any(AbortSignal))
  expect(wrapper.get('pre').element.textContent).toBe('First\n  at issuer\n')
  wrapper.unmount()
})

it('switches to user files and discards an outstanding read from another scope', async () => {
  let resolveRead
  client.scopedLog.mockImplementationOnce(() => new Promise(resolve => { resolveRead = resolve }))
  const wrapper = mount(ScopedLogs, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[aria-label="Log file"]').setValue('0')
  client.scopedLogFiles.mockResolvedValueOnce([{ id: 'alice', file: 'current.log', size: 90 }])
  await wrapper.get('[aria-label="Log scope"]').setValue('users')
  await flushPromises()
  resolveRead({ text: 'stale task data', nextOffset: 15, more: false, version: 'old' })
  await flushPromises()
  expect(wrapper.text()).not.toContain('stale task data')
  await wrapper.get('[aria-label="Log file"]').setValue('0')
  await flushPromises()
  expect(client.scopedLog).toHaveBeenLastCalledWith('users', 'alice', 'current.log',
    0, null, expect.any(AbortSignal))
  expect(wrapper.get('pre').text()).toContain('IllegalStateException: failed\n  at issuer')
  wrapper.unmount()
})

it('reports rotation and clears private text when authorization expires', async () => {
  const wrapper = mount(ScopedLogs, { props: { token: 'admin' } })
  await flushPromises()
  await wrapper.get('[aria-label="Log file"]').setValue('0')
  await flushPromises()
  client.scopedLog.mockRejectedValueOnce(Object.assign(new Error('Log file rotated. Refresh.'), { status: 409 }))
  await wrapper.get('[aria-label="Read more log text"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Log file rotated. Refresh.')
  client.scopedLog.mockRejectedValueOnce(Object.assign(new Error('Denied'), { status: 403 }))
  await wrapper.get('[aria-label="Reload log file"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('pre').exists()).toBe(false)
  expect(wrapper.emitted('authorization-error')).toHaveLength(1)
  wrapper.unmount()
})

it('searches Unicode text using original offsets and wraps around literal matches', async () => {
  client.scopedLog.mockResolvedValueOnce({ text: 'İx X [x] 🙂 🙂', nextOffset: 19, more: false, version: 'one' })
  const wrapper = mount(ScopedLogs, { props: { token: 'admin' }, attachTo: document.body })
  await flushPromises()
  await wrapper.get('[aria-label="Log file"]').setValue('0')
  await flushPromises()
  await wrapper.get('[aria-label="Find in loaded log text"]').setValue('x')
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().toString()).toBe('x')
  expect(window.getSelection().anchorOffset).toBe(1)
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().toString()).toBe('X')
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().anchorOffset).toBe(6)
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().anchorOffset).toBe(1)
  await wrapper.get('[aria-label="Find in loaded log text"]').setValue('[x]')
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().toString()).toBe('[x]')
  await wrapper.get('[aria-label="Find in loaded log text"]').setValue('🙂')
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().anchorOffset).toBe(9)
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().anchorOffset).toBe(12)
  await wrapper.get('form').trigger('submit')
  expect(window.getSelection().anchorOffset).toBe(9)
  wrapper.unmount()
})
