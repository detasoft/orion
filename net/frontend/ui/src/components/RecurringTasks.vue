<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const renewal = ref(null)
const error = ref('')
const busy = ref(false)
let revision = 0
const states = {
  disabled: 'Disabled', stopped: 'Stopped', issuing: 'Issuing certificate',
  awaiting_certificate: 'Waiting for first certificate', retrying: 'Retrying',
  scheduled: 'Scheduled', unavailable: 'Unavailable',
}
const result = computed(() => {
  const status = renewal.value
  if (!status) return ''
  if (status.state === 'issuing') return 'In progress'
  if (status.message) return status.message
  if (!status.lastAttempt) return 'No attempt recorded in this server session'
  return status.lastAttempt === status.lastSuccess ? 'Succeeded' : 'No result available'
})

async function refresh() {
  const current = ++revision
  renewal.value = null
  error.value = ''
  busy.value = !!props.token
  if (!props.token) return
  try {
    const response = await createOrionClient({ token: props.token }).acmeConfiguration()
    if (current === revision) {
      renewal.value = response.renewal
      if (!response.renewal) error.value = 'Task status is unavailable.'
    }
  } catch (failure) {
    if (current !== revision) return
    error.value = failure.message || 'Could not load recurring tasks.'
    if (failure.status === 401 || failure.status === 403) emit('authorization-error')
  } finally {
    if (current === revision) busy.value = false
  }
}

watch(() => props.token, refresh, { immediate: true })
onBeforeUnmount(() => { revision++ })
</script>

<template>
  <section class="panel recurring-tasks" aria-label="Recurring tasks" :aria-busy="busy">
    <div class="task-toolbar">
      <h3>Recurring tasks</h3>
      <button class="secondary-button" :disabled="busy" @click="refresh">Refresh tasks</button>
    </div>
    <p v-if="busy" role="status">Loading tasks…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <article v-if="renewal" aria-label="ACME certificate renewal">
      <div class="task-toolbar">
        <div>
          <h4>Certificate renewal</h4>
          <code>acme-certificate</code>
        </div>
        <a class="secondary-button" href="#/logs?task=acme-certificate">View logs</a>
      </div>
      <dl>
        <div><dt>Status</dt><dd>{{ states[renewal.state] || renewal.state }}</dd></div>
        <div><dt>Last attempt</dt><dd>
          <time v-if="renewal.lastAttempt" :datetime="renewal.lastAttempt">{{ renewal.lastAttempt }}</time>
          <span v-else>Not recorded</span>
        </dd></div>
        <div><dt>Next attempt</dt><dd>
          <time v-if="renewal.nextAttempt" :datetime="renewal.nextAttempt">{{ renewal.nextAttempt }}</time>
          <span v-else>Not scheduled</span>
        </dd></div>
        <div><dt>Latest result</dt><dd>{{ result }}</dd></div>
        <div v-if="renewal.lastSuccess"><dt>Last successful issuance</dt><dd>
          <time :datetime="renewal.lastSuccess">{{ renewal.lastSuccess }}</time>
        </dd></div>
      </dl>
      <p v-if="renewal.activationError" role="alert">{{ renewal.activationError }}</p>
      <p v-if="renewal.state === 'awaiting_certificate'">Issue the first certificate from Key material.</p>
      <p class="task-note">Attempt history covers this server session. Saved logs remain available after restart.</p>
    </article>
  </section>
</template>

<style scoped>
.recurring-tasks { padding: 1.25rem; min-width: 0; }
.task-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
h3, h4 { margin: 0 0 .5rem; }
article { margin-top: 1.25rem; }
dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr)); gap: 1rem; }
dt, .task-note { color: var(--muted); font-size: .85rem; }
dd { margin: .35rem 0 0; overflow-wrap: anywhere; }
[role="alert"] { color: var(--danger, #b42318); }
</style>
