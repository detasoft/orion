import { writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const [modulePath, endpoint, sessionId, resultPath, followingPath] = process.argv.slice(2)
const { followSessionTerminal } = await import(pathToFileURL(modulePath))
const { createOrionClient } = await import(new URL('./orion-api.js', pathToFileURL(modulePath)))
const result = { output: '', sizes: [], statuses: [], requests: [], rawBytes: 0 }
const decoder = new TextDecoder()
await followSessionTerminal({
  sessionId,
  signal: AbortSignal.timeout(45000),
  client: createOrionClient({
    baseUrl: endpoint,
    token: 'acceptance-admin',
    async fetchImpl(url, init) {
      const request = new URL(url)
      result.requests.push({ after: request.searchParams.get('after'), follow: request.searchParams.has('follow') })
      const response = await fetch(url, init).catch(error => { console.error(error); throw error })
      return new Response(response.body.pipeThrough(new TransformStream({
        transform(bytes, controller) {
          result.rawBytes += bytes.length
          controller.enqueue(bytes)
        },
      })), { status: response.status, headers: response.headers })
    },
  }),
  terminal: {
    write(bytes, done) { result.output += decoder.decode(bytes, { stream: true }); done() },
    resize(columns, rows) { result.sizes.push([columns, rows]) },
  },
  onStatus(status) {
    result.statuses.push(status)
    if (status === 'Following session') writeFileSync(followingPath, 'following')
  },
})
writeFileSync(resultPath, JSON.stringify(result))
if (!result.statuses.includes('Session exited (0)')) throw new Error('Terminal did not observe exit')
