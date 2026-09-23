<script setup>
import { onMounted, ref } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const organizations = ref([])
const organization = ref('')
const email = ref('')
const invitation = ref(null)
const busy = ref(false)
const error = ref('')
const copied = ref(false)
const api = createOrionClient({ token: props.token })

function failed(failure) {
  error.value = failure.message || 'Could not create invitation'
  if (failure.status === 401 || failure.status === 403) emit('authorization-error')
}

onMounted(async () => {
  try {
    const response = await api.invitations()
    organizations.value = response.organizations
    organization.value = response.organizations.find((item) => item.oidcConfigured)?.id ?? ''
  } catch (failure) { failed(failure) }
})

async function invite() {
  busy.value = true
  error.value = ''
  invitation.value = null
  copied.value = false
  try {
    invitation.value = await api.invite({ organization: organization.value, email: email.value.trim() })
  } catch (failure) { failed(failure) }
  finally { busy.value = false }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(invitation.value.url)
    copied.value = true
  } catch { error.value = 'Could not copy. Select and copy the link below.' }
}
</script>

<template>
  <section class="panel content-panel invitations">
    <h3>Invite a user</h3>
    <p>Select an organization and send the generated link to the invited email address.</p>
    <form @submit.prevent="invite">
      <label>Organization
        <select v-model="organization" required :disabled="busy">
          <option v-for="item in organizations" :key="item.id" :value="item.id" :disabled="!item.oidcConfigured">
            {{ item.name }} ({{ item.id }}){{ item.oidcConfigured ? '' : ' — configure OIDC first' }}
          </option>
        </select>
      </label>
      <label>Email <input v-model="email" type="email" required maxlength="254" :disabled="busy" /></label>
      <button class="primary-button" :disabled="busy || !organization">
        {{ busy ? 'Creating…' : 'Create invitation' }}
      </button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="invitation" role="status">
      <label>Invitation link <input :value="invitation.url" readonly @focus="$event.target.select()" /></label>
      <p>Expires {{ new Date(invitation.expiresAt * 1000).toLocaleString() }}. The link can be used once.</p>
      <button class="secondary-button" @click="copy">{{ copied ? 'Copied' : 'Copy link' }}</button>
    </div>
  </section>
</template>

<style scoped>
.invitations { max-width: 720px; }
form, label { display: grid; gap: 10px; }
form { gap: 18px; margin: 24px 0; }
input, select { padding: 10px; border: 1px solid #a8b0a9; border-radius: 6px; width: 100%; }
button { justify-self: start; }
</style>
