import { describe, expect, it, vi } from 'vitest'
import { createOrionClient, formatRelativeDate, normalizeRepositoryName } from './orion-api.js'

describe('normalizeRepositoryName', () => {
  it('removes leading slashes and the git suffix', () => {
    expect(normalizeRepositoryName(' /teams/console.git ')).toBe('teams/console')
  })

  it('keeps a nested repository name intact', () => {
    expect(normalizeRepositoryName('platform/orion')).toBe('platform/orion')
  })
})

describe('formatRelativeDate', () => {
  it('formats recent timestamps', () => {
    const now = new Date('2026-09-02T12:00:00Z')
    expect(formatRelativeDate('2026-09-02T11:42:00Z', now)).toBe('18 minutes ago')
  })
})

describe('createOrionClient', () => {
  it('sends the bearer token and normalized repository name', async () => {
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
    expect(JSON.parse(init.body)).toEqual({ name: 'platform/console' })
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
