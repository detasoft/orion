<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { createOrionClient } from '../lib/orion-api.js'
import { followSessionTerminal } from '../lib/session-terminal.js'

const props = defineProps({ token: { type: String, required: true } })
const emit = defineEmits(['authorization-error'])
const sessionId = ref('')
const openedSession = ref('')
const container = ref(null)
const status = ref('')
let active

function detach() {
  active?.abort.abort()
  active?.terminal.dispose()
  active = null
  openedSession.value = ''
  status.value = ''
}

async function openSession() {
  const id = sessionId.value.trim()
  if (!id) return
  detach()
  const terminal = new Terminal({ disableStdin: true, cursorBlink: false, scrollback: 10000 })
  const current = { terminal, abort: new AbortController() }
  active = current
  openedSession.value = id
  try {
    await nextTick()
    if (active !== current) return
    terminal.open(container.value)
    await followSessionTerminal({
      client: createOrionClient({ token: props.token }),
      sessionId: id,
      terminal,
      signal: current.abort.signal,
      onStatus(value) { if (active === current) status.value = value },
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
    <p v-else>Session {{ openedSession }} · View only</p>
    <p role="status">{{ status }}</p>
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
