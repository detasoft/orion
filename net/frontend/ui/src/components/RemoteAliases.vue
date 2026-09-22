<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const aliases = ref([])
const loading = ref(false)
const failed = ref(false)
const revision = ref(null)
const saving = ref(false)
const message = ref('')
const editor = ref(null)
const busy = computed(() => loading.value || saving.value)
const needsHttpUsername = computed(() => {
  const draft = editor.value
  if (draft?.credentialKind !== 'PASSWORD') return false
  const upstream = draft.upstream || aliases.value.find(alias => alias.alias === draft.alias)?.upstream || ''
  return /^https?:/i.test(upstream)
})
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
  revision.value = null
  loading.value = true
  failed.value = false
  try {
    const response = await createOrionClient({ token: props.token }).remoteAliases()
    if (currentAttempt === attempt) {
      aliases.value = response.aliases
      revision.value = response.revision
    }
  } catch (error) {
    if (currentAttempt !== attempt) return
    failed.value = true
    if (error?.status === 401 || error?.status === 403) emit('authorization-error')
  } finally {
    if (currentAttempt === attempt) loading.value = false
  }
}

function closeEditor() {
  if (editor.value) editor.value.credential = ''
  editor.value = null
}

function openEditor(action, alias) {
  closeEditor()
  message.value = ''
  editor.value = {
    action, alias: alias?.alias ?? '', upstream: '', ref: alias?.ref ?? 'main',
    credentialKind: action === 'create' ? 'TOKEN' : '',
    username: '', knownHosts: '', credential: '',
  }
}

function mutationError(error) {
  if (error?.status === 409) return 'Configuration changed. Refresh the list and reopen the form.'
  if (error?.status === 400) {
    let code
    try { code = JSON.parse(error.message).status } catch { /* Only known server codes are displayed. */ }
    if (code === 'bootstrap-source-fixed') {
      return 'The upstream and ref of an active bootstrap source cannot be changed.'
    }
    if (code === 'alias-exists') return 'This alias already exists. Choose another name.'
    return 'Check the alias settings and try again. Re-enter the credential if required.'
  }
  return 'Could not complete the operation. Refresh the list to check the current configuration.'
}

async function mutate(command) {
  if (busy.value || !revision.value) return
  const currentAttempt = attempt
  saving.value = true
  message.value = ''
  try {
    const response = await createOrionClient({ token: props.token }).mutateRemoteAlias({
      ...command, scope: 'system', revision: revision.value,
    })
    if (currentAttempt !== attempt) return
    aliases.value = aliases.value.filter((alias) => alias.alias !== response.alias.alias)
    aliases.value.push(response.alias)
    aliases.value.sort((left, right) => left.alias.localeCompare(right.alias))
    revision.value = response.revision
    closeEditor()
    const outcome = response.status === 'saved' ? 'Saved' : 'Retry finished'
    message.value = `${outcome}. ${statusLabels[response.alias.status] ?? 'Unknown sync result'}.`
  } catch (error) {
    if (currentAttempt !== attempt) return
    message.value = mutationError(error)
    if (error?.status === 401 || error?.status === 403) {
      closeEditor()
      aliases.value = []
      revision.value = null
      emit('authorization-error')
    } else if (error?.status === 409) {
      closeEditor()
      // Release this operation before the list request takes ownership of attempt.
      saving.value = false
      await loadAliases()
    }
  } finally {
    delete command.credential
    if (currentAttempt === attempt) saving.value = false
  }
}

function submit() {
  const draft = editor.value
  if (!draft || busy.value) return
  const command = { action: draft.action, alias: draft.alias, ref: draft.ref }
  if (draft.upstream) command.upstream = draft.upstream
  if (draft.knownHosts) command.knownHosts = draft.knownHosts
  if (draft.action !== 'update') {
    if (draft.credentialKind) command.credentialKind = draft.credentialKind
    if (needsHttpUsername.value) command.username = draft.username
    if (draft.credentialKind !== 'NONE') command.credential = draft.credential
  }
  draft.credential = ''
  return mutate(command)
}

watch(() => props.token, () => {
  closeEditor()
  saving.value = false
  message.value = ''
  loadAliases()
}, { immediate: true })
onBeforeUnmount(() => { attempt += 1; closeEditor() })
</script>

<template>
  <section class="panel content-panel">
    <div class="content-toolbar">
      <p>Remote aliases <span>Transparent Git proxies</span></p>
      <div class="proxy-actions">
        <button class="secondary-button" :disabled="busy || !!editor" @click="loadAliases">Refresh list</button>
        <button class="primary-button" :disabled="busy || !revision || !!editor"
          @click="openEditor('create')">Add alias</button>
      </div>
    </div>
    <p class="proxy-description">
      Reads refresh from the upstream. Writes complete after the upstream accepts them.
      Repository mirrors synchronize asynchronously and are configured separately.
    </p>
    <p v-if="message" class="proxy-message" role="status">{{ message }}</p>
    <form v-if="editor" class="proxy-editor" @submit.prevent="submit">
      <h3>{{ editor.action === 'create' ? 'Add alias'
        : editor.action === 'update' ? 'Edit alias' : 'Replace credential' }}</h3>
      <p>System scope · Administrator access</p>
      <fieldset :disabled="busy">
        <label>Alias
          <input v-model="editor.alias" name="alias" required :readonly="editor.action !== 'create'" />
        </label>
        <label>{{ editor.action === 'create' ? 'Upstream URL' : 'New upstream URL (optional)' }}
          <input v-model="editor.upstream" name="upstream" :required="editor.action === 'create'"
            placeholder="https://git.example/repository.git" />
        </label>
        <label>Selected ref<input v-model="editor.ref" name="ref" required /></label>
        <label>SSH known-hosts file URI (optional)
          <input v-model="editor.knownHosts" name="knownHosts" placeholder="file:///path/to/known_hosts" />
        </label>
        <template v-if="editor.action !== 'update'">
          <label>Authentication
            <select v-model="editor.credentialKind" name="credentialKind">
              <option v-if="editor.action !== 'create'" value="">Keep current method</option>
              <option value="TOKEN">HTTP bearer token</option>
              <option value="PASSWORD">Password (HTTP Basic or SSH)</option>
              <option value="PRIVATE_KEY">SSH private key</option>
              <option v-if="editor.action === 'create'" value="NONE">None (local file upstream)</option>
            </select>
          </label>
          <label v-if="needsHttpUsername">HTTP username
            <input v-model="editor.username" name="username" required autocomplete="off" />
          </label>
          <label v-if="editor.credentialKind !== 'NONE'">New credential
            <textarea v-if="!editor.credentialKind || editor.credentialKind === 'PRIVATE_KEY'"
              v-model="editor.credential"
              name="credential" required autocomplete="off" spellcheck="false" rows="4" />
            <input v-else v-model="editor.credential" name="credential" type="password"
              required autocomplete="new-password" />
          </label>
          <p>The credential is write-only and is cleared after submission.</p>
        </template>
        <p v-else>Credentials are retained. Use Replace credential to supply a new value.</p>
        <div class="proxy-actions">
          <button class="primary-button" type="submit" :disabled="!revision">
            {{ saving ? 'Saving…' : 'Save' }}
          </button>
          <button class="secondary-button" type="button" @click="closeEditor">Cancel</button>
        </div>
      </fieldset>
    </form>
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
        <div class="proxy-actions row-actions">
          <button class="secondary-button" :disabled="busy || !!editor || !revision"
            @click="openEditor('update', alias)">Edit</button>
          <button class="secondary-button" :disabled="busy || !!editor || !revision"
            @click="openEditor('replace-credential', alias)">Replace credential</button>
          <button class="secondary-button" :disabled="busy || !!editor || !revision"
            @click="mutate({ action: 'retry', alias: alias.alias })">Retry</button>
        </div>
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
.proxy-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.row-actions { margin-top: 18px; }
.proxy-message { margin: 0; padding: 16px 24px; }
.proxy-editor { padding: 20px 24px; border-top: 1px solid var(--border); }
.proxy-editor h3 { margin-top: 0; }
.proxy-editor fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.proxy-editor label { display: grid; gap: 8px; margin-bottom: 16px; }
.proxy-editor input, .proxy-editor select, .proxy-editor textarea {
  width: 100%; box-sizing: border-box; padding: 10px; font: inherit;
  color: inherit; background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
}
@media (max-width: 600px) {
  .proxy-row dl { grid-template-columns: 1fr; gap: 6px; }
  .proxy-row dd { margin-bottom: 10px; }
}
</style>
