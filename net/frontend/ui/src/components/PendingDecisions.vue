<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const requests = ref([])
const operation = ref(null)
const errorMessage = ref('')
const message = ref('')
let active = null

function begin(kind) {
  const current = new AbortController()
  active = current
  operation.value = kind
  errorMessage.value = ''
  message.value = ''
  return current
}

function finish(current) {
  if (active !== current) return
  active = null
  operation.value = null
}

function clear() {
  active?.abort()
  active = null
  operation.value = null
  requests.value = []
  errorMessage.value = ''
  message.value = ''
}

function reportAuthorizationError(error) {
  if (error?.status === 401 || error?.status === 403) {
    requests.value = []
    emit('authorization-error')
  }
}

async function load() {
  if (operation.value || !props.token) return
  const current = begin('loading')
  requests.value = []
  try {
    const response = await createOrionClient({ token: props.token }).decisions(current.signal)
    if (active === current) requests.value = response.decisions
  } catch (error) {
    if (active !== current) return
    errorMessage.value = 'Could not load pending decisions. Refresh the list to try again.'
    reportAuthorizationError(error)
  } finally {
    finish(current)
  }
}

async function resolve(id, action) {
  if (operation.value || !props.token) return
  const current = begin('answering')
  try {
    await createOrionClient({ token: props.token }).resolveDecision(id, action, current.signal)
    if (active !== current) return
    requests.value = requests.value.filter((request) => request.id !== id)
    message.value = 'Decision recorded.'
  } catch (error) {
    if (active !== current) return
    if (error?.status === 404) {
      requests.value = requests.value.filter((request) => request.id !== id)
      message.value = 'This request is no longer available.'
    } else {
      errorMessage.value = 'Could not record decision. Refresh the list to check its current status.'
      reportAuthorizationError(error)
    }
  } finally {
    finish(current)
  }
}

watch(() => props.token, () => {
  clear()
  load()
}, { immediate: true })
onBeforeUnmount(clear)
</script>

<template>
  <section class="panel content-panel" :aria-busy="operation !== null">
    <div class="content-toolbar">
      <p>Pending decisions <span>Requests awaiting your response</span></p>
      <button class="secondary-button" :disabled="!!operation || !token" @click="load">Refresh list</button>
    </div>
    <p v-if="message" class="decision-message" role="status">{{ message }}</p>
    <p v-if="errorMessage" class="decision-message" role="alert">{{ errorMessage }}</p>
    <div v-if="!token" class="empty-state">Connect to review pending decisions.</div>
    <div v-else-if="operation === 'loading'" class="empty-state" role="status">Loading pending decisions…</div>
    <div v-else-if="!requests.length && !errorMessage" class="empty-state">No pending decisions.</div>
    <div v-else>
      <p v-if="operation === 'answering'" class="decision-message" role="status">Recording decision…</p>
      <article v-for="request in requests" :key="request.id" class="decision-row">
        <h3>{{ request.title }}</h3>
        <p class="decision-description">{{ request.description }}</p>
        <dl>
          <dt>Scope</dt><dd>{{ request.scope }}</dd>
          <dt>Requested at</dt><dd><time :datetime="request.createdAt">{{ request.createdAt }}</time></dd>
        </dl>
        <div class="decision-actions">
          <button v-for="(label, action) in request.actions" :key="action" class="secondary-button"
            :disabled="!!operation" @click="resolve(request.id, action)">{{ label }}</button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.decision-message { margin: 0; padding: 16px 24px; }
.decision-row { padding: 20px 24px; border-top: 1px solid var(--border); overflow-wrap: anywhere; }
.decision-row h3 { margin: 0 0 12px; }
.decision-description { white-space: pre-wrap; line-height: 1.6; }
.decision-row dl { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 10px 16px; margin: 0; }
.decision-row dt { font-weight: 600; }
.decision-row dd { margin: 0; }
.decision-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.decision-actions button { white-space: normal; overflow-wrap: anywhere; max-width: 100%; }
@media (max-width: 600px) {
  .decision-row dl { grid-template-columns: 1fr; gap: 6px; }
  .decision-row dd { margin-bottom: 10px; }
}
</style>
