<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const renewal = ref(null)
const cleanup = ref(null)
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
  cleanup.value = null
  error.value = ''
  busy.value = !!props.token
  if (!props.token) return
  try {
    const client = createOrionClient({ token: props.token })
    const [acmeResult, tasksResult] = await Promise.allSettled([
      client.acmeConfiguration(), client.recurringTasks(),
    ])
    if (current === revision) {
      const denied = [acmeResult, tasksResult].find(response => response.status === 'rejected' &&
        [401, 403].includes(response.reason.status))
      if (denied) {
        error.value = denied.reason.message || 'Authorization failed.'
        emit('authorization-error')
        return
      }
      if (acmeResult.status === 'fulfilled') {
        renewal.value = acmeResult.value.renewal
        if (!renewal.value) error.value = 'Task status is unavailable.'
      } else {
        error.value = acmeResult.reason.message || 'Could not load certificate renewal.'
      }
      if (tasksResult.status === 'fulfilled') {
        cleanup.value = tasksResult.value.gitPackCleanup
      } else {
        error.value ||= tasksResult.reason.message || 'Could not load Git cleanup status.'
      }
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
    <article v-if="cleanup" aria-label="Git pack cleanup">
      <div class="task-toolbar">
        <div>
          <h4>Git pack cleanup</h4>
          <code>git-pack-cleanup</code>
        </div>
        <a class="secondary-button" href="#/logs?task=git-pack-cleanup">View logs</a>
      </div>
      <dl>
        <div><dt>Status</dt><dd>{{ states[cleanup.state] || cleanup.state }}</dd></div>
        <div><dt>Last attempt</dt><dd>
          <time v-if="cleanup.lastAttempt" :datetime="cleanup.lastAttempt">{{ cleanup.lastAttempt }}</time>
          <span v-else>Not recorded</span>
        </dd></div>
        <div><dt>Next attempt</dt><dd>
          <time v-if="cleanup.nextAttempt" :datetime="cleanup.nextAttempt">{{ cleanup.nextAttempt }}</time>
          <span v-else>Not scheduled</span>
        </dd></div>
        <div><dt>Last run</dt><dd>{{ cleanup.deleted }} deleted, {{ cleanup.observed }} observed,
          {{ cleanup.skipped }} skipped</dd></div>
      </dl>
      <p v-if="cleanup.message" role="alert">{{ cleanup.message }}</p>
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
