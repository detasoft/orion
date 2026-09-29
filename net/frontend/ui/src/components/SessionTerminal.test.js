import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { encode } from 'cbor2'

const { terminals, client } = vi.hoisted(() => ({
  terminals: [],
  client: { sessions: vi.fn(), sessionEvents: vi.fn(), sendSessionCommand: vi.fn(), sessionCommandStatus: vi.fn() },
}))
vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    constructor(options) {
      this.options = options
      this.open = vi.fn()
      this.dispose = vi.fn()
      this.write = vi.fn((bytes, done) => done())
      this.resize = vi.fn()
      this.onData = vi.fn((listener) => { this.data = listener })
      this.onBinary = vi.fn((listener) => { this.binary = listener })
      terminals.push(this)
    }
  },
}))
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: () => client }))

import SessionTerminal from './SessionTerminal.vue'

let wrapper
const sources = []

beforeEach(() => {
  terminals.length = 0
  sources.length = 0
  client.sessions.mockReset().mockResolvedValue({ sessions: [
    { id: 'session-1', agent: 'agent-a', state: 'RUNNING' },
    { id: 'session-2', agent: 'agent-b', state: 'EXITED' },
  ] })
  client.sendSessionCommand.mockReset().mockResolvedValue({ phase: 'CONFIRMED', outcome: 'SUCCEEDED' })
  client.sessionCommandStatus.mockReset()
  client.sessionEvents.mockReset().mockImplementation((id, after, signal, follow) => {
    return Promise.resolve(new Response(new ReadableStream({
      start(controller) {
        if (follow) sources.push(controller)
        else controller.close()
      },
    }), { headers: { 'Content-Type': 'application/cbor-seq' } }))
  })
  wrapper = mount(SessionTerminal, { props: {
    token: 'token', onSelectSession: (sessionId) => wrapper.setProps({ sessionId }),
  } })
})

afterEach(() => {
  wrapper.unmount()
  vi.useRealTimers()
})

async function open(id = 'session-1') {
  await flushPromises()
  await wrapper.get('select[aria-label="Session"]').setValue(id)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('terminal view lifecycle', () => {
  it('allows retrying the selected session after its initial open failed', async () => {
    client.sessionEvents.mockRejectedValueOnce(Object.assign(new Error('Session not found'), { status: 404 }))
    await open()
    expect(wrapper.get('[role="status"]').text()).toBe('Session not found')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(terminals).toHaveLength(2)
    expect(terminals[0].dispose).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="status"]').text()).toBe('Following session')
  })

  it('lists sessions with their agent and state without opening one automatically', async () => {
    await flushPromises()
    expect(wrapper.get('select').text()).toContain('session-1 — agent-a — RUNNING')
    expect(wrapper.get('select').text()).toContain('session-2 — agent-b — EXITED')
    expect(client.sessionEvents).not.toHaveBeenCalled()
  })

  it('opens the session selected by the route and follows route changes', async () => {
    await wrapper.setProps({ sessionId: 'session-1' })
    await flushPromises()
    const signal = client.sessionEvents.mock.calls[0][2]
    await wrapper.setProps({ sessionId: 'session-2' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(wrapper.text()).toContain('Session session-2')
    await wrapper.setProps({ sessionId: '' })
    expect(terminals[1].dispose).toHaveBeenCalledOnce()
  })

  it('shows an empty list and allows it to be refreshed', async () => {
    client.sessions.mockResolvedValueOnce({ sessions: [] })
    await flushPromises()
    await wrapper.get('[aria-label="Refresh sessions"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('No sessions available')
    await wrapper.get('[aria-label="Refresh sessions"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('select').text()).toContain('session-1')
  })

  it.each([403, 503])('reports list failure %s and allows retry', async (status) => {
    await flushPromises()
    client.sessions.mockRejectedValueOnce(Object.assign(new Error('List unavailable'), { status }))
    await wrapper.get('[aria-label="Refresh sessions"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('List unavailable')
    expect(Boolean(wrapper.emitted('authorization-error'))).toBe(status === 403)
    await wrapper.get('[aria-label="Refresh sessions"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('List unavailable')
  })

  it('ignores list results from replaced credentials and cancels on unmount', async () => {
    await flushPromises()
    let reject
    client.sessions.mockImplementationOnce(() => new Promise((resolve, fail) => { reject = fail }))
    await wrapper.get('[aria-label="Refresh sessions"]').trigger('click')
    const signal = client.sessions.mock.lastCall[0]
    await wrapper.setProps({ token: 'new-token' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    reject(Object.assign(new Error('Old token expired'), { status: 403 }))
    await flushPromises()
    expect(wrapper.emitted('authorization-error')).toBeUndefined()
    expect(wrapper.text()).not.toContain('Old token expired')
    const currentSignal = client.sessions.mock.lastCall[0]
    wrapper.unmount()
    expect(currentSignal.aborted).toBe(true)
  })
  it('suppresses terminal replies during history and enables input after the cursor handoff', async () => {
    let history
    client.sessionEvents.mockImplementationOnce(() => Promise.resolve(new Response(new ReadableStream({
      start(controller) { history = controller },
    }), { headers: { 'Content-Type': 'application/cbor-seq' } })))
    await open()
    expect(terminals[0].options.disableStdin).toBe(true)
    terminals[0].write.mockImplementation((bytes, done) => {
      terminals[0].data('reply to old terminal query')
      done()
    })
    history.enqueue(encode([10, 0x100, Uint8Array.of(27, 91, 54, 110)]))
    await flushPromises()
    expect(client.sendSessionCommand).not.toHaveBeenCalled()
    history.close()
    await flushPromises()
    expect(client.sessionEvents.mock.calls[1].slice(0, 2)).toEqual(['session-1', '10'])
    expect(client.sessionEvents.mock.calls[1][3]).toBe(true)
    expect(terminals[0].options.disableStdin).toBe(false)
    terminals[0].data('a')
    await flushPromises()
    expect(client.sendSessionCommand).toHaveBeenCalledOnce()
  })

  it('opens the entered session, renders output and retains an exited terminal', async () => {
    await open()
    expect(client.sessionEvents.mock.calls[0].slice(0, 2)).toEqual(['session-1', null])
    expect(terminals[0].options.disableStdin).toBe(false)
    expect(terminals[0].open).toHaveBeenCalledWith(wrapper.get('.terminal-viewport').element)
    sources[0].enqueue(encode([1, 0x100, Uint8Array.of(65)]))
    sources[0].enqueue(encode([2, 0x201, [0]]))
    await flushPromises()
    expect(terminals[0].write).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="status"]').text()).toBe('Session exited (0)')
    expect(terminals[0].dispose).not.toHaveBeenCalled()
    expect(terminals[0].options.disableStdin).toBe(true)
  })

  it('sends Unicode input and binary input without changing the bytes', async () => {
    await open()
    terminals[0].data('ж\r')
    terminals[0].binary('\x00\xff')
    await flushPromises()
    expect(client.sendSessionCommand.mock.calls[0][1]).toMatchObject({ operation: 'input', bytes: '0LYN' })
    expect(client.sendSessionCommand.mock.calls[1][1]).toMatchObject({ operation: 'input', bytes: 'AP8=' })
  })

  it('splits a paste into bounded commands without changing its UTF-8 bytes', async () => {
    await open()
    const paste = 'ж'.repeat(9000)
    terminals[0].data(paste)
    await flushPromises()
    const actual = client.sendSessionCommand.mock.calls.map(([, command]) => atob(command.bytes)).join('')
    expect(Array.from(actual, (char) => char.charCodeAt(0))).toEqual(Array.from(new TextEncoder().encode(paste)))
    expect(client.sendSessionCommand).toHaveBeenCalledTimes(3)
  })

  it('sends a resize and applies dimensions only from the journal event', async () => {
    await open()
    await wrapper.get('input[aria-label="Columns"]').setValue(120)
    await wrapper.get('input[aria-label="Rows"]').setValue(40)
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(client.sendSessionCommand.mock.calls[0][1]).toMatchObject({
      operation: 'resize', columns: 120, rows: 40,
    })
    expect(terminals[0].resize).not.toHaveBeenCalled()
    sources[0].enqueue(encode([1, 0x102, [120, 40]]))
    await flushPromises()
    expect(terminals[0].resize).toHaveBeenCalledWith(120, 40)
    expect(client.sendSessionCommand).toHaveBeenCalledOnce()
  })

  it('shows delivery failure and disables further input while retaining output', async () => {
    client.sendSessionCommand.mockResolvedValue({ phase: 'DELIVERY_FAILED', detail: 'agent offline' })
    await open()
    terminals[0].data('a')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('agent offline')
    expect(terminals[0].options.disableStdin).toBe(true)
    terminals[0].data('b')
    expect(client.sendSessionCommand).toHaveBeenCalledOnce()
    sources[0].enqueue(encode([1, 0x100, Uint8Array.of(65)]))
    await flushPromises()
    expect(terminals[0].write).toHaveBeenCalledOnce()
  })

  it.each([
    ['sendSessionCommand', 401], ['sendSessionCommand', 403],
    ['sessionCommandStatus', 401], ['sessionCommandStatus', 403],
    ['sendSessionCommand', 500], ['sessionCommandStatus', 500],
  ])('propagates authorization failures from %s (%s) without retrying', async (method, status) => {
    vi.useFakeTimers()
    client.sendSessionCommand.mockResolvedValue({ phase: 'SENT' })
    client[method].mockRejectedValue(Object.assign(new Error('Request rejected'), { status }))
    await open()
    terminals[0].data('a')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)

    if (status === 401 || status === 403) {
      expect(wrapper.emitted('authorization-error')).toHaveLength(1)
    } else {
      expect(wrapper.emitted('authorization-error')).toBeUndefined()
    }
    expect(wrapper.get('[role="alert"]').text()).toContain('Request rejected')
    expect(terminals[0].options.disableStdin).toBe(true)
    terminals[0].data('b')
    await vi.advanceTimersByTimeAsync(5000)
    expect(client.sendSessionCommand).toHaveBeenCalledOnce()
    expect(client.sessionCommandStatus).toHaveBeenCalledTimes(method === 'sessionCommandStatus' ? 1 : 0)
  })

  it.each(['sendSessionCommand', 'sessionCommandStatus'])(
    'ignores a late authorization rejection from detached %s', async (method) => {
      vi.useFakeTimers()
      let reject
      client.sendSessionCommand.mockResolvedValue({ phase: 'SENT' })
      client[method].mockImplementationOnce(() => new Promise((resolve, fail) => { reject = fail }))
      await open()
      terminals[0].data('a')
      await flushPromises()
      await vi.advanceTimersByTimeAsync(1000)
      await wrapper.setProps({ token: 'replacement' })

      reject(Object.assign(new Error('Old token rejected'), { status: 403 }))
      await flushPromises()
      expect(wrapper.emitted('authorization-error')).toBeUndefined()
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    },
  )

  it('aborts the old session before opening another and cancels on close', async () => {
    await open()
    const oldSignal = client.sessionEvents.mock.calls[0][2]
    await open('session-2')
    expect(oldSignal.aborted).toBe(true)
    expect(terminals[0].dispose).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Session session-2')
    await wrapper.get('[aria-label="Close session"]').trigger('click')
    expect(client.sessionEvents.mock.calls[3][2].aborted).toBe(true)
    expect(terminals[1].dispose).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="status"]').text()).toBe('')
  })

  it('closes the stream when the credentials change', async () => {
    await open()
    await wrapper.setProps({ token: 'replacement' })
    expect(client.sessionEvents.mock.calls[0][2].aborted).toBe(true)
    expect(terminals[0].dispose).toHaveBeenCalledOnce()
  })

  it('closes the stream when leaving the view', async () => {
    await open()
    wrapper.unmount()
    expect(client.sessionEvents.mock.calls[0][2].aborted).toBe(true)
    expect(terminals[0].dispose).toHaveBeenCalledOnce()
  })

  it('reports expired credentials to the owning app', async () => {
    client.sessionEvents.mockRejectedValue(Object.assign(new Error('Expired token'), { status: 403 }))
    await open()
    expect(wrapper.emitted('authorization-error')).toHaveLength(1)
    expect(wrapper.get('[role="status"]').text()).toBe('Expired token')
  })
})
