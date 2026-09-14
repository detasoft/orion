import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { encode } from 'cbor2'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { followSessionTerminal } from './session-terminal.js'

function fixture(name) {
  const path = resolve(dirname(fileURLToPath(import.meta.url)),
    '../../../../../agent-protocol/protocol/fixtures', `${name}.hex`)
  return Uint8Array.from(Buffer.from(readFileSync(path, 'utf8').replace(/\s/g, ''), 'hex'))
}

function stream(...chunks) {
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
  }), { headers: { 'Content-Type': 'application/cbor-seq' } })
}

function setup(responses) {
  const terminal = {
    write: vi.fn((bytes, done) => queueMicrotask(done)),
    resize: vi.fn(),
  }
  const client = { sessionEvents: vi.fn() }
  for (const response of responses) client.sessionEvents.mockResolvedValueOnce(response)
  const abort = new AbortController()
  const onStatus = vi.fn()
  const run = () => followSessionTerminal({ client, sessionId: 'session-1', terminal,
    signal: abort.signal, onStatus })
  return { terminal, client, abort, onStatus, run }
}

afterEach(() => vi.useRealTimers())

describe('session terminal replay', () => {
  it('accepts future payload fields and indefinite strings in unknown records', async () => {
    const futureResize = Uint8Array.from(Buffer.from(
      '840619010283185018186a7061796c6f61642d7631697265636f72642d7631', 'hex'))
    const unknown = Uint8Array.from(Buffer.from('8307197ffe7f61616162ff8308197ffed90100f820', 'hex'))
    const output = Uint8Array.from(Buffer.from('83091901005f410041ffff', 'hex'))
    const test = setup([stream(futureResize, unknown, output, encode([10, 0x201, [0, 'future']]))])
    await test.run()
    expect(test.terminal.resize).toHaveBeenCalledWith(80, 24)
    expect(test.terminal.write.mock.calls.map(([bytes]) => [...bytes])).toEqual([[0, 255]])
  })

  it('replays the shared binary journal fixture one byte at a time and shows exit', async () => {
    const bytes = fixture('session-events-v1')
    const test = setup([stream(...Array.from(bytes, (byte) => Uint8Array.of(byte)))])
    await test.run()

    expect(test.terminal.write.mock.calls.map(([bytes]) => [...bytes])).toEqual([[0, 27, 255]])
    expect(test.terminal.resize).toHaveBeenCalledWith(180, 50)
    expect(test.onStatus).toHaveBeenLastCalledWith('Session exited (0)')
    expect(test.client.sessionEvents).toHaveBeenCalledOnce()
  })

  it('waits for output parsing before resize and reads later live chunks', async () => {
    let source
    let written
    const response = new Response(new ReadableStream({ start(controller) { source = controller } }), {
      headers: { 'Content-Type': 'application/cbor-seq' },
    })
    const test = setup([response])
    test.terminal.write.mockImplementation((bytes, done) => { written = done })
    const running = test.run()
    source.enqueue(encode([1, 0x100, Uint8Array.of(65)]))
    source.enqueue(encode([2, 0x102, [100, 30]]))
    await vi.waitFor(() => expect(written).toBeDefined())
    expect(test.terminal.resize).not.toHaveBeenCalled()
    written()
    await vi.waitFor(() => expect(test.terminal.resize).toHaveBeenCalledWith(100, 30))
    source.enqueue(encode([3, 0x201, [7]]))
    await running
    expect(test.onStatus).toHaveBeenLastCalledWith('Session exited (7)')
  })

  it('resumes after unknown events, discards a truncated tail and does not render duplicates', async () => {
    vi.useFakeTimers()
    const output = encode([6, 0x100, Uint8Array.of(66)])
    const test = setup([
      stream(fixture('session-event-unknown-tail-v1'), output.subarray(0, output.length - 1)),
      stream(fixture('session-event-unknown-tail-v1'), output, encode([7, 0x201, [0]])),
    ])
    const running = test.run()
    await vi.advanceTimersByTimeAsync(1000)
    await running
    expect(test.client.sessionEvents.mock.calls[1][1]).toBe('5')
    expect(test.terminal.write.mock.calls.map(([bytes]) => [...bytes])).toEqual([[66]])
  })

  it('keeps unsigned 64-bit cursors exact across reconnects', async () => {
    vi.useFakeTimers()
    const id = 18446744073709551614n
    const test = setup([stream(encode([id, 0x7ffe, null])), stream(encode([id + 1n, 0x201, [0]]))])
    const running = test.run()
    await vi.advanceTimersByTimeAsync(1000)
    await running
    expect(test.client.sessionEvents.mock.calls[1][1]).toBe(id.toString())
  })

  it('continues past PTY closure until the process exit record', async () => {
    const test = setup([stream(encode([1, 0x103, []]), encode([2, 0x7ffe, null]),
      encode([3, 0x201, [2]]))])
    await test.run()
    expect(test.onStatus).toHaveBeenCalledWith('Terminal closed; waiting for session exit')
    expect(test.onStatus).toHaveBeenLastCalledWith('Session exited (2)')
  })

  it('cancels an idle stream on detach', async () => {
    const cancel = vi.fn()
    const test = setup([new Response(new ReadableStream({ cancel }), {
      headers: { 'Content-Type': 'application/cbor-seq' },
    })])
    const running = test.run()
    await vi.waitFor(() => expect(test.onStatus).toHaveBeenCalledWith('Following session'))
    test.abort.abort()
    await running
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('cancels a pending terminal write without applying the next resize', async () => {
    const test = setup([stream(encode([1, 0x100, Uint8Array.of(65)]), encode([2, 0x102, [80, 24]]))])
    test.terminal.write.mockImplementation(() => {})
    const running = test.run()
    await vi.waitFor(() => expect(test.terminal.write).toHaveBeenCalledOnce())
    test.abort.abort()
    await running
    expect(test.terminal.resize).not.toHaveBeenCalled()
  })

  it('stops retrying when credentials expire', async () => {
    const test = setup([])
    test.client.sessionEvents.mockRejectedValue(Object.assign(new Error('Expired token'), { status: 401 }))
    await expect(test.run()).rejects.toMatchObject({ status: 401 })
    expect(test.client.sessionEvents).toHaveBeenCalledOnce()
  })

  it('retries transient HTTP failures and cancels during retry backoff', async () => {
    vi.useFakeTimers()
    const test = setup([])
    test.client.sessionEvents.mockRejectedValue(Object.assign(new Error('Unavailable'), { status: 503 }))
    const running = test.run()
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.client.sessionEvents).toHaveBeenCalledTimes(2)
    test.abort.abort()
    await running
    await vi.advanceTimersByTimeAsync(10000)
    expect(test.client.sessionEvents).toHaveBeenCalledTimes(2)
  })

  it('resumes after a completed write when the live transport fails', async () => {
    vi.useFakeTimers()
    let source
    const response = new Response(new ReadableStream({ start(controller) { source = controller } }), {
      headers: { 'Content-Type': 'application/cbor-seq' },
    })
    const test = setup([response, stream(encode([2, 0x100, Uint8Array.of(66)]), encode([3, 0x201, [0]]))])
    const running = test.run()
    source.enqueue(encode([1, 0x100, Uint8Array.of(65)]))
    await vi.advanceTimersByTimeAsync(0)
    source.error(new Error('Connection reset'))
    await vi.advanceTimersByTimeAsync(1000)
    await running
    expect(test.client.sessionEvents.mock.calls[1][1]).toBe('1')
    expect(test.terminal.write.mock.calls.map(([bytes]) => [...bytes])).toEqual([[65], [66]])
  })

  it.each([
    [1, 0x100, 'not bytes'],
    [1, 0x102, [0, 24]],
    [1, 0x102, [80, 65536]],
    [-1, 0x7ffe, null],
    [1.5, 0x7ffe, null],
  ])('rejects a malformed record %j without retrying it', async (...record) => {
    const test = setup([stream(encode(record))])
    await expect(test.run()).rejects.toThrow(/Invalid/)
    expect(test.client.sessionEvents).toHaveBeenCalledOnce()
  })
})
