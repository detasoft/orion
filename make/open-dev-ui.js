const { execFile } = require('node:child_process')
const path = require('node:path')
const { promisify } = require('node:util')
const { setTimeout: delay } = require('node:timers/promises')
const run = promisify(execFile)

async function main() {
  const url = new URL(process.argv.length > 2 ? process.argv.at(-1) : 'http://localhost:4173')
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Use an HTTP(S) URL without credentials for the development UI.')
  }
  url.hash = ''
  const vite = await fetch(new URL('/@vite/client', url), {
    redirect: 'error', signal: AbortSignal.timeout(5000),
  })
  await vite.body?.cancel()
  if (!vite.ok || !vite.headers.get('content-type')?.includes('javascript')) {
    throw new Error('Automatic login requires the Vite development UI; use URL=http://localhost:4173.')
  }
  console.log(`Waiting for Orion behind ${url.origin} (up to 120 seconds)...`)
  const identityUrl = new URL('/api/auth/me', url)
  const deadline = Date.now() + 120000
  while (true) {
    try {
      const response = await fetch(identityUrl, { redirect: 'error', signal: AbortSignal.timeout(3000) })
      await response.body?.cancel()
      if (response.status === 401 || response.ok) break
    } catch { /* The backend may still be compiling or starting. */ }
    if (Date.now() >= deadline) throw new Error('Orion is not ready. Check run-server and retry make open-ui.')
    await delay(500)
  }
  let token = process.env.ORION_TOKEN?.trim()
  if (!token) {
    try {
      const issued = await run('make', ['-s', 'issue-token-raw',
        `ORION_SSH_HOST=${process.env.ORION_SSH_HOST || url.hostname}`,
        `ORION_SSH_PORT=${process.env.ORION_SSH_PORT || '8022'}`], {
        cwd: path.resolve(__dirname, '..'), timeout: 20000,
      })
      token = issued.stdout.trim()
    } catch {
      throw new Error('Cannot issue a token over SSH. Enroll your key with make enroll-admin-key, '
        + 'check ORION_SSH_HOST/ORION_SSH_PORT, or supply ORION_TOKEN; then retry make open-ui.')
    }
  }
  if (!token) throw new Error('Orion returned an empty token.')
  const identity = await fetch(identityUrl, {
    headers: { Authorization: `Bearer ${token}` }, redirect: 'error', signal: AbortSignal.timeout(5000),
  })
  await identity.body?.cancel()
  if (!identity.ok) throw new Error(`Orion rejected the token (HTTP ${identity.status}).`)
  url.hash = new URLSearchParams({ 'dev-token': token }).toString()
  const environment = { ...process.env }
  delete environment.BROWSER
  await new Promise((resolve, reject) => {
    const child = execFile('python3', ['-c',
      'import sys, webbrowser; sys.exit(not webbrowser.open(sys.stdin.read(), new=2))'],
    { env: environment, timeout: 15000 }, error => {
      if (error) reject(new Error('Cannot open the default browser. Check Python 3 and your desktop session.'))
      else resolve()
    })
    child.stdin.on('error', () => {})
    child.stdin.end(url.href)
  })
  console.log(`Opened ${url.origin} with a development login in your default browser.`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
