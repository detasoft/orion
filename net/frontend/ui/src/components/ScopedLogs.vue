<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createOrionClient } from '../lib/orion-api.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const scope = ref('tasks')
const files = ref([])
const selected = ref('')
const text = ref('')
const error = ref('')
const busy = ref(false)
const more = ref(false)
const full = ref(false)
const query = ref('')
let searchOffset = 0
const output = ref(null)
const chosen = computed(() => files.value[Number(selected.value)])
let offset = 0
let version = null
let active = null

function cancel() {
  active?.abort()
  active = null
  busy.value = false
}

function clearText() {
  text.value = ''
  offset = 0
  version = null
  more.value = false
  full.value = false
  searchOffset = 0
}

function begin() {
  cancel()
  const current = new AbortController()
  active = current
  busy.value = true
  error.value = ''
  return current
}

function failed(failure) {
  error.value = failure.message || 'Could not read logs.'
  if (failure.status === 401 || failure.status === 403) {
    clearText()
    files.value = []
    emit('authorization-error')
  }
}

async function loadFiles() {
  cancel()
  selected.value = ''
  files.value = []
  clearText()
  if (!props.token) return
  const current = begin()
  try {
    const result = await createOrionClient({ token: props.token }).scopedLogFiles(scope.value, current.signal)
    if (active === current) files.value = result
  } catch (failure) {
    if (active === current) failed(failure)
  } finally {
    if (active === current) busy.value = false
  }
}

async function read(reset = false, nextPart = false) {
  if (selected.value === '' || !chosen.value || !props.token) return
  if (reset) clearText()
  if (nextPart) {
    text.value = ''
    searchOffset = 0
    full.value = false
  }
  const file = chosen.value
  const current = begin()
  try {
    const page = await createOrionClient({ token: props.token }).scopedLog(
      scope.value, file.id, file.file, offset, version, current.signal)
    if (active !== current) return
    text.value += page.text
    offset = page.nextOffset
    version = page.version
    more.value = page.more
    full.value = text.value.length >= 1024 * 1024
  } catch (failure) {
    if (active === current) failed(failure)
  } finally {
    if (active === current) busy.value = false
  }
}

function findNext() {
  if (!query.value || !output.value) return
  const pattern = new RegExp(query.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'giu')
  pattern.lastIndex = searchOffset
  let match = pattern.exec(text.value)
  if (!match) {
    pattern.lastIndex = 0
    match = pattern.exec(text.value)
  }
  searchOffset = match ? match.index + match[0].length : 0
  if (!match) return
  const index = match.index
  const range = document.createRange()
  range.setStart(output.value.firstChild, index)
  range.setEnd(output.value.firstChild, index + match[0].length)
  const selection = window.getSelection()
  selection.removeAllRanges()
  selection.addRange(range)
  const line = text.value.slice(0, index).split('\n').length - 1
  output.value.scrollTop = line * parseFloat(getComputedStyle(output.value).lineHeight)
}

watch([() => props.token, scope], loadFiles, { immediate: true })
watch(query, () => { searchOffset = 0 })
onBeforeUnmount(cancel)
</script>

<template>
  <section class="panel scoped-logs" aria-label="Task and user logs">
    <div class="log-controls">
      <label>Group by
        <select v-model="scope" aria-label="Log scope">
          <option value="tasks">Tasks</option><option value="users">Users</option>
        </select>
      </label>
      <label class="log-selection">Log file
        <select v-model="selected" aria-label="Log file" @change="read(true)">
          <option value="">Select a log file</option>
          <option v-for="(file, index) in files" :key="`${file.id}/${file.file}`" :value="String(index)">
            {{ file.id }} — {{ file.file }} ({{ file.size }} bytes)
          </option>
        </select>
      </label>
      <button type="button" class="button-secondary" @click="loadFiles">Refresh list</button>
    </div>
    <p v-if="!busy && !files.length && !error">No saved logs in this group yet.</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="selected !== '' && files.length">
      <div class="log-controls">
        <button class="button-secondary" :disabled="busy" aria-label="Reload log file" @click="read(true)">
          Reload file
        </button>
        <button class="button-secondary" :disabled="busy || full" aria-label="Read more log text" @click="read()">
          {{ more ? 'Load more' : 'Check for new text' }}
        </button>
        <form class="log-search" @submit.prevent="findNext">
          <input v-model="query" aria-label="Find in loaded log text" placeholder="Find in loaded text" />
          <button class="button-secondary" :disabled="!query">Find next</button>
        </form>
      </div>
      <p v-if="full">Showing 1 MiB of this file.
        <button class="button-secondary" :disabled="busy" @click="read(false, true)">Read next part</button>
      </p>
      <pre ref="output" tabindex="0" aria-label="Log text">{{ text }}</pre>
    </template>
  </section>
</template>

<style scoped>
.scoped-logs { padding: 1rem; min-width: 0; }
.log-controls, .log-search { display: flex; gap: .75rem; align-items: end; flex-wrap: wrap; }
.log-controls { margin-bottom: 1rem; }
.log-controls label { display: grid; gap: .4rem; min-width: 0; }
.log-selection { flex: 1; }
select, input { padding: .5rem; min-width: 0; max-width: 100%; }
pre { max-height: 65vh; overflow: auto; white-space: pre; line-height: 1.5rem; padding: .75rem;
  background: var(--bg, #f5f5f5); font-size: .8rem; }
[role="alert"] { color: var(--danger, #b42318); }
</style>
