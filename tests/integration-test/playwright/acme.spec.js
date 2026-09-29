import { readFile } from 'node:fs/promises'
import { X509Certificate } from 'node:crypto'
import { browserUrl, orionUrl, test, expect } from './fixtures.js'

const certificatePath = '/api/admin/acme/certificate'
const rootPath = new URL('../../external-services/.state/step/certs/root_ca.crt', import.meta.url)

function certificates(pem) {
  const blocks = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g)
  expect(blocks?.length).toBeGreaterThanOrEqual(2)
  return blocks.map(block => new X509Certificate(block))
}

test('administrator issues and inspects an HTTP-01 certificate in Orion', async ({
  request, playwright, page, adminToken, step,
}) => {
  const authorized = { Authorization: `Bearer ${adminToken}` }
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

  await step('Открыть Orion', null, () => page.goto(browserUrl))
  await expect(page.getByRole('button', { name: 'Key material' })).toBeVisible()
  await step('Открыть ключи и сертификаты', page.getByRole('button', { name: 'Key material' }),
    target => target.click())
  await expect(page.getByText('acme-identity')).toBeVisible()
  await expect(page.getByText('No issued certificate').first()).toBeVisible()
  await step('Запросить выпуск ACME-сертификата', page.getByRole('button', { name: 'Issue ACME certificate' }),
    target => target.click())
  await expect(page.getByText('Certificate issued and saved.')).toBeVisible({ timeout: 150_000 })
  await expect(page.locator('.material-certificate').first()
    .getByText('orion.test', { exact: true })).toBeVisible()

  const issued = await request.get(`${orionUrl}${certificatePath}`, { headers: authorized })
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
