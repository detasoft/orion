import { decodeSequence, getEncoded } from 'cbor2'

const MAX_RECORD_BYTES = 16 * 1024 * 1024 + 4096

export async function followSessionTerminal({ client, sessionId, terminal, signal, onStatus }) {
  let cursor = null
  let retryDelay = 1000
  while (!signal.aborted) {
    onStatus(cursor === null ? 'Connecting…' : 'Reconnecting…')
    let response
    try {
      response = await client.sessionEvents(sessionId, cursor?.toString() ?? null, signal)
    } catch (error) {
      if (signal.aborted) return
      if (error.status && error.status < 500 && error.status !== 408 && error.status !== 429) throw error
    }
    if (signal.aborted) {
      await response?.body?.cancel()
      return
    }
    if (response) {
      if (response.headers.get('content-type')?.split(';')[0].trim() !== 'application/cbor-seq'
          || !response.body) {
        await response.body?.cancel()
        throw new Error('Expected a session journal stream')
      }
      onStatus('Following session')
      const reader = response.body.getReader()
      const cancel = () => { reader.cancel().catch(() => {}) }
      signal.addEventListener('abort', cancel, { once: true })
      let pending = new Uint8Array()
      try {
        while (!signal.aborted) {
          let chunk
          try {
            chunk = await reader.read()
          } catch {
            break
          }
          if (chunk.done || signal.aborted) break
          const bytes = new Uint8Array(pending.length + chunk.value.length)
          bytes.set(pending)
          bytes.set(chunk.value, pending.length)
          const records = []
          let consumed = 0
          try {
            for (const record of decodeSequence(bytes, {
              saveOriginal: true, ignoreGlobalTags: true, maxDepth: 128,
            })) {
              if (!Array.isArray(record)) throw new Error('Invalid session journal record')
              const size = getEncoded(record).length
              if (size > MAX_RECORD_BYTES) throw new Error('Invalid session journal record size')
              consumed += size
              records.push(record)
            }
          } catch (error) {
            if (!(error instanceof RangeError) && !error.message.startsWith('Unexpected end of stream')) {
              throw new Error('Invalid session journal encoding', { cause: error })
            }
          }
          pending = bytes.slice(consumed)
          if (pending.length > MAX_RECORD_BYTES) throw new Error('Invalid session journal record size')
          for (const record of records) {
            if (signal.aborted) return
            if (!Array.isArray(record) || record.length < 3) throw new Error('Invalid session journal record')
            const [id, type, payload] = record
            if ((typeof id !== 'bigint' && !Number.isSafeInteger(id))
                || id < 0 || id > 18446744073709551615n
                || !Number.isInteger(type) || type < 0 || type > 65535) {
              throw new Error('Invalid session event identity')
            }
            const eventId = BigInt(id)
            if (cursor !== null && eventId <= cursor) continue
            switch (type) {
              case 0x0100:
                if (!(payload instanceof Uint8Array)) throw new Error('Invalid terminal output')
                await writeTerminal(terminal, payload, signal)
                break
              case 0x0102:
                if (!Array.isArray(payload) || payload.length < 2
                    || !payload.slice(0, 2).every((size) => Number.isInteger(size) && size > 0 && size <= 65535)) {
                  throw new Error('Invalid terminal size')
                }
                terminal.resize(payload[0], payload[1])
                break
              case 0x0103:
                onStatus('Terminal closed; waiting for session exit')
                break
              case 0x0201:
                if (!Array.isArray(payload) || payload.length < 1 || !Number.isInteger(payload[0])
                    || payload[0] < -2147483648 || payload[0] > 2147483647) {
                  throw new Error('Invalid session exit')
                }
                onStatus(`Session exited (${payload[0]})`)
                return
              case 0x0203:
                onStatus('Session failed to start')
                return
            }
            if (signal.aborted) return
            cursor = eventId
            retryDelay = 1000
          }
        }
      } finally {
        signal.removeEventListener('abort', cancel)
        await reader.cancel().catch(() => {})
        reader.releaseLock()
      }
    }
    if (signal.aborted) return
    onStatus('Connection interrupted; reconnecting…')
    await new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer)
        signal.removeEventListener('abort', done)
        resolve()
      }
      const timer = setTimeout(done, retryDelay)
      signal.addEventListener('abort', done, { once: true })
    })
    retryDelay = Math.min(retryDelay * 2, 10000)
  }
}

function writeTerminal(terminal, bytes, signal) {
  return new Promise((resolve, reject) => {
    const done = () => {
      signal.removeEventListener('abort', done)
      resolve()
    }
    signal.addEventListener('abort', done, { once: true })
    try {
      terminal.write(bytes, done)
    } catch (error) {
      signal.removeEventListener('abort', done)
      reject(error)
    }
  })
}
