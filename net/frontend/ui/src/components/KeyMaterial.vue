<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const entries = ref([])
const loading = ref(false)
const issuing = ref(false)
const message = ref('')
const error = ref('')
let attempt = 0

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

async function issueCertificate() {
  if (issuing.value || loading.value) return
  const current = attempt
  issuing.value = true
  message.value = ''
  error.value = ''
  try {
    await createOrionClient({ token: props.token }).issueAcmeCertificate()
    if (current !== attempt) return
    message.value = 'Certificate issued and saved.'
    await loadEntries()
  } catch (failure) {
    if (current !== attempt) return
    if (failure?.status === 401 || failure?.status === 403) {
      emit('authorization-error')
      return
    }
    error.value = failure?.status === 409 ? 'Certificate issuance is already in progress.'
      : failure?.status === 400 ? failure.message : 'Could not issue the certificate.'
  } finally {
    issuing.value = false
  }
}

watch(() => props.token, () => { entries.value = []; loadEntries() }, { immediate: true })
onBeforeUnmount(() => { attempt += 1 })
</script>

<template>
  <section class="panel content-panel">
    <div class="content-toolbar">
      <p>Key material <span>Certificates and public key information in Orion’s protected store</span></p>
      <div class="material-actions">
        <button class="secondary-button" :disabled="loading || issuing" @click="loadEntries">Refresh</button>
        <button class="primary-button" aria-label="Issue ACME certificate"
          :disabled="loading || issuing" @click="issueCertificate">
          {{ issuing ? 'Issuing…' : 'Issue ACME certificate' }}
        </button>
      </div>
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
