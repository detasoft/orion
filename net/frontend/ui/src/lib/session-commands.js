export function createSessionCommands({ client, sessionId, signal, onFailure }) {
  const queue = []
  const pending = new Set()
  let sending = false
  let paused = false
  let timer

  signal.addEventListener('abort', () => {
    clearTimeout(timer)
    queue.length = 0
    pending.clear()
  }, { once: true })

  function fail(commandId, detail) {
    if (signal.aborted || paused) return
    paused = true
    queue.length = 0
    clearTimeout(timer)
    onFailure(`Command ${commandId}: ${detail}. Input paused; reopen the session to continue.`)
  }

  function observe(commandId, status) {
    if (status.phase === 'DELIVERY_FAILED'
        || (status.phase === 'CONFIRMED' && status.outcome !== 'SUCCEEDED')) {
      pending.delete(commandId)
      fail(commandId, status.detail || status.outcome || 'Delivery failed')
    } else if (status.phase === 'CONFIRMED') {
      pending.delete(commandId)
    }
  }

  function schedulePoll() {
    if (!timer && pending.size && !signal.aborted && !paused) timer = setTimeout(poll, 1000)
  }

  async function poll() {
    try {
      for (const commandId of pending) {
        if (signal.aborted || paused) break
        try {
          const status = await client.sessionCommandStatus(sessionId, commandId,
            AbortSignal.any([signal, AbortSignal.timeout(10000)]))
          observe(commandId, status)
        } catch (error) {
          fail(commandId, `Status unknown: ${error.message}`)
        }
      }
    } finally {
      timer = undefined
      schedulePoll()
    }
  }

  async function drain() {
    if (sending) return
    sending = true
    try {
      while (queue.length && !signal.aborted && !paused) {
        const command = queue.shift()
        try {
          const status = await client.sendSessionCommand(sessionId, command,
            AbortSignal.any([signal, AbortSignal.timeout(10000)]))
          if (signal.aborted) return
          pending.add(command.commandId)
          observe(command.commandId, status)
          schedulePoll()
        } catch (error) {
          fail(command.commandId, `Delivery not confirmed: ${error.message}`)
        }
      }
    } finally {
      sending = false
    }
  }

  return {
    send(command) {
      if (signal.aborted || paused) return
      if (queue.length + pending.size + Number(sending) >= 64) {
        fail('queue', 'Too many commands await confirmation; queued input was not sent')
        return
      }
      const commandId = Array.from(crypto.getRandomValues(new Uint8Array(16)),
        (byte) => byte.toString(16).padStart(2, '0')).join('')
      queue.push({ ...command, commandId })
      void drain()
    },
  }
}
