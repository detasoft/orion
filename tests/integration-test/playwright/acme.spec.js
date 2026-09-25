import { readFile } from 'node:fs/promises'
import { X509Certificate } from 'node:crypto'
import { test, expect } from '@playwright/test'

const orionUrl = process.env.ORION_HTTP_URL ?? 'http://orion.test:8000'
const fixtureUrl = 'https://fixture.orion.test:9000'
const certificatePath = '/api/admin/acme/certificate'
const rootPath = new URL('../../external-services/.state/step/certs/root_ca.crt', import.meta.url)

function certificates(pem) {
  const blocks = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g)
  expect(blocks?.length).toBeGreaterThanOrEqual(2)
  return blocks.map(block => new X509Certificate(block))
}

test('Orion obtains and saves its own certificate through HTTP-01', async ({ request, playwright }) => {
  const token = process.env.ORION_TOKEN
  expect(token, 'Set ORION_TOKEN to an Orion admin bearer token').toBeTruthy()

  const directory = await request.get(`${fixtureUrl}/acme/acme/directory`)
  expect(directory.ok()).toBeTruthy()
  expect((await directory.json()).newOrder).toBeTruthy()

  const authorized = { Authorization: `Bearer ${token}` }
  const before = await request.get(`${orionUrl}${certificatePath}`, { headers: authorized })
  expect([200, 404]).toContain(before.status())
  const previous = before.status() === 200 ? await before.text() : null

  const denied = await request.post(`${orionUrl}${certificatePath}`, {
    headers: { Authorization: 'Bearer invalid-token' }
  })
  expect(denied.status()).toBe(403)
  const afterDenied = await request.get(`${orionUrl}${certificatePath}`, { headers: authorized })
  expect(afterDenied.status()).toBe(before.status())
  if (previous !== null) expect(await afterDenied.text()).toBe(previous)

  const issued = await request.post(`${orionUrl}${certificatePath}`, {
    headers: authorized,
    timeout: 150_000
  })
  const issuedBody = await issued.text()
  expect(issued.status(), issuedBody).toBe(200)
  const issuedChain = certificates(issuedBody)
  const leaf = issuedChain[0]
  const issuer = issuedChain[1]
  const root = new X509Certificate(await readFile(rootPath, 'utf8'))
  expect(leaf.checkHost('orion.test')).toBe('orion.test')
  expect(Date.parse(leaf.validTo)).toBeGreaterThan(Date.now())
  expect(leaf.verify(issuer.publicKey)).toBe(true)
  expect(issuer.verify(root.publicKey)).toBe(true)

  const freshClient = await playwright.request.newContext({
    extraHTTPHeaders: authorized
  })
  try {
    const saved = await freshClient.get(`${orionUrl}${certificatePath}`)
    expect(saved.status()).toBe(200)
    expect(certificates(await saved.text())[0].fingerprint256).toBe(leaf.fingerprint256)
  } finally {
    await freshClient.dispose()
  }
})
