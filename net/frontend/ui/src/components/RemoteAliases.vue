<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const aliases = ref([])
const loading = ref(false)
const failed = ref(false)
let attempt = 0

const statusLabels = {
  'not-checked': 'Not checked',
  success: 'Success',
  unavailable: 'Unavailable',
  'authentication-failed': 'Authentication failed',
  conflict: 'Conflict',
}

async function loadAliases() {
  const currentAttempt = ++attempt
  aliases.value = []
  loading.value = true
  failed.value = false
  try {
    const response = await createOrionClient({ token: props.token }).remoteAliases()
    if (currentAttempt === attempt) aliases.value = response.aliases
  } catch (error) {
    if (currentAttempt !== attempt) return
    failed.value = true
    if (error?.status === 401 || error?.status === 403) emit('authorization-error')
  } finally {
    if (currentAttempt === attempt) loading.value = false
  }
}

watch(() => props.token, loadAliases, { immediate: true })
onBeforeUnmount(() => { attempt += 1 })
</script>

<template>
  <section class="panel content-panel">
    <div class="content-toolbar">
      <p>Remote aliases <span>Transparent Git proxies</span></p>
      <button class="primary-button" :disabled="loading" @click="loadAliases">Refresh list</button>
    </div>
    <p class="proxy-description">
      Reads refresh from the upstream. Writes complete after the upstream accepts them.
      Repository mirrors synchronize asynchronously and are configured separately.
    </p>
    <div v-if="loading" class="empty-state" role="status">Loading remote aliases…</div>
    <div v-else-if="failed" class="empty-state" role="alert">Could not load remote aliases.</div>
    <div v-else-if="!aliases.length" class="empty-state">No remote aliases configured.</div>
    <div v-else class="proxy-list">
      <article v-for="alias in aliases" :key="`${alias.scope}/${alias.alias}`" class="proxy-row">
        <h3>{{ alias.alias }}</h3>
        <dl>
          <dt>Scope</dt><dd>{{ alias.scope }}</dd>
          <dt>Upstream</dt><dd>{{ alias.upstream }}</dd>
          <dt>Transport</dt><dd>{{ alias.transport }}</dd>
          <dt>Selected ref</dt><dd><code>{{ alias.ref }}</code></dd>
          <dt>Last sync result</dt><dd>{{ statusLabels[alias.status] ?? 'Unknown' }}</dd>
          <template v-if="alias.observedAt">
            <dt>Observed at</dt><dd><time :datetime="alias.observedAt">{{ alias.observedAt }}</time></dd>
          </template>
          <dt>Git endpoint</dt><dd>{{ alias.endpoint ?? 'No public Git endpoint' }}</dd>
        </dl>
      </article>
    </div>
  </section>
</template>

<style scoped>
.proxy-description { margin: 0; padding: 0 24px 20px; line-height: 1.6; }
.proxy-row { padding: 20px 24px; border-top: 1px solid var(--border); }
.proxy-row h3 { margin: 0 0 16px; }
.proxy-row dl { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 10px 16px; margin: 0; }
.proxy-row dt { font-weight: 600; }
.proxy-row dd { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 600px) {
  .proxy-row dl { grid-template-columns: 1fr; gap: 6px; }
  .proxy-row dd { margin-bottom: 10px; }
}
</style>
