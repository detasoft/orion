<script setup>
import { onMounted, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const organizations = ref([])
const organization = ref('')
const scope = ref('organization')
const providers = ref([])
const provider = ref('')
const repositories = ref([])
const access = ref(Object.create(null))
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
    providers.value = response.systemProviders ?? []
    provider.value = providers.value[0] ?? ''
    repositories.value = (response.repositories ?? []).map(name => ({ name }))
  } catch (failure) { failed(failure) }
})

watch(scope, () => {
  invitation.value = null
  copied.value = false
  error.value = ''
  access.value = Object.create(null)
})

watch([email, provider, access], () => {
  invitation.value = null
  copied.value = false
}, { deep: true })

async function invite() {
  busy.value = true
  error.value = ''
  invitation.value = null
  copied.value = false
  try {
    const input = scope.value === 'system'
      ? { scope: 'system', email: email.value.trim(), provider: provider.value,
        repositories: repositories.value.filter(item => access.value[item.name])
          .map(item => ({ repository: item.name, readWrite: access.value[item.name] === 'readWrite' })) }
      : { organization: organization.value, email: email.value.trim() }
    invitation.value = await api.invite(input)
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
    <p>Choose the account scope and send the generated link to the invited email address.</p>
    <form @submit.prevent="invite">
      <label>Invitation scope
        <select v-model="scope" name="scope" :disabled="busy">
          <option value="organization">Organization member</option>
          <option value="system">System user</option>
        </select>
      </label>
      <label v-if="scope === 'organization'">Organization
        <select v-model="organization" name="organization" required :disabled="busy">
          <option v-for="item in organizations" :key="item.id" :value="item.id" :disabled="!item.oidcConfigured">
            {{ item.name }} ({{ item.id }}){{ item.oidcConfigured ? '' : ' — configure OIDC first' }}
          </option>
        </select>
      </label>
      <template v-else>
        <label>Invitation provider
          <select v-model="provider" name="provider" required :disabled="busy">
            <option v-for="item in providers" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
        <p v-if="!providers.length">Configure a system OIDC provider first.</p>
        <fieldset :disabled="busy">
          <legend>Initial repository access</legend>
          <p>No access is granted unless selected. Administrative permissions are not available here.</p>
          <label v-for="item in repositories" :key="item.name">{{ item.name }}
            <select v-model="access[item.name]" :name="`access-${item.name}`">
              <option :value="undefined">No access</option>
              <option value="read">READ</option>
              <option value="readWrite">READ+WRITE</option>
            </select>
          </label>
        </fieldset>
      </template>
      <label>Email <input v-model="email" type="email" required maxlength="254" :disabled="busy" /></label>
      <button class="primary-button" :disabled="busy || (scope === 'system' ? !provider : !organization)">
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
