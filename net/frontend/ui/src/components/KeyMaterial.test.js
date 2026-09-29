import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

const client = { keyMaterial: vi.fn(), createKeyMaterial: vi.fn(), issueAcmeCertificate: vi.fn(),
  acmeConfiguration: vi.fn(), saveAcmeConfiguration: vi.fn() }
vi.mock('../lib/orion-api.js', () => ({ createOrionClient: vi.fn(() => client) }))

import KeyMaterial from './KeyMaterial.vue'

beforeEach(() => {
  vi.clearAllMocks()
  client.keyMaterial.mockResolvedValue({ entries: [{
    alias: 'acme-identity', purpose: 'TLS_IDENTITY', algorithm: 'RSA',
    scope: 'cluster:5:orion', version: 1,
    publicKeyPem: '-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----',
    certificates: [],
  }] })
  client.issueAcmeCertificate.mockResolvedValue('-----BEGIN CERTIFICATE-----')
  client.acmeConfiguration.mockResolvedValue({ revision: 'r1', enabled: true, provider: 'letsencrypt',
    directoryUrl: 'https://acme-v02.api.letsencrypt.org/directory', accountEmail: 'admin@example.test',
    domains: ['example.test'], eabKeyId: '', eabConfigured: false, presets: [
      { id: 'letsencrypt', label: "Let's Encrypt", directoryUrl: 'https://acme-v02.api.letsencrypt.org/directory' },
      { id: 'zerossl', label: 'ZeroSSL', directoryUrl: 'https://acme.zerossl.com/v2/DV90', requiresEab: true },
      { id: 'custom', label: 'Other ACME server', directoryUrl: '' },
    ] })
  client.saveAcmeConfiguration.mockResolvedValue({ revision: 'r2' })
  client.createKeyMaterial.mockResolvedValue({})
})

it('generates a named account key and refreshes the inventory', async () => {
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('[aria-label="Key name"]').setValue('account-two')
  await wrapper.get('[aria-label="Create key material"]').trigger('submit')
  await flushPromises()
  expect(client.createKeyMaterial).toHaveBeenCalledWith({
    alias: 'account-two', purpose: 'ACME_ACCOUNT', privateKeyPem: '',
  })
  expect(client.keyMaterial).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})

it('imports Certbot JSON through the existing form and makes the key available for ACME', async () => {
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  const jwk = '{"kty":"RSA","n":"public-modulus","d":"private-exponent"}'
  await wrapper.get('[aria-label="Key operation"]').setValue('import')
  await wrapper.get('[aria-label="Key name"]').setValue('certbot')
  await wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').setValue(jwk)
  expect(wrapper.text()).toContain('private_key.json')
  client.keyMaterial.mockResolvedValueOnce({ entries: [
    { alias: 'certbot', version: 1, purpose: 'ACME_ACCOUNT', algorithm: 'RSA', certificates: [] },
  ] })
  await wrapper.get('[aria-label="Create key material"]').trigger('submit')
  await flushPromises()
  expect(client.createKeyMaterial).toHaveBeenCalledWith({
    alias: 'certbot', purpose: 'ACME_ACCOUNT', privateKeyPem: jwk,
  })
  expect(wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').element.value).toBe('')
  expect(wrapper.get('[aria-label="ACME account key"]').text()).toContain('certbot')
  expect(wrapper.text()).not.toContain('private-exponent')
  wrapper.unmount()
})

it('imports a key and clears the private input even when the request fails', async () => {
  client.createKeyMaterial.mockRejectedValueOnce(Object.assign(new Error('Invalid key'), { status: 400 }))
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('[aria-label="Key operation"]').setValue('import')
  await wrapper.get('[aria-label="Key name"]').setValue('imported')
  await wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').setValue('secret-pem')
  await wrapper.get('[aria-label="Create key material"]').trigger('submit')
  await flushPromises()
  expect(client.createKeyMaterial).toHaveBeenCalledWith(expect.objectContaining({ privateKeyPem: 'secret-pem' }))
  expect(wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').element.value).toBe('')
  expect(wrapper.text()).toContain('Invalid key')
  wrapper.unmount()
})

it('selects only compatible account keys and saves the reference before issuance', async () => {
  client.keyMaterial.mockResolvedValue({ entries: [
    { alias: 'account', version: 3, purpose: 'ACME_ACCOUNT', algorithm: 'RSA', certificates: [] },
    { alias: 'tls-only', version: 1, purpose: 'TLS_IDENTITY', algorithm: 'RSA', certificates: [] },
  ] })
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  const select = wrapper.get('[aria-label="ACME account key"]')
  expect(select.text()).toContain('account')
  expect(select.text()).not.toContain('tls-only')
  await select.setValue('account')
  await wrapper.get('.acme-form').trigger('submit')
  await flushPromises()
  expect(client.saveAcmeConfiguration).toHaveBeenCalledWith(expect.objectContaining({
    accountMaterial: { alias: 'account', version: 3 },
  }))
  expect(client.issueAcmeCertificate).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it('keeps the configured key selected and does not save unchanged settings before issuance', async () => {
  const settings = await client.acmeConfiguration()
  client.acmeConfiguration.mockResolvedValue({ ...settings, accountMaterial: { alias: 'saved', version: 2 } })
  client.keyMaterial.mockResolvedValue({ entries: [
    { alias: 'saved', version: 2, purpose: 'ACME_ACCOUNT', algorithm: 'RSA', certificates: [] },
  ] })
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  expect(wrapper.get('[aria-label="ACME account key"]').element.value).toBe('saved')
  await wrapper.get('.acme-form').trigger('submit')
  await flushPromises()
  expect(client.saveAcmeConfiguration).not.toHaveBeenCalled()
  expect(client.issueAcmeCertificate).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it('does not let an old import clear a new sessions private input', async () => {
  let finish
  client.createKeyMaterial.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const wrapper = mount(KeyMaterial, { props: { token: 'old-token' } })
  await flushPromises()
  await wrapper.get('[aria-label="Key operation"]').setValue('import')
  await wrapper.get('[aria-label="Key name"]').setValue('first')
  await wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').setValue('old-secret')
  await wrapper.get('[aria-label="Create key material"]').trigger('submit')
  await wrapper.setProps({ token: 'new-token' })
  await flushPromises()
  await wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').setValue('new-secret')
  finish({})
  await flushPromises()
  expect(wrapper.get('[aria-label="Private key PEM or Certbot JSON"]').element.value).toBe('new-secret')
  expect(wrapper.text()).not.toContain('Key pair saved.')
  wrapper.unmount()
})

it('enables a complete but disabled ACME configuration before issuing', async () => {
  const settings = await client.acmeConfiguration()
  client.acmeConfiguration.mockResolvedValueOnce({ ...settings, enabled: false })
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(client.saveAcmeConfiguration).toHaveBeenCalledOnce()
  expect(client.issueAcmeCertificate).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it('saves provider credentials before issuing and clears the secret input', async () => {
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('[aria-label="ACME provider"]').setValue('zerossl')
  await wrapper.get('[aria-label="EAB Key ID"]').setValue('account-id')
  await wrapper.get('[aria-label="EAB HMAC key"]').setValue('c2VjcmV0')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(client.saveAcmeConfiguration).toHaveBeenCalledWith(expect.objectContaining({
    revision: 'r1', provider: 'zerossl', eabKeyId: 'account-id', eabHmacKey: 'c2VjcmV0',
  }))
  expect(client.issueAcmeCertificate).toHaveBeenCalledOnce()
  expect(wrapper.get('[aria-label="EAB HMAC key"]').element.value).toBe('')
  expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
  wrapper.unmount()
})

it('does not issue when saving fails and preserves the entered settings for retry', async () => {
  client.saveAcmeConfiguration.mockRejectedValueOnce(Object.assign(new Error('Configuration changed'),
    { status: 409 }))
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('[aria-label="ACME account email"]').setValue('new@example.test')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(client.issueAcmeCertificate).not.toHaveBeenCalled()
  expect(wrapper.get('[aria-label="ACME account email"]').element.value).toBe('new@example.test')
  expect(wrapper.text()).toContain('Configuration changed')
  wrapper.unmount()
})

it('shows public material, issues a certificate, and refreshes the stored list', async () => {
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  expect(wrapper.text()).toContain('acme-identity')
  expect(wrapper.text()).toContain('No issued certificate')
  expect(wrapper.text()).not.toContain('PRIVATE KEY')
  client.keyMaterial.mockResolvedValueOnce({ entries: [{
    alias: 'acme-identity', purpose: 'TLS_IDENTITY', algorithm: 'RSA',
    scope: 'cluster:5:orion', version: 1, publicKeyPem: 'public',
    certificates: [{ subject: '', dnsNames: ['orion.test'], issuer: 'CN=Orion test CA',
      validFrom: '2026-09-25T00:00:00Z', validUntil: '2026-09-26T00:00:00Z',
      sha256Fingerprint: 'AA:BB', serialNumber: '01' }],
  }] })

  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(client.issueAcmeCertificate).toHaveBeenCalledOnce()
  expect(client.keyMaterial).toHaveBeenCalledTimes(2)
  expect(client.saveAcmeConfiguration).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('orion.test')
  expect(wrapper.text()).toContain('AA:BB')
  wrapper.unmount()
})

it('keeps the stored list and reports issuance failure', async () => {
  client.issueAcmeCertificate.mockRejectedValueOnce(Object.assign(new Error('ACME is not configured'),
    { status: 400 }))
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('ACME is not configured')
  expect(wrapper.text()).toContain('acme-identity')
  wrapper.unmount()
})

it('shows the renewal retry and refreshes its result', async () => {
  const settings = await client.acmeConfiguration()
  client.acmeConfiguration.mockResolvedValue({ ...settings, renewal: {
    state: 'retrying', expiresAt: '2026-10-20T00:00:00Z', nextAttempt: '2026-10-01T01:00:00Z',
    lastAttempt: '2026-10-01T00:00:00Z', lastSuccess: '',
    message: 'Certificate renewal failed. Check ACME settings and CA availability.', activationError: '',
  } })
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  expect(wrapper.text()).toContain('Automatic renewal: retrying')
  expect(wrapper.text()).toContain('2026-10-01T01:00:00Z')
  expect(wrapper.text()).toContain('Certificate renewal failed.')
  client.acmeConfiguration.mockResolvedValue({ ...settings, renewal: {
    state: 'scheduled', expiresAt: '2027-01-01T00:00:00Z', nextAttempt: '2026-12-02T00:00:00Z',
    lastAttempt: '2026-10-01T01:00:00Z', lastSuccess: '2026-10-01T01:00:00Z',
    message: '', activationError: 'Could not activate the saved certificate. Retrying automatically.',
  } })
  await wrapper.findAll('button').find(button => button.text() === 'Refresh').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Automatic renewal: scheduled')
  expect(wrapper.text()).toContain('Could not activate the saved certificate.')
  expect(wrapper.text()).not.toContain('Certificate renewal failed.')
  wrapper.unmount()
})

it('preserves issuance success when the renewal status refresh fails', async () => {
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  client.acmeConfiguration.mockRejectedValueOnce(new Error('Unavailable'))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('Certificate issued and saved.')
  expect(wrapper.text()).toContain('Could not refresh renewal status.')
  expect(wrapper.text()).not.toContain('Could not save settings or issue')
  wrapper.unmount()
})
