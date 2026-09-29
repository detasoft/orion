<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const entries = ref([])
const loading = ref(false)
const issuing = ref(false)
const message = ref('')
const error = ref('')
const configurationLoading = ref(false)
const configured = ref(false)
const renewal = ref(null)
const form = reactive({ revision: '', enabled: false, provider: 'letsencrypt', directoryUrl: '',
  accountEmail: '', domains: '', eabKeyId: '', eabHmacKey: '', eabConfigured: false })
const presets = ref([])
let savedSettings = ''
const eabVisible = computed(() => form.provider !== 'letsencrypt' || form.eabConfigured)
const needsEab = computed(() => presets.value.find(preset => preset.id === form.provider)?.requiresEab)
let attempt = 0

function settingsKey() {
  return JSON.stringify([form.provider, form.directoryUrl, form.accountEmail,
    form.domains.split(/[\s,]+/).filter(Boolean), form.eabKeyId])
}

async function loadConfiguration() {
  const current = attempt
  configurationLoading.value = true
  configured.value = false
  form.eabHmacKey = ''
  try {
    const response = await createOrionClient({ token: props.token }).acmeConfiguration()
    if (current !== attempt) return
    Object.assign(form, response, { domains: response.domains.join(', '), eabHmacKey: '' })
    renewal.value = response.renewal
    presets.value = response.presets
    savedSettings = settingsKey()
    configured.value = true
  } catch (failure) {
    if (current !== attempt) return
    error.value = 'Could not load ACME settings.'
    if (failure?.status === 401 || failure?.status === 403) emit('authorization-error')
  } finally {
    if (current === attempt) configurationLoading.value = false
  }
}

function providerChanged() {
  form.eabKeyId = ''
  form.eabHmacKey = ''
  form.eabConfigured = false
  form.directoryUrl = presets.value.find(preset => preset.id === form.provider)?.directoryUrl ?? ''
}

async function loadEntries() {
  const current = ++attempt
  loading.value = true
  error.value = ''
  try {
    const response = await createOrionClient({ token: props.token }).keyMaterial()
    if (current === attempt) entries.value = response.entries ?? []
  } catch (failure) {
    if (current !== attempt) return
    error.value = 'Could not load key material.'
    if (failure?.status === 401 || failure?.status === 403) emit('authorization-error')
  } finally {
    if (current === attempt) loading.value = false
  }
}

async function saveConfiguration(issue = false) {
  if (issuing.value || loading.value || configurationLoading.value || !configured.value) return
  const current = attempt
  issuing.value = true
  message.value = ''
  error.value = ''
  try {
    const client = createOrionClient({ token: props.token })
    if (!issue || !form.enabled || settingsKey() !== savedSettings || form.eabHmacKey) {
      const response = await client.saveAcmeConfiguration({ revision: form.revision, provider: form.provider,
        directoryUrl: form.directoryUrl, accountEmail: form.accountEmail,
        domains: form.domains.split(/[\s,]+/).filter(Boolean), eabKeyId: form.eabKeyId,
        eabHmacKey: form.eabHmacKey })
      if (current !== attempt) return
      form.revision = response.revision
      form.enabled = true
      form.eabConfigured = Boolean(form.eabKeyId)
      form.eabHmacKey = ''
      savedSettings = settingsKey()
    }
    if (issue) await client.issueAcmeCertificate()
    if (current !== attempt) return
    message.value = issue ? 'Certificate issued and saved.' : 'ACME settings saved.'
    const statusAttempt = issue ? current + 1 : current
    if (issue) await loadEntries()
    if (statusAttempt !== attempt) return
    try {
      const response = await client.acmeConfiguration()
      if (statusAttempt === attempt) renewal.value = response.renewal
    } catch (failure) {
      if (statusAttempt !== attempt) return
      error.value = 'Could not refresh renewal status.'
      if (failure?.status === 401 || failure?.status === 403) emit('authorization-error')
    }
  } catch (failure) {
    if (current !== attempt) return
    if (failure?.status === 401 || failure?.status === 403) {
      emit('authorization-error')
      return
    }
    error.value = [400, 409].includes(failure?.status) ? failure.message
      : 'Could not save settings or issue the certificate.'
  } finally {
    issuing.value = false
  }
}

watch(() => props.token, () => {
  entries.value = []
  renewal.value = null
  loadEntries()
  loadConfiguration()
}, { immediate: true })
onBeforeUnmount(() => { attempt += 1 })
</script>

<template>
  <section class="panel content-panel">
    <div class="content-toolbar">
      <p>Key material <span>Certificates and public key information in Orion’s protected store</span></p>
      <div class="material-actions">
        <button class="secondary-button" :disabled="loading || issuing || configurationLoading"
          @click="loadEntries(); loadConfiguration()">Refresh</button>
      </div>
    </div>
    <form class="acme-form" @submit.prevent="saveConfiguration(true)">
      <fieldset :disabled="loading || issuing || configurationLoading || !configured">
        <legend>ACME certificate</legend>
        <div class="acme-fields">
          <label>Provider
            <select v-model="form.provider" aria-label="ACME provider" @change="providerChanged">
              <option v-for="preset in presets" :key="preset.id" :value="preset.id">{{ preset.label }}</option>
            </select>
          </label>
          <label v-if="form.provider === 'custom'">Directory URL
            <input v-model="form.directoryUrl" aria-label="ACME directory URL" type="url" required>
          </label>
          <label>Account email
            <input v-model="form.accountEmail" aria-label="ACME account email" type="email" required>
          </label>
          <label>Domains (comma separated)
            <input v-model="form.domains" aria-label="ACME domains" required placeholder="example.com, www.example.com">
          </label>
          <template v-if="eabVisible">
            <label>EAB Key ID
              <input v-model="form.eabKeyId" aria-label="EAB Key ID" :required="needsEab">
            </label>
            <label>EAB HMAC key
              <input v-model="form.eabHmacKey" aria-label="EAB HMAC key" type="password" autocomplete="new-password"
                :required="Boolean(form.eabKeyId) && !form.eabConfigured"
                :placeholder="form.eabConfigured ? 'Saved — leave blank to keep' : 'Base64url secret from your provider'">
            </label>
          </template>
        </div>
        <p>EAB credentials are stored encrypted. Domain verification uses HTTP-01 on port 80.</p>
        <p>By issuing a certificate, you confirm acceptance of the selected provider’s terms of service.</p>
        <div class="material-actions">
          <button type="button" class="secondary-button" @click="saveConfiguration(false)">Save settings</button>
          <button type="submit" class="primary-button" aria-label="Issue ACME certificate">
            {{ issuing ? 'Working…' : 'Issue ACME certificate' }}
          </button>
        </div>
      </fieldset>
    </form>
    <div v-if="renewal" class="renewal-status" aria-label="Automatic certificate renewal">
      <p>Automatic renewal: {{ renewal.state.replaceAll('_', ' ') }}</p>
      <p v-if="renewal.expiresAt">Certificate expires: {{ renewal.expiresAt }}</p>
      <p v-if="renewal.nextAttempt">Next attempt: {{ renewal.nextAttempt }}</p>
      <p v-if="renewal.lastAttempt">Last attempt: {{ renewal.lastAttempt }}</p>
      <p v-if="renewal.lastSuccess">Last successful issuance: {{ renewal.lastSuccess }}</p>
      <p v-if="renewal.message" role="status">{{ renewal.message }}</p>
      <p v-if="renewal.activationError" class="material-error" role="alert">{{ renewal.activationError }}</p>
      <p>Checks run every minute after the first certificate is issued. Failed renewals retry after one hour.</p>
    </div>
    <p v-if="message" class="material-message" role="status">{{ message }}</p>
    <p v-if="error" class="material-error" role="alert">{{ error }}</p>
    <div v-if="loading && !entries.length" class="empty-state" role="status">Loading key material…</div>
    <div v-else-if="!entries.length && !error" class="empty-state">The material store is empty.</div>
    <div v-else class="material-list">
      <article v-for="entry in entries" :key="entry.alias" class="material-entry">
        <div class="material-entry-heading">
          <h3>{{ entry.alias }}</h3>
          <span>{{ entry.purpose.replaceAll('_', ' ') }}</span>
        </div>
        <p>{{ entry.algorithm }} · version {{ entry.version }} · {{ entry.scope }}</p>
        <p v-if="!entry.certificates.length">No issued certificate</p>
        <div v-for="certificate in entry.certificates" :key="certificate.sha256Fingerprint"
          class="material-certificate">
          <strong>{{ certificate.dnsNames?.join(', ') || certificate.subject || 'Unnamed certificate' }}</strong>
          <p v-if="certificate.subject">Subject: {{ certificate.subject }}</p>
          <p>Issuer: {{ certificate.issuer }}</p>
          <p>Valid: {{ certificate.validFrom }} – {{ certificate.validUntil }}</p>
          <p>SHA-256: <code>{{ certificate.sha256Fingerprint }}</code></p>
          <p>Serial: {{ certificate.serialNumber }}</p>
        </div>
        <details v-if="entry.publicKeyPem">
          <summary>Public key</summary>
          <pre>{{ entry.publicKeyPem }}</pre>
        </details>
      </article>
    </div>
  </section>
</template>

<style scoped>
.acme-form { margin-block: 1rem; }
.acme-form fieldset { min-width: 0; border: 1px solid var(--border, #d6d6d6); border-radius: 8px; padding: 1rem; }
.acme-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: 1rem; }
.acme-fields label { display: grid; gap: .4rem; min-width: 0; }
.acme-fields input, .acme-fields select { width: 100%; min-width: 0; box-sizing: border-box; padding: .6rem; }
.acme-form p { font-size: .85rem; }
</style>
