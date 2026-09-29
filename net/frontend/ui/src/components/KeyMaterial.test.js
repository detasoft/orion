import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

const client = { keyMaterial: vi.fn(), issueAcmeCertificate: vi.fn(),
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
