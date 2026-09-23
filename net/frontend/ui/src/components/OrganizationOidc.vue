<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error', 'saved'])
const api = createOrionClient({ token: props.token })
const organizations = ref([])
const organization = ref('')
const revision = ref('')
const selected = ref('')
const id = ref('')
const issuer = ref('')
const clientId = ref('')
const clientSecret = ref('')
const idleHours = ref(48)
const reauthenticationHours = ref(0)
const busy = ref(false)
const error = ref('')
const saved = ref(false)
const providers = computed(() => organizations.value.find((item) => item.id === organization.value)?.providers ?? [])
const original = computed(() => providers.value.find((item) => item.id === selected.value))
const needsSecret = computed(() => !original.value || original.value.issuer !== issuer.value.trim()
  || original.value.clientId !== clientId.value.trim())

function reset() {
  id.value = original.value?.id ?? ''
  issuer.value = original.value?.issuer ?? ''
  clientId.value = original.value?.clientId ?? ''
  clientSecret.value = ''
  idleHours.value = (original.value?.idleTimeoutSeconds ?? 172800) / 3600
  reauthenticationHours.value = (original.value?.reauthenticationTimeoutSeconds ?? 0) / 3600
  saved.value = false
}
watch(organization, () => { selected.value = ''; reset() })
watch(selected, reset)

function failed(failure) {
  error.value = failure.message || 'Could not load or save providers'
  if (failure.status === 401 || failure.status === 403) emit('authorization-error')
}

async function load() {
  const response = await api.oidcSettings()
  organizations.value = response.organizations
  revision.value = response.revision
  if (!organization.value) organization.value = response.organizations[0]?.id ?? ''
}

async function reload() {
  busy.value = true
  error.value = ''
  clientSecret.value = ''
  try { await load(); reset() }
  catch (failure) { failed(failure) }
  finally { busy.value = false }
}

async function save() {
  busy.value = true
  saved.value = false
  error.value = ''
  const input = { organization: organization.value, revision: revision.value, id: id.value.trim(),
    issuer: issuer.value.trim(), clientId: clientId.value.trim(), clientSecret: clientSecret.value,
    idleTimeoutSeconds: Math.round(Number(idleHours.value) * 3600),
    reauthenticationTimeoutSeconds: Math.round(Number(reauthenticationHours.value) * 3600) }
  clientSecret.value = ''
  try {
    await api.saveOidcProvider(input)
    emit('saved')
    await load()
    selected.value = input.id
    await nextTick()
    reset()
    saved.value = true
  } catch (failure) { failed(failure) }
  finally { input.clientSecret = ''; busy.value = false }
}

onMounted(reload)
</script>

<template>
  <section class="panel content-panel oidc-settings">
    <h3>Organization sign-in</h3>
    <p>Add a Google or corporate OIDC provider before inviting users.</p>
    <form @submit.prevent="save">
      <label>Organization
        <select v-model="organization" :disabled="busy" required>
          <option v-for="item in organizations" :key="item.id" :value="item.id">{{ item.id }}</option>
        </select>
      </label>
      <label>Provider
        <select v-model="selected" :disabled="busy">
          <option value="">Add provider</option>
          <option v-for="item in providers" :key="item.id" :value="item.id">{{ item.id }}</option>
        </select>
      </label>
      <label>Provider ID <input v-model="id" required :readonly="!!selected" :disabled="busy" /></label>
      <label>Issuer URL
        <input v-model="issuer" type="url" required placeholder="https://accounts.google.com" :disabled="busy" />
      </label>
      <label>Client ID <input v-model="clientId" required :disabled="busy" /></label>
      <label>Client secret
        <input v-model="clientSecret" type="password" autocomplete="new-password"
          :required="needsSecret" maxlength="4096" :disabled="busy" />
      </label>
      <p>{{ needsSecret ? 'Enter the secret for this client.' : 'Leave empty to keep the saved secret.' }}
        Secrets are encrypted when saved and are never displayed.</p>
      <label>Inactivity timeout (hours)
        <input v-model.number="idleHours" name="idleHours" type="number" min="0.000278" max="596523"
          step="any" required :disabled="busy" />
      </label>
      <label>Require sign-in every (hours; 0 disables)
        <input v-model.number="reauthenticationHours" name="reauthenticationHours" type="number"
          min="0" max="596523" step="any" required :disabled="busy" />
      </label>
      <p>Timeouts apply separately to each browser session using this provider.</p>
      <button class="primary-button" :disabled="busy || !organization || !revision">Save provider</button>
    </form>
    <p v-if="saved" role="status">Provider saved. You can now invite users to this organization.</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button class="secondary-button" :disabled="busy" @click="reload">Reload providers</button>
  </section>
</template>

<style scoped>
.oidc-settings { max-width: 720px; }
form, label { display: grid; gap: 10px; }
form { gap: 18px; margin: 24px 0; }
input, select { padding: 10px; border: 1px solid #a8b0a9; border-radius: 6px; width: 100%; }
button { justify-self: start; }
</style>
