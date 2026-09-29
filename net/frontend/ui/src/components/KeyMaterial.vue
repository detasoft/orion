<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const entries = ref([])
const loading = ref(false)
const issuing = ref(false)
const creating = ref(false)
const keyForm = reactive({ alias: '', purpose: 'ACME_ACCOUNT', operation: 'generate', privateKeyPem: '' })
const message = ref('')
const error = ref('')
const configurationLoading = ref(false)
const configured = ref(false)
const renewal = ref(null)
const form = reactive({ revision: '', enabled: false, provider: 'letsencrypt', directoryUrl: '',
  accountEmail: '', domains: '', accountMaterial: null, eabKeyId: '', eabHmacKey: '', eabConfigured: false })
const presets = ref([])
const accountKeys = computed(() => entries.value.filter(entry =>
  entry.purpose === 'ACME_ACCOUNT' && entry.algorithm === 'RSA'))
const accountAlias = computed({
  get: () => form.accountMaterial?.alias ?? '',
  set: alias => {
    const entry = accountKeys.value.find(key => key.alias === alias)
    form.accountMaterial = entry ? { alias: entry.alias, version: entry.version } : null
    form.eabHmacKey = ''
    form.eabConfigured = false
  },
})
let savedSettings = ''
const eabVisible = computed(() => form.provider !== 'letsencrypt' || form.eabConfigured)
const needsEab = computed(() => presets.value.find(preset => preset.id === form.provider)?.requiresEab)
let attempt = 0

function settingsKey() {
  return JSON.stringify([form.provider, form.directoryUrl, form.accountEmail,
    form.domains.split(/[\s,]+/).filter(Boolean), form.eabKeyId, form.accountMaterial])
}

async function loadConfiguration() {
  const current = attempt
  configurationLoading.value = true
  configured.value = false
  form.eabHmacKey = ''
  try {
    const response = await createOrionClient({ token: props.token }).acmeConfiguration()
    if (current !== attempt) return
    Object.assign(form, response, { domains: response.domains.join(', '), eabHmacKey: '',
      accountMaterial: response.accountMaterial ?? null })
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
  form.accountMaterial = null
  form.directoryUrl = presets.value.find(preset => preset.id === form.provider)?.directoryUrl ?? ''
}

async function loadEntries() {
  const current = attempt
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
  if (issuing.value || creating.value || loading.value || configurationLoading.value || !configured.value) return
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
        eabHmacKey: form.eabHmacKey, accountMaterial: form.accountMaterial })
      if (current !== attempt) return
      form.revision = response.revision
      form.accountMaterial = response.accountMaterial ?? null
      form.enabled = true
      form.eabConfigured = Boolean(form.eabKeyId)
      form.eabHmacKey = ''
      savedSettings = settingsKey()
    }
    if (issue) await client.issueAcmeCertificate()
    if (current !== attempt) return
    message.value = issue ? 'Certificate issued and saved.' : 'ACME settings saved.'
    await loadEntries()
    if (current !== attempt) return
    try {
      const response = await client.acmeConfiguration()
      if (current === attempt) renewal.value = response.renewal
    } catch (failure) {
      if (current !== attempt) return
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
    if (current === attempt) issuing.value = false
  }
}

async function createKey() {
  if (creating.value || issuing.value || loading.value || configurationLoading.value) return
  const current = attempt
  creating.value = true
  message.value = ''
  error.value = ''
  try {
    const request = createOrionClient({ token: props.token }).createKeyMaterial({
      alias: keyForm.alias, purpose: keyForm.purpose,
      privateKeyPem: keyForm.operation === 'import' ? keyForm.privateKeyPem : '',
    })
    keyForm.privateKeyPem = ''
    await request
    if (current !== attempt) return
    message.value = 'Key pair saved.'
    keyForm.alias = ''
    await loadEntries()
  } catch (failure) {
    if (current !== attempt) return
    if (failure?.status === 401 || failure?.status === 403) emit('authorization-error')
    else error.value = [400, 409].includes(failure?.status) ? failure.message : 'Could not save key material.'
  } finally {
    if (current === attempt) {
      creating.value = false
      keyForm.privateKeyPem = ''
    }
  }
}

watch(() => keyForm.operation, () => { keyForm.privateKeyPem = '' })

function refresh() {
  ++attempt
  loadEntries()
  loadConfiguration()
}

watch(() => props.token, () => {
  entries.value = []
  renewal.value = null
  keyForm.privateKeyPem = ''
  creating.value = false
  issuing.value = false
  message.value = ''
  refresh()
}, { immediate: true })
onBeforeUnmount(() => { attempt += 1 })
</script>

<template>
  <section class="panel content-panel">
    <div class="content-toolbar">
      <p>Key material <span>Certificates and public key information in Orion’s protected store</span></p>
      <div class="material-actions">
        <button class="secondary-button" :disabled="loading || issuing || creating || configurationLoading"
          @click="refresh">Refresh</button>
      </div>
    </div>
    <form class="acme-form" @submit.prevent="saveConfiguration(true)">
      <fieldset :disabled="loading || issuing || creating || configurationLoading || !configured">
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
          <label>Account key
            <select v-model="accountAlias" aria-label="ACME account key">
              <option value="">Automatic</option>
              <option v-for="key in accountKeys" :key="key.alias" :value="key.alias">
                {{ key.alias }} · version {{ key.version }}
              </option>
              <option v-if="form.accountMaterial && !accountKeys.some(key => key.alias === accountAlias)"
                :value="accountAlias" disabled>{{ accountAlias }} (unavailable)</option>
            </select>
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
        <p>The account key identifies your ACME account. Automatic reuses the current account for unchanged
          provider settings, or creates a key for a new account. Manage keys below to reuse an existing account.</p>
        <p>By issuing a certificate, you confirm acceptance of the selected provider’s terms of service.</p>
        <div class="material-actions">
          <button type="button" class="secondary-button" @click="saveConfiguration(false)">Save settings</button>
          <button type="submit" class="primary-button" aria-label="Issue ACME certificate">
            {{ issuing ? 'Working…' : 'Issue ACME certificate' }}
          </button>
        </div>
      </fieldset>
    </form>
    <form class="key-form" aria-label="Create key material" @submit.prevent="createKey">
      <fieldset :disabled="loading || issuing || creating || configurationLoading">
        <legend>Create key material</legend>
        <div class="acme-fields">
          <label>Operation
            <select v-model="keyForm.operation" aria-label="Key operation">
              <option value="generate">Generate key pair</option>
              <option value="import">Import private key</option>
            </select>
          </label>
          <label>Key name
            <input v-model="keyForm.alias" aria-label="Key name" required maxlength="128"
              pattern="[a-z0-9][a-z0-9._-]*" placeholder="acme-account">
          </label>
          <label>Purpose
            <select v-model="keyForm.purpose" aria-label="Key purpose">
              <option value="ACME_ACCOUNT">ACME account</option>
              <option value="TLS_IDENTITY">HTTPS certificate</option>
            </select>
          </label>
        </div>
        <label v-if="keyForm.operation === 'import'" class="private-key-input">Private key PEM
          <textarea v-model="keyForm.privateKeyPem" aria-label="Private key PEM" required rows="5"
            maxlength="16384" autocomplete="off" spellcheck="false" />
        </label>
        <p v-if="keyForm.operation === 'import'">Import an unencrypted RSA private key in PKCS#1 or PKCS#8 PEM
          format (2048–8192 bits). The public key is derived automatically.</p>
        <p v-else>Creates an RSA 3072-bit key pair in the protected store.</p>
        <p>Names use lowercase letters, digits, dots, underscores and hyphens. Existing keys cannot be overwritten.</p>
        <button type="submit" class="secondary-button">{{ creating ? 'Saving…' : 'Save key pair' }}</button>
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
        <p v-if="entry.publicKeySha256Fingerprint">Public key SHA-256:
          <code>{{ entry.publicKeySha256Fingerprint }}</code></p>
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
.acme-form, .key-form { margin-block: 1rem; }
fieldset { min-width: 0; border: 1px solid var(--border, #d6d6d6); border-radius: 8px; padding: 1rem; }
.acme-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: 1rem; }
.acme-fields label { display: grid; gap: .4rem; min-width: 0; }
.acme-fields input, .acme-fields select { width: 100%; min-width: 0; box-sizing: border-box; padding: .6rem; }
.acme-form p, .key-form p { font-size: .85rem; }
.private-key-input { display: grid; gap: .4rem; margin-top: 1rem; }
.private-key-input textarea { width: 100%; min-width: 0; box-sizing: border-box; resize: vertical; }
</style>
