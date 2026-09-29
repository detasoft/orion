export function startServerLogs({ client, onError }) {
  const controller = new AbortController()
  let timer
  let cursor = null
  let interrupted = false

  function stop() {
    controller.abort()
    clearTimeout(timer)
  }

  async function poll() {
    try {
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(15000)])
      const page = await client.serverLogs(cursor, signal)
      if (controller.signal.aborted) return
      if (page.gap) console.warn('Some server logs are no longer available; showing retained records.')
      for (const entry of page.entries) {
        const method = { ERROR: 'error', WARN: 'warn', DEBUG: 'debug', TRACE: 'debug' }[entry.level] ?? 'log'
        console[method](entry.text)
      }
      cursor = page.cursor
      interrupted = false
    } catch (error) {
      if (controller.signal.aborted) return
      if (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) {
        stop()
        onError(error)
        return
      }
      if (!interrupted) console.warn('Server log connection interrupted; retrying.')
      interrupted = true
    }
    if (!controller.signal.aborted) timer = setTimeout(poll, 1000)
  }

  poll()
  return stop
}
