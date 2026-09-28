import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

const client = { keyMaterial: vi.fn(), issueAcmeCertificate: vi.fn() }
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

  await wrapper.get('button[aria-label="Issue ACME certificate"]').trigger('click')
  await flushPromises()
  expect(client.issueAcmeCertificate).toHaveBeenCalledOnce()
  expect(client.keyMaterial).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('orion.test')
  expect(wrapper.text()).toContain('AA:BB')
  wrapper.unmount()
})

it('keeps the stored list and reports issuance failure', async () => {
  client.issueAcmeCertificate.mockRejectedValueOnce(Object.assign(new Error('ACME is not configured'),
    { status: 400 }))
  const wrapper = mount(KeyMaterial, { props: { token: 'admin-token' } })
  await flushPromises()
  await wrapper.get('button[aria-label="Issue ACME certificate"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('ACME is not configured')
  expect(wrapper.text()).toContain('acme-identity')
  wrapper.unmount()
})
