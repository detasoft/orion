import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { encode } from 'cbor2'

const { terminals, client } = vi.hoisted(() => ({
  terminals: [],
  client: { sessionEvents: vi.fn() },
}))
vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    constructor(options) {
      this.options = options
      this.open = vi.fn()
      this.dispose = vi.fn()
      this.write = vi.fn((bytes, done) => done())
      this.resize = vi.fn()
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
  client.sessionEvents.mockReset().mockImplementation(() => Promise.resolve(new Response(new ReadableStream({
    start(controller) { sources.push(controller) },
  }), { headers: { 'Content-Type': 'application/cbor-seq' } })))
  wrapper = mount(SessionTerminal, { props: { token: 'token' } })
})

afterEach(() => wrapper.unmount())

async function open(id = 'session-1') {
  await wrapper.get('input').setValue(id)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('terminal view lifecycle', () => {
  it('opens the entered session, renders output and retains an exited terminal', async () => {
    await open()
    expect(client.sessionEvents.mock.calls[0].slice(0, 2)).toEqual(['session-1', null])
    expect(terminals[0].options.disableStdin).toBe(true)
    expect(terminals[0].open).toHaveBeenCalledWith(wrapper.get('.terminal-viewport').element)
    sources[0].enqueue(encode([1, 0x100, Uint8Array.of(65)]))
    sources[0].enqueue(encode([2, 0x201, [0]]))
    await flushPromises()
    expect(terminals[0].write).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="status"]').text()).toBe('Session exited (0)')
    expect(terminals[0].dispose).not.toHaveBeenCalled()
  })

  it('aborts the old session before opening another and cancels on close', async () => {
    await open()
    const oldSignal = client.sessionEvents.mock.calls[0][2]
    await open('session-2')
    expect(oldSignal.aborted).toBe(true)
    expect(terminals[0].dispose).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('Session session-2')
    await wrapper.get('button[type="button"]').trigger('click')
    expect(client.sessionEvents.mock.calls[1][2].aborted).toBe(true)
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
