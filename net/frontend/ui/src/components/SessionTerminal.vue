<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { createOrionClient } from '../lib/orion-api.js'
import { followSessionTerminal } from '../lib/session-terminal.js'
import { createSessionCommands } from '../lib/session-commands.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const sessionId = ref('')
const openedSession = ref('')
const container = ref(null)
const status = ref('')
const commandError = ref('')
const available = ref(false)
const columns = ref(80)
const rows = ref(24)
let active

function detach() {
  active?.abort.abort()
  active?.terminal.dispose()
  active = null
  openedSession.value = ''
  status.value = ''
  commandError.value = ''
  available.value = false
}

function sendBytes(current, bytes) {
  if (active !== current || !available.value || commandError.value) return
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    current.commands.send({
      operation: 'input', bytes: btoa(String.fromCharCode(...bytes.subarray(offset, offset + 8192))),
    })
  }
}

function resize() {
  if (!active || !available.value || commandError.value) return
  active.commands.send({ operation: 'resize', columns: columns.value, rows: rows.value })
}

async function openSession() {
  const id = sessionId.value.trim()
  if (!id) return
  detach()
  const terminal = new Terminal({ disableStdin: true, cursorBlink: false, scrollback: 10000 })
  const current = { terminal, abort: new AbortController() }
  const client = createOrionClient({ token: props.token })
  current.commands = createSessionCommands({
    client, sessionId: id, signal: current.abort.signal,
    onFailure(message, status) {
      if (active !== current) return
      commandError.value = message
      terminal.options.disableStdin = true
      if (status === 401 || status === 403) emit('authorization-error')
    },
  })
  active = current
  openedSession.value = id
  try {
    await nextTick()
    if (active !== current) return
    terminal.open(container.value)
    terminal.onData((data) => sendBytes(current, new TextEncoder().encode(data)))
    terminal.onBinary((data) => sendBytes(current, Uint8Array.from(data, (char) => char.charCodeAt(0))))
    await followSessionTerminal({
      client,
      sessionId: id,
      terminal,
      signal: current.abort.signal,
      onStatus(value) { if (active === current) status.value = value },
      onAvailability(value) {
        if (active !== current) return
        available.value = value
        terminal.options.disableStdin = !value || Boolean(commandError.value)
      },
    })
  } catch (error) {
    if (active !== current) return
    status.value = error.message || 'Could not open session'
    if (error.status === 401 || error.status === 403) emit('authorization-error')
  }
}

watch(() => props.token, detach)
onBeforeUnmount(detach)
</script>

<template>
  <section class="panel content-panel session-terminal">
    <form class="terminal-controls" @submit.prevent="openSession">
      <label>Session ID <input v-model="sessionId" aria-label="Session ID" required /></label>
      <button class="primary-button" :disabled="!sessionId.trim()">Open session</button>
      <button v-if="openedSession" type="button" class="secondary-button" @click="detach">Close</button>
    </form>
    <p v-if="!openedSession">Open a session to view its terminal history and live output.</p>
    <p v-else>Session {{ openedSession }}</p>
    <form v-if="openedSession" class="terminal-controls" @submit.prevent="resize">
      <label>Columns
        <input v-model.number="columns" aria-label="Columns" type="number" min="1" max="65535" required />
      </label>
      <label>Rows
        <input v-model.number="rows" aria-label="Rows" type="number" min="1" max="65535" required />
      </label>
      <button class="secondary-button" :disabled="!available || Boolean(commandError)">Resize terminal</button>
    </form>
    <p role="status">{{ status }}</p>
    <p v-if="commandError" role="alert">{{ commandError }}</p>
    <div v-show="openedSession" ref="container" class="terminal-viewport" aria-label="Session terminal" />
  </section>
</template>

<style scoped>
.session-terminal { padding: 24px; }
.terminal-controls { display: flex; align-items: end; flex-wrap: wrap; gap: 12px; }
.terminal-controls label { display: grid; gap: 8px; }
.terminal-controls input { padding: 8px; border: 1px solid var(--border); border-radius: 6px; }
.terminal-viewport { overflow: auto; background: #000; padding: 12px; }
</style>
