<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const api = createOrionClient({ token: props.token })
const users = ref([])
const selected = ref('')
const revision = ref('')
const bindings = ref([])
const busy = ref(false)
const error = ref('')
const saved = ref(false)
const user = computed(() => users.value.find((item) => item.id === selected.value))

function reset() {
  bindings.value = (user.value?.bindings ?? []).map((binding) => ({ ...binding }))
  saved.value = false
  error.value = ''
}
watch(selected, reset)

function failed(failure) {
  error.value = failure.message || 'Could not load or save bindings.'
  if (failure.status === 401 || failure.status === 403) emit('authorization-error')
}

async function load() {
  const result = await api.systemUsers()
  users.value = result.users
  revision.value = result.revision
  if (!users.value.some((item) => item.id === selected.value)) selected.value = users.value[0]?.id ?? ''
  reset()
}

async function reload() {
  busy.value = true
  error.value = ''
  try { await load() }
  catch (failure) { failed(failure) }
  finally { busy.value = false }
}

async function save() {
  busy.value = true
  error.value = ''
  saved.value = false
  try {
    await api.saveSystemOidcBindings({ id: selected.value, revision: revision.value,
      bindings: bindings.value.map((binding) => ({ issuer: binding.issuer.trim(), subject: binding.subject })) })
    await load()
    saved.value = true
  } catch (failure) { failed(failure) }
  finally { busy.value = false }
}

onMounted(reload)
</script>

<template>
  <section class="panel content-panel system-bindings">
    <h3>System user sign-in</h3>
    <p>Link an existing user to the exact issuer and subject supplied by your identity provider.
      Email does not link accounts. Existing roles and permissions are preserved.</p>
    <form v-if="users.length" @submit.prevent="save">
      <label>Existing system user
        <select v-model="selected" name="user" :disabled="busy" required>
          <option v-for="item in users" :key="item.id" :value="item.id">{{ item.id }}</option>
        </select>
      </label>
      <fieldset v-for="(binding, index) in bindings" :key="index" :disabled="busy">
        <legend>OIDC binding {{ index + 1 }}</legend>
        <label>Issuer URL <input v-model="binding.issuer" name="issuer" type="url" required /></label>
        <label>Subject <input v-model="binding.subject" name="subject" required maxlength="512" /></label>
        <button type="button" class="secondary-button" @click="bindings.splice(index, 1)">Remove binding</button>
      </fieldset>
      <button type="button" class="secondary-button" :disabled="busy || bindings.length >= 64"
        @click="bindings.push({ issuer: '', subject: '' })">Add binding</button>
      <p>Removing a binding revokes its OIDC sessions and tokens after saving.</p>
      <button class="primary-button" :disabled="busy || !user || !revision">Save bindings</button>
    </form>
    <p v-else-if="!busy">No existing system users are available.</p>
    <p v-if="saved" role="status">Bindings saved.</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button name="reload" class="secondary-button" :disabled="busy" @click="reload">Reload users</button>
  </section>
</template>

<style scoped>
.system-bindings { max-width: 720px; }
form, label, fieldset { display: grid; gap: 10px; }
form { gap: 18px; margin: 24px 0; }
input, select { padding: 10px; border: 1px solid #a8b0a9; border-radius: 6px; width: 100%; }
fieldset { min-width: 0; border: 1px solid #a8b0a9; border-radius: 6px; padding: 16px; }
button { justify-self: start; }
</style>
