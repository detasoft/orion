import { describe, expect, it, vi } from 'vitest'
import { createOrionClient, formatRelativeDate } from './orion-api.js'

describe('formatRelativeDate', () => {
  it('formats recent timestamps', () => {
    const now = new Date('2026-09-02T12:00:00Z')
    expect(formatRelativeDate('2026-09-02T11:42:00Z', now)).toBe('18 minutes ago')
  })
})

describe('createOrionClient', () => {
  it('loads pending decisions with the current token and cancellation signal', async () => {
    const result = { decisions: [{ id: 'decision-1', scope: 'acme/platform',
      title: 'Host key changed', description: 'Review the fingerprint', actions: { replace: 'Replace key' } }] }
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify(result), {
      headers: { 'Content-Type': 'application/json' },
    }))
    const signal = new AbortController().signal
    const client = createOrionClient({ baseUrl: 'https://orion.example/', token: 'old-token', fetchImpl })
    client.setToken('current-token')

    expect(await client.decisions(signal)).toEqual(result)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('https://orion.example/api/admin/decisions')
    expect(init.method).toBeUndefined()
    expect(init.body).toBeUndefined()
    expect(init.headers.get('Authorization')).toBe('Bearer current-token')
    expect(init.signal).toBe(signal)
  })

  it('posts only the decision ID and action and accepts an empty 204 response', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    const signal = new AbortController().signal
    const client = createOrionClient({ token: 'reviewer-token', fetchImpl })

    expect(await client.resolveDecision('decision-1', 'replace', signal)).toBe('')
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/admin/decisions')
    expect(init.method).toBe('POST')
    expect(init.headers.get('Authorization')).toBe('Bearer reviewer-token')
    expect(init.headers.get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init.body)).toEqual({ id: 'decision-1', action: 'replace' })
    expect(init.signal).toBe(signal)
  })

  it.each([403, 404])('preserves decision response status %s for the UI', async (status) => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('Decision request is unavailable', { status }))
    const client = createOrionClient({ fetchImpl })

    await expect(client.resolveDecision('decision-1', 'reject')).rejects.toMatchObject({
      status, message: 'Decision request is unavailable',
    })
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })

  it.each(['list', 'resolve'])('propagates cancellation of a decision %s request', async (operation) => {
    const controller = new AbortController()
    controller.abort()
    const fetchImpl = vi.fn(async (_url, { signal }) => {
      signal.throwIfAborted()
      throw new Error('Expected an aborted signal')
    })
    const client = createOrionClient({ fetchImpl })
    const result = operation === 'list' ? client.decisions(controller.signal)
      : client.resolveDecision('decision-1', 'replace', controller.signal)

    await expect(result).rejects.toBe(controller.signal.reason)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })

  it('sends command identities and reads their existing server status', async () => {
    const fetchImpl = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', {
      headers: { 'Content-Type': 'application/json' },
    })))
    const signal = new AbortController().signal
    const client = createOrionClient({ token: 'token', fetchImpl })
    const command = { commandId: 'input-1', operation: 'input', bytes: 'AP8=' }
    await client.sendSessionCommand('session-1', command, signal)
    await client.sessionCommandStatus('session-1', 'input-1', signal)
    expect(fetchImpl.mock.calls[0][0]).toBe('/api/admin/sessions/session-1/commands')
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual(command)
    expect(fetchImpl.mock.calls[0][1].method).toBe('POST')
    expect(fetchImpl.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer token')
    expect(fetchImpl.mock.calls[1][0]).toBe('/api/admin/sessions/session-1/commands?commandId=input-1')
    expect(fetchImpl.mock.calls[1][1].signal).toBe(signal)
  })

  it('opens an authenticated live journal with an exact unsigned cursor and cancellation', async () => {
    const response = new Response(new Uint8Array([0x80]), {
      headers: { 'Content-Type': 'application/cbor-seq' },
    })
    const fetchImpl = vi.fn().mockResolvedValue(response)
    const signal = new AbortController().signal
    const client = createOrionClient({ token: 'secret-token', fetchImpl })

    expect(await client.sessionEvents('session 1', '18446744073709551614', signal, true)).toBe(response)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/admin/sessions/session%201/events?follow=true&after=18446744073709551614')
    expect(init.headers.get('Authorization')).toBe('Bearer secret-token')
    expect(init.signal).toBe(signal)
    expect(response.bodyUsed).toBe(false)
  })

  it('loads remote aliases through the authenticated Admin API', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('{"aliases":[]}', {
      headers: { 'Content-Type': 'application/json' },
    }))
    const client = createOrionClient({ token: 'admin-token', fetchImpl })
    expect(await client.remoteAliases()).toEqual({ aliases: [] })
    expect(fetchImpl.mock.calls[0][0]).toBe('/api/admin/proxies')
    expect(fetchImpl.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer admin-token')
  })

  it('posts proxy commands with their revision and keeps credentials out of the URL', async () => {
    const result = { status: 'saved', revision: 'next', alias: { alias: 'backup', status: 'success' } }
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify(result), {
      status: 201, headers: { 'Content-Type': 'application/json' },
    }))
    const client = createOrionClient({ token: 'admin-token', fetchImpl })
    const command = { action: 'replace-credential', scope: 'system', revision: 'read',
      alias: 'backup', credential: 'private-value' }
    expect(await client.mutateRemoteAlias(command)).toEqual(result)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/admin/proxies')
    expect(init.method).toBe('POST')
    expect(init.headers.get('Authorization')).toBe('Bearer admin-token')
    expect(init.headers.get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init.body)).toEqual(command)
  })

  it('loads repository discovery from the Admin API', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({ repositories: [] }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    const client = createOrionClient({ fetchImpl })

    await client.repositories()

    expect(fetchImpl.mock.calls[0][0]).toBe('/api/admin/repositories')
    expect(fetchImpl.mock.calls[0][1].method).toBeUndefined()
  })

  it('sends the bearer token and unchanged repository name', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'ok' }), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    }))
    const client = createOrionClient({
      baseUrl: 'http://localhost:8000/',
      token: 'secret-token',
      fetchImpl,
    })

    await client.createRepository('/platform/console.git')

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8000/api/admin/repositories')
    expect(init.headers.get('Authorization')).toBe('Bearer secret-token')
    expect(JSON.parse(init.body)).toEqual({ name: '/platform/console.git' })
  })

  it('reports a useful error when Orion rejects the request', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('Repository name is required', {
      status: 400,
    }))
    const client = createOrionClient({ fetchImpl })

    await expect(client.createRepository('')).rejects.toThrow('Repository name is required')
  })

  it('preserves the response status so authorization failures can disconnect the UI', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('Expired token', { status: 403 }))
    const client = createOrionClient({ fetchImpl })

    await expect(client.routes()).rejects.toMatchObject({
      message: 'Expired token',
      status: 403,
    })
  })
})
