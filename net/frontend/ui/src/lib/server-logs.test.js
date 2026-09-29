import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { startServerLogs } from './server-logs.js'

let stop
let client
let onError

beforeEach(() => {
  vi.useFakeTimers()
  for (const method of ['log', 'warn', 'error', 'debug']) vi.spyOn(console, method).mockImplementation(() => {})
  client = { serverLogs: vi.fn().mockResolvedValue({ cursor: 'process:1', gap: false, entries: [] }) }
  onError = vi.fn()
})

afterEach(() => {
  stop?.()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('server logs in the browser console', () => {
  it('prints Logback text unchanged at the matching level and resumes from the returned cursor', async () => {
    const entries = [
      { level: 'INFO', text: '10:20 [main] INFO server - hello %s\n' },
      { level: 'WARN', text: 'warning\n' },
      { level: 'ERROR', text: 'failure\njava.lang.Exception: example\n\tat Server.run(Server.java:2)\n' },
      { level: 'DEBUG', text: 'debug\n' },
      { level: 'TRACE', text: 'trace\n' },
    ]
    client.serverLogs.mockResolvedValueOnce({ cursor: 'process:5', gap: false, entries })
    stop = startServerLogs({ client, onError })
    await vi.advanceTimersByTimeAsync(0)
    expect(console.log).toHaveBeenCalledWith(entries[0].text)
    expect(console.warn).toHaveBeenCalledWith(entries[1].text)
    expect(console.error).toHaveBeenCalledWith(entries[2].text)
    expect(console.debug.mock.calls).toEqual([[entries[3].text], [entries[4].text]])
    expect(client.serverLogs.mock.calls[0][0]).toBeNull()
    await vi.advanceTimersByTimeAsync(1000)
    expect(client.serverLogs.mock.calls[1][0]).toBe('process:5')
    expect(console.log).toHaveBeenCalledTimes(1)
  })

  it('serializes polling and discards a late response after cancellation', async () => {
    let resolve
    client.serverLogs.mockReturnValueOnce(new Promise((done) => { resolve = done }))
    stop = startServerLogs({ client, onError })
    await vi.advanceTimersByTimeAsync(10000)
    expect(client.serverLogs).toHaveBeenCalledOnce()
    const signal = client.serverLogs.mock.calls[0][1]
    stop()
    expect(signal.aborted).toBe(true)
    resolve({ cursor: 'process:1', gap: false, entries: [{ level: 'INFO', text: 'old connection' }] })
    await vi.advanceTimersByTimeAsync(10000)
    expect(console.log).not.toHaveBeenCalled()
    expect(client.serverLogs).toHaveBeenCalledOnce()
    expect(onError).not.toHaveBeenCalled()
  })

  it('retries transient failures with the same cursor and reports a gap on recovery', async () => {
    client.serverLogs.mockResolvedValueOnce({ cursor: 'process:10', gap: false, entries: [] })
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce({ cursor: 'new-process:1', gap: true,
        entries: [{ level: 'INFO', text: 'after restart\n' }] })
    stop = startServerLogs({ client, onError })
    await vi.advanceTimersByTimeAsync(3000)
    expect(client.serverLogs.mock.calls.map(([cursor]) => cursor))
      .toEqual([null, 'process:10', 'process:10', 'process:10'])
    expect(console.warn).toHaveBeenCalledTimes(2)
    expect(console.log).toHaveBeenCalledWith('after restart\n')
    expect(onError).not.toHaveBeenCalled()
  })

  it.each([401, 403, 404])('stops polling when the server rejects access with %i', async (status) => {
    const error = Object.assign(new Error('rejected'), { status })
    client.serverLogs.mockRejectedValueOnce(error)
    stop = startServerLogs({ client, onError })
    await vi.advanceTimersByTimeAsync(5000)
    expect(onError).toHaveBeenCalledWith(error)
    expect(client.serverLogs).toHaveBeenCalledOnce()
  })
})
