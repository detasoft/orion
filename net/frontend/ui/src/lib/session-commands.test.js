import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSessionCommands } from './session-commands.js'

function setup() {
  const abort = new AbortController()
  const onFailure = vi.fn()
  const client = {
    sendSessionCommand: vi.fn().mockResolvedValue({ phase: 'SENT' }),
    sessionCommandStatus: vi.fn().mockResolvedValue({ phase: 'CONFIRMED', outcome: 'SUCCEEDED' }),
  }
  const commands = createSessionCommands({ client, sessionId: 'session-1', signal: abort.signal, onFailure })
  return { abort, onFailure, client, commands }
}

afterEach(() => vi.useRealTimers())

describe('terminal commands', () => {
  it('does not poll a command before its POST response while other commands are pending', async () => {
    vi.useFakeTimers()
    const test = setup()
    let finish
    test.client.sendSessionCommand.mockResolvedValueOnce({ phase: 'SENT' })
      .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    test.commands.send({ operation: 'input', bytes: 'YQ==' })
    test.commands.send({ operation: 'input', bytes: 'Yg==' })
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.client.sessionCommandStatus).toHaveBeenCalledOnce()
    finish({ phase: 'SENT' })
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.client.sessionCommandStatus).toHaveBeenCalledTimes(2)
    test.abort.abort()
  })

  it('bounds commands waiting for confirmation and reports unsent input', async () => {
    vi.useFakeTimers()
    const test = setup()
    test.client.sendSessionCommand.mockImplementation(() => new Promise(() => {}))
    for (let i = 0; i < 65; i++) test.commands.send({ operation: 'input', bytes: 'YQ==' })
    expect(test.onFailure).toHaveBeenCalledWith(expect.stringContaining('queued input was not sent'))
    expect(test.client.sendSessionCommand).toHaveBeenCalledOnce()
    test.abort.abort()
  })

  it('sends input and resize sequentially with distinct identities, then observes confirmation', async () => {
    vi.useFakeTimers()
    const test = setup()
    let finish
    test.client.sendSessionCommand.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    test.commands.send({ operation: 'input', bytes: 'AP8=' })
    test.commands.send({ operation: 'resize', columns: 100, rows: 30 })
    await vi.advanceTimersByTimeAsync(0)
    expect(test.client.sendSessionCommand).toHaveBeenCalledOnce()
    finish({ phase: 'SENT' })
    await vi.advanceTimersByTimeAsync(0)
    const first = test.client.sendSessionCommand.mock.calls[0][1]
    const second = test.client.sendSessionCommand.mock.calls[1][1]
    expect(first).toMatchObject({ operation: 'input', bytes: 'AP8=' })
    expect(second).toMatchObject({ operation: 'resize', columns: 100, rows: 30 })
    expect(first.commandId).not.toBe(second.commandId)
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.client.sessionCommandStatus).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(3000)
    expect(test.client.sessionCommandStatus).toHaveBeenCalledTimes(2)
    expect(test.onFailure).not.toHaveBeenCalled()
    test.abort.abort()
  })

  it('surfaces late delivery failure, pauses input and never resubmits the command', async () => {
    vi.useFakeTimers()
    const test = setup()
    test.client.sessionCommandStatus.mockResolvedValue({ phase: 'DELIVERY_FAILED', detail: 'agent offline' })
    test.commands.send({ operation: 'input', bytes: 'YQ==' })
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.onFailure).toHaveBeenCalledWith(expect.stringContaining('agent offline'))
    test.commands.send({ operation: 'input', bytes: 'Yg==' })
    await vi.advanceTimersByTimeAsync(3000)
    expect(test.client.sendSessionCommand).toHaveBeenCalledOnce()
    test.abort.abort()
  })

  it('keeps UNKNOWN pending and reports a journal-confirmed execution failure', async () => {
    vi.useFakeTimers()
    const test = setup()
    test.client.sendSessionCommand.mockResolvedValue({ phase: 'UNKNOWN' })
    test.client.sessionCommandStatus.mockResolvedValueOnce({ phase: 'SENT' })
      .mockResolvedValueOnce({ phase: 'CONFIRMED', outcome: 'FAILED', detail: 'PTY is closed' })
    test.commands.send({ operation: 'resize', columns: 80, rows: 24 })
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.onFailure).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1000)
    expect(test.onFailure).toHaveBeenCalledWith(expect.stringContaining('PTY is closed'))
    test.abort.abort()
  })

  it('stops queued input after an ambiguous HTTP failure', async () => {
    vi.useFakeTimers()
    const test = setup()
    test.client.sendSessionCommand.mockRejectedValue(new TypeError('Network failed'))
    test.commands.send({ operation: 'input', bytes: 'YQ==' })
    test.commands.send({ operation: 'input', bytes: 'Yg==' })
    await vi.advanceTimersByTimeAsync(2000)
    expect(test.onFailure).toHaveBeenCalledWith(expect.stringContaining('Network failed'))
    expect(test.client.sendSessionCommand).toHaveBeenCalledOnce()
    test.abort.abort()
  })

  it('cancels requests and polling when the terminal detaches', async () => {
    vi.useFakeTimers()
    const test = setup()
    test.commands.send({ operation: 'input', bytes: 'YQ==' })
    await vi.advanceTimersByTimeAsync(0)
    test.abort.abort()
    expect(test.client.sendSessionCommand.mock.calls[0][2].aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(5000)
    expect(test.client.sessionCommandStatus).not.toHaveBeenCalled()
  })
})
