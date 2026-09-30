<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({ client: { type: Object, required: true }, organization: String, admin: Boolean, disabled: Boolean })
const emit = defineEmits(['change'])
const storage = ref('local')
const scope = ref('organization')
const connections = ref([])
const selected = ref('')
const location = ref('')
const revision = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const editor = ref(null)
const draft = ref({})
let generation = 0
const owner = computed(() => scope.value === 'system' ? undefined : props.organization)
const chosen = computed(() => connections.value.find((item) => item.name === selected.value))
const busy = computed(() => loading.value || saving.value)

function closeEditor() {
  draft.value.secretKey = ''
  draft.value.sessionToken = ''
  draft.value = {}
  editor.value = null
}
function edit(create) {
  closeEditor()
  editor.value = create ? 'create' : 'update'
  draft.value = { name: create ? '' : chosen.value.name, endpoint: create ? '' : chosen.value.endpoint,
    region: create ? 'us-east-1' : chosen.value.region, pathStyleAccess: create || chosen.value.pathStyleAccess,
    accessKeyId: create ? '' : chosen.value.accessKeyId, secretKey: '', sessionToken: '', clearToken: false,
    defaultCredentials: props.admin && !create && !chosen.value.credentialsConfigured }
}
function describe(failure) {
  try { return JSON.parse(failure.message).error || failure.message } catch { return failure.message || 'Storage request failed' }
}
async function reload() {
  const request = ++generation
  closeEditor()
  selected.value = ''
  connections.value = []
  revision.value = ''
  error.value = ''
  saving.value = false
  loading.value = false
  if (storage.value !== 's3') return
  if (scope.value === 'organization' && !owner.value) {
    error.value = 'Enter an organization/team/repository name first.'
    return
  }
  loading.value = true
  try {
    const result = await props.client.storageConnections(owner.value)
    if (request !== generation) return
    connections.value = result.connections
    revision.value = result.revision
  } catch (failure) {
    if (request === generation) error.value = describe(failure)
  } finally {
    if (request === generation) loading.value = false
  }
}
async function save() {
  if (!draft.value.name || !draft.value.region
    || !draft.value.defaultCredentials && (!draft.value.accessKeyId || editor.value === 'create' && !draft.value.secretKey)) {
    error.value = 'Enter a connection name, region, access key ID and secret key.'
    return
  }
  const request = generation
  const input = { name: draft.value.name, endpoint: draft.value.endpoint || null, region: draft.value.region,
    pathStyleAccess: draft.value.pathStyleAccess, defaultCredentials: !!draft.value.defaultCredentials }
  if (!input.defaultCredentials) {
    input.accessKeyId = draft.value.accessKeyId || null
    input.secretKey = draft.value.secretKey || null
    input.sessionToken = draft.value.clearToken ? '' : draft.value.sessionToken || null
  }
  draft.value.secretKey = ''
  draft.value.sessionToken = ''
  error.value = ''
  saving.value = true
  try {
    const result = await props.client.saveStorageConnection(owner.value,
      { revision: revision.value, create: editor.value === 'create', connection: input })
    if (request !== generation) return
    connections.value = result.connections
    revision.value = result.revision
    selected.value = result.connections.some((item) => item.name === input.name) ? input.name : ''
    closeEditor()
  } catch (failure) {
    if (request === generation) error.value = describe(failure)
  } finally {
    input.secretKey = null
    input.sessionToken = null
    if (request === generation) saving.value = false
  }
}
watch([storage, scope, owner, () => props.client], reload, { flush: 'sync' })
watch([storage, scope, selected, location, chosen, busy, editor], () => {
  emit('change', storage.value === 'local' ? null
    : !busy.value && !editor.value && chosen.value?.canUse && location.value
      ? { connectionScope: scope.value, connection: selected.value, location: location.value } : false)
}, { immediate: true })
onBeforeUnmount(() => { generation++; closeEditor() })
</script>

<template>
  <fieldset class="repository-storage" :disabled="disabled">
    <label>Storage
      <select v-model="storage" name="storage"><option value="local">Local</option><option value="s3">S3</option></select>
    </label>
    <template v-if="storage === 's3'">
      <p>Git data operations are not implemented yet. S3 repositories currently store metadata only.</p>
      <label v-if="admin">Connection scope
        <select v-model="scope" name="connection-scope">
          <option value="organization">Repository organization</option><option value="system">System</option>
        </select>
      </label>
      <p v-else>Storage connections for {{ organization || 'your organization' }}.</p>
      <p v-if="loading" role="status">Loading connections…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <label>Storage connection
        <select v-model="selected" name="connection" :disabled="busy || !!editor">
          <option value="">Choose a connection</option>
          <option v-for="connection in connections" :key="connection.name" :value="connection.name">
            {{ connection.name }}{{ connection.canUse ? '' : ' (inspection only)' }}
          </option>
        </select>
      </label>
      <div class="storage-actions">
        <button type="button" class="secondary-button"
          data-action="reload-connections" :disabled="busy"
          @click="reload">Reload</button>
        <button type="button" class="secondary-button"
          data-action="add-connection" :disabled="busy || !revision"
          @click="edit(true)">Add connection</button>
        <button type="button" class="secondary-button"
          data-action="edit-connection" :disabled="busy || !chosen?.canChange"
          @click="edit(false)">Edit connection</button>
      </div>
      <div v-if="editor" class="storage-editor">
        <p>Connection creation and modification require separate permissions for this name.</p>
        <label>Connection name
          <input v-model="draft.name" name="connection-name" :readonly="editor === 'update'"
            :disabled="busy" />
        </label>
        <label>Endpoint URL
          <input v-model="draft.endpoint" name="endpoint" placeholder="https://s3.example.com"
            :disabled="busy" />
        </label>
        <label>Region<input v-model="draft.region" name="region" :disabled="busy" /></label>
        <label class="checkbox-row"><input v-model="draft.pathStyleAccess" type="checkbox" :disabled="busy" />Path-style access</label>
        <label v-if="admin" class="checkbox-row">
          <input v-model="draft.defaultCredentials" type="checkbox"
            :disabled="busy" />Use server AWS credentials
        </label>
        <template v-if="!draft.defaultCredentials">
          <label>Access key ID<input v-model="draft.accessKeyId" name="access-key-id" autocomplete="off" :disabled="busy" /></label>
          <label>Secret key
            <input v-model="draft.secretKey" name="secret-key" type="password" autocomplete="new-password"
              :disabled="busy" />
          </label>
          <label>Session token (optional)
            <input v-model="draft.sessionToken" name="session-token" type="password" autocomplete="new-password"
              :disabled="busy || draft.clearToken" />
          </label>
          <label v-if="editor === 'update'" class="checkbox-row">
            <input v-model="draft.clearToken" type="checkbox"
              :disabled="busy" />Remove session token
          </label>
          <p>Credentials are encrypted and never displayed. Leave replacement secrets empty to keep saved values.</p>
        </template>
        <div class="storage-actions">
          <button type="button" class="secondary-button"
            data-action="cancel-connection" :disabled="saving"
            @click="closeEditor">Cancel editing</button>
          <button type="button" class="primary-button"
            data-action="save-connection" :disabled="busy"
            @click="save">{{ saving ? 'Saving…' : 'Save connection' }}</button>
        </div>
      </div>
      <label>S3 location<input v-model="location" name="location" placeholder="s3://bucket/prefix" :disabled="busy" /></label>
    </template>
  </fieldset>
</template>

<style scoped>
.repository-storage { display: grid; gap: 12px; min-width: 0; margin: 15px 0 0; padding: 0; border: 0; }
.repository-storage label { display: block; font-size: 10px; font-weight: 700; }
.repository-storage p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.5; }
.repository-storage select {
  width: 100%; margin-top: 7px; padding: 10px 11px;
  color: var(--text); background: var(--surface-soft); border: 1px solid var(--border);
  border-radius: 8px; outline: 0; font-size: 11px;
}
.repository-storage select:focus {
  border-color: #a7c654; box-shadow: 0 0 0 3px rgb(176 213 76 / 12%);
}
.repository-storage .checkbox-row { display: flex; align-items: center; gap: 8px; }
.storage-editor { display: grid; gap: 12px; padding: 12px; border: 1px solid var(--border); border-radius: 8px; }
.storage-actions { display: flex; flex-wrap: wrap; gap: 8px; }
input[type=checkbox] { width: auto; margin: 0; }
</style>
