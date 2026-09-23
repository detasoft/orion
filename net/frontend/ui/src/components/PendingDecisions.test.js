import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PendingDecisions from './PendingDecisions.vue'

const { createOrionClient, decisions, resolveDecision } = vi.hoisted(() => ({
  createOrionClient: vi.fn(), decisions: vi.fn(), resolveDecision: vi.fn(),
}))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient }))

const request = {
  id: 'decision-1', title: 'Host key changed', description: 'Old fingerprint\nNew fingerprint',
  scope: 'acme/platform/api', createdAt: '2026-09-23T00:00:00Z',
  state: 'PENDING', error: '', selectedAction: -1, retryable: false,
  actions: { replace: 'Replace stored key', reject: 'Reject connection' },
}
let wrapper

beforeEach(() => {
  createOrionClient.mockReset().mockReturnValue({ decisions, resolveDecision })
  decisions.mockReset().mockResolvedValue({ decisions: [request] })
  resolveDecision.mockReset().mockResolvedValue('')
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

function deferred() {
  let resolve
  let reject
  const promise = new Promise((accept, fail) => { resolve = accept; reject = fail })
  return { promise, resolve, reject }
}

function button(label) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === label)
  expect(found, `button: ${label}`).toBeDefined()
  return found
}

async function open(token = 'reviewer-token') {
  wrapper = mount(PendingDecisions, { props: { token } })
  await flushPromises()
}

describe('Pending decisions', () => {
  it('retains a failed action, shows its error and retries only when permitted', async () => {
    decisions.mockResolvedValueOnce({ decisions: [{ ...request, state: 'FAILED', selectedAction: 'replace',
      error: 'Storage unavailable', retryable: true }] })
    await open()
    expect(wrapper.get('[role="alert"]').text()).toContain('Storage unavailable')
    expect(wrapper.findAll('button').map((candidate) => candidate.text())).not.toContain('Replace stored key')
    decisions.mockResolvedValueOnce({ decisions: [] })
    await button('Retry action').trigger('click')
    await flushPromises()
    expect(resolveDecision).toHaveBeenCalledExactlyOnceWith(request.id, 'retry', expect.any(AbortSignal))
    expect(wrapper.find('article').exists()).toBe(false)
  })

  it('allows closing a nonrepeatable failure without executing it again', async () => {
    decisions.mockResolvedValueOnce({ decisions: [{ ...request, state: 'FAILED', selectedAction: 'replace',
      error: '<script>failed</script>', retryable: false }] })
    await open()
    expect(wrapper.findAll('button').map((candidate) => candidate.text())).not.toContain('Retry action')
    expect(wrapper.find('script').exists()).toBe(false)
    decisions.mockResolvedValueOnce({ decisions: [] })
    await button('Close decision').trigger('click')
    await flushPromises()
    expect(resolveDecision).toHaveBeenCalledExactlyOnceWith(request.id, 'close', expect.any(AbortSignal))
    expect(wrapper.text()).toContain('Decision closed.')
  })

  it('polls a running action and then displays its failure', async () => {
    vi.useFakeTimers()
    try {
      decisions.mockResolvedValueOnce({ decisions: [{ ...request, state: 'RUNNING', selectedAction: 'replace' }] })
      await open()
      expect(wrapper.text()).toContain('Action is running')
      expect(wrapper.findAll('button')).toHaveLength(1)
      decisions.mockResolvedValueOnce({ decisions: [{ ...request, state: 'FAILED', selectedAction: 'replace',
        error: 'Save failed', retryable: true }] })
      await vi.advanceTimersByTimeAsync(1000)
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain('Save failed')
      await vi.advanceTimersByTimeAsync(2000)
      expect(decisions).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('loads descriptions, scope, time and server-defined actions and refreshes the list', async () => {
    await open()
    expect(createOrionClient).toHaveBeenCalledWith({ token: 'reviewer-token' })
    expect(decisions).toHaveBeenCalledWith(expect.any(AbortSignal))
    expect(wrapper.text()).toContain(request.title)
    expect(wrapper.text()).toContain(request.description)
    expect(wrapper.text()).toContain(request.scope)
    expect(wrapper.get('time').attributes('datetime')).toBe(request.createdAt)
    expect(button('Replace stored key').element.disabled).toBe(false)
    expect(button('Reject connection').element.disabled).toBe(false)

    decisions.mockResolvedValueOnce({ decisions: [] })
    await button('Refresh list').trigger('click')
    await flushPromises()
    expect(decisions).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('No pending decisions.')
    expect(wrapper.find('article').exists()).toBe(false)
  })

  it('shows loading and an actionable load failure without claiming the queue is empty', async () => {
    const loading = deferred()
    decisions.mockReturnValueOnce(loading.promise)
    await open()
    expect(wrapper.text()).toContain('Loading pending decisions…')
    expect(button('Refresh list').element.disabled).toBe(true)
    loading.reject(new Error('network failure'))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('Could not load pending decisions.')
    expect(wrapper.text()).not.toContain('No pending decisions.')
    expect(button('Refresh list').element.disabled).toBe(false)
    await button('Refresh list').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(request.title)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it.each(['replace', 'reject'])('sends %s once and removes only the answered request', async (action) => {
    decisions.mockResolvedValue({ decisions: [request, { ...request, id: 'decision-2', title: 'Another request' }] })
    const answer = deferred()
    resolveDecision.mockReturnValueOnce(answer.promise)
    await open()
    const selected = button(request.actions[action])
    await selected.trigger('click')
    await selected.trigger('click')
    expect(resolveDecision).toHaveBeenCalledExactlyOnceWith(request.id, action, expect.any(AbortSignal))
    expect(wrapper.findAll('button').every((candidate) => candidate.element.disabled)).toBe(true)
    expect(wrapper.findAll('article')).toHaveLength(2)

    decisions.mockResolvedValueOnce({ decisions: [{ ...request, id: 'decision-2', title: 'Another request' }] })
    answer.resolve('')
    await flushPromises()
    expect(wrapper.text()).toContain('Decision submitted.')
    expect(wrapper.text()).not.toContain(request.title)
    expect(wrapper.text()).toContain('Another request')
    expect(wrapper.findAll('article')).toHaveLength(1)
    expect(button('Refresh list').element.disabled).toBe(false)
  })

  it('removes a request answered elsewhere and explains its unavailability', async () => {
    resolveDecision.mockRejectedValueOnce({ status: 404 })
    await open()
    await button('Replace stored key').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('This request is no longer available.')
    expect(wrapper.text()).toContain('No pending decisions.')
    expect(wrapper.find('article').exists()).toBe(false)
  })

  it('keeps a request after an uncertain answer failure without retrying automatically', async () => {
    resolveDecision.mockRejectedValueOnce(new Error('connection lost'))
    await open()
    await button('Replace stored key').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('Refresh the list to check its current status.')
    expect(wrapper.text()).toContain(request.title)
    expect(wrapper.text()).not.toContain('Decision submitted.')
    expect(resolveDecision).toHaveBeenCalledTimes(1)
  })

  it.each([401, 403])('clears requests and reports authorization failure %s to the parent', async (status) => {
    resolveDecision.mockRejectedValueOnce({ status })
    await open()
    await button('Reject connection').trigger('click')
    await flushPromises()
    expect(wrapper.find('article').exists()).toBe(false)
    expect(wrapper.emitted('authorization-error')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('No pending decisions.')
  })

  it('reports an authorization failure while loading', async () => {
    decisions.mockRejectedValueOnce({ status: 403 })
    await open()
    expect(wrapper.emitted('authorization-error')).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('Could not load pending decisions.')
  })

  it('aborts an old load on token change and ignores its late response', async () => {
    const oldLoad = deferred()
    decisions.mockReturnValueOnce(oldLoad.promise)
    await open()
    const oldSignal = decisions.mock.calls[0][0]
    decisions.mockResolvedValueOnce({ decisions: [{ ...request, id: 'new', title: 'New account request' }] })
    await wrapper.setProps({ token: 'new-token' })
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    expect(createOrionClient).toHaveBeenLastCalledWith({ token: 'new-token' })
    oldLoad.resolve({ decisions: [request] })
    await flushPromises()
    expect(wrapper.text()).toContain('New account request')
    expect(wrapper.text()).not.toContain(request.title)
    expect(button('Refresh list').element.disabled).toBe(false)
  })

  it('aborts an answer on token change without letting its late error affect the new account', async () => {
    const answer = deferred()
    resolveDecision.mockReturnValueOnce(answer.promise)
    await open()
    await button('Replace stored key').trigger('click')
    const oldSignal = resolveDecision.mock.calls[0][2]
    decisions.mockResolvedValueOnce({ decisions: [] })
    await wrapper.setProps({ token: 'new-token' })
    await flushPromises()
    answer.reject({ status: 403 })
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    expect(wrapper.emitted('authorization-error')).toBeUndefined()
    expect(wrapper.text()).toContain('No pending decisions.')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it.each(['load', 'answer'])('aborts a pending %s on unmount and ignores its late failure', async (operation) => {
    const pending = deferred()
    if (operation === 'load') decisions.mockReturnValueOnce(pending.promise)
    else resolveDecision.mockReturnValueOnce(pending.promise)
    await open()
    if (operation === 'answer') await button('Replace stored key').trigger('click')
    const signal = operation === 'load' ? decisions.mock.calls[0][0] : resolveDecision.mock.calls[0][2]
    const removed = wrapper
    wrapper.unmount()
    wrapper = null
    expect(signal.aborted).toBe(true)
    pending.reject({ status: 403 })
    await flushPromises()
    expect(removed.emitted('authorization-error')).toBeUndefined()
  })

  it('clears the queue and does not fetch anonymously when the token is removed', async () => {
    await open()
    await wrapper.setProps({ token: '' })
    await flushPromises()
    expect(wrapper.text()).toContain('Connect to review pending decisions.')
    expect(wrapper.find('article').exists()).toBe(false)
    expect(decisions).toHaveBeenCalledTimes(1)
    expect(button('Refresh list').element.disabled).toBe(true)
  })

  it('renders producer descriptions and action labels as text', async () => {
    const text = '<img src=x onerror=alert(1)>'
    decisions.mockResolvedValueOnce({ decisions: [{ ...request, title: text, description: text,
      actions: { custom: text } }] })
    await open()
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain(text)
    await button(text).trigger('click')
    await flushPromises()
    expect(resolveDecision).toHaveBeenCalledWith(request.id, 'custom', expect.any(AbortSignal))
  })
})
