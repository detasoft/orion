<script setup>
import { onMounted, ref } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ invitation: { type: String, default: '' }, ticket: { type: String, default: '' },
  organization: { type: String, default: 'default' } })
const emit = defineEmits(['signed-in', 'close'])
const api = createOrionClient()
const organization = ref(props.organization)
const providers = ref([])
const provider = ref('')
const loadedOrganization = ref('')
const profile = ref(null)
const busy = ref(false)
const error = ref('')

async function perform(action) {
  busy.value = true
  error.value = ''
  try { await action() }
  catch (failure) { error.value = failure.message || 'Sign-in failed. Please start again.' }
  finally { busy.value = false }
}

function loadProviders() {
  return perform(async () => {
    providers.value = []
    provider.value = ''
    const selectedOrganization = organization.value.trim()
    const result = await api.providers(selectedOrganization)
    loadedOrganization.value = selectedOrganization
    providers.value = result.providers
    provider.value = result.providers[0] ?? ''
    if (!provider.value) error.value = 'No sign-in provider is configured for this organization.'
  })
}

function start() {
  return perform(async () => {
    const response = await api.beginOidc({ organization: loadedOrganization.value,
      provider: provider.value, invitation: props.invitation })
    window.location.assign(response.url)
  })
}

function complete() {
  return perform(async () => {
    const result = await api.completeOidc({ ticket: props.ticket,
      first: profile.value.first, last: profile.value.last })
    emit('signed-in', result)
  })
}

onMounted(() => {
  if (props.ticket) {
    perform(async () => { profile.value = await api.oidcProfile(props.ticket) })
  } else if (props.invitation) loadProviders()
})
</script>

<template>
  <div class="modal-backdrop">
    <section class="modal sign-in" aria-label="Organization sign-in">
      <button class="close-button" aria-label="Close sign-in" @click="emit('close')">×</button>
      <h2>{{ profile?.setup ? 'Set up your profile' : 'Sign in to your organization' }}</h2>
      <form v-if="profile" @submit.prevent="complete">
        <p>{{ profile.email }}</p>
        <template v-if="profile.setup">
          <label>First name <input v-model="profile.first" required maxlength="100" /></label>
          <label>Last name <input v-model="profile.last" maxlength="100" /></label>
        </template>
        <button class="primary-button" :disabled="busy">Continue</button>
      </form>
      <template v-else-if="!ticket">
        <form @submit.prevent="loadProviders">
          <label>Organization <input v-model="organization" required :readonly="!!invitation" /></label>
          <button class="secondary-button" :disabled="busy">Find sign-in providers</button>
        </form>
        <form v-if="providers.length && organization.trim() === loadedOrganization" @submit.prevent="start">
          <label>Sign-in provider
            <select v-model="provider"><option v-for="item in providers" :key="item">{{ item }}</option></select>
          </label>
          <button class="primary-button" :disabled="busy">Sign in</button>
        </form>
      </template>
      <p v-if="busy" role="status">Please wait…</p>
      <p v-if="error" role="alert">{{ error }}</p>
    </section>
  </div>
</template>

<style scoped>
.sign-in { position: relative; padding: 32px; max-width: 480px; }
form, label { display: grid; gap: 12px; }
form { margin-top: 20px; }
input, select { padding: 10px; border: 1px solid #a8b0a9; border-radius: 6px; }
button { justify-self: start; }
</style>
