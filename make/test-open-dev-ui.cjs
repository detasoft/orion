const { test } = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { promisify } = require('node:util')
const { execFile } = require('node:child_process')
const run = promisify(execFile)

async function exercise(t, { dev = true, reject = false, warming = false } = {}) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'orion-open-ui-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const requests = []
  const server = http.createServer((req, res) => {
    requests.push({ url: req.url, authorization: req.headers.authorization })
    if (req.url === '/@vite/client') {
      res.writeHead(200, { 'Content-Type': dev ? 'text/javascript' : 'text/html' })
      res.end(dev ? '/* vite */' : '<html>production</html>')
    } else {
      const unavailable = warming && requests.length < 3
      res.writeHead(unavailable ? 503 : req.headers.authorization === 'Bearer synthetic+token' && !reject ? 200 : 401,
        { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ userId: 'root', admin: true }))
    }
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => server.close(resolve)))
  await fs.writeFile(path.join(directory, 'make'), `#!${process.execPath}
require('node:fs').writeFileSync(process.env.TRACE_MAKE, JSON.stringify(process.argv.slice(2)));
console.log('synthetic+token');
`, { mode: 0o755 })
  await fs.writeFile(path.join(directory, 'python3'), `#!${process.execPath}
let input = ''; process.stdin.on('data', chunk => input += chunk);
process.stdin.on('end', () => require('node:fs').writeFileSync(process.env.TRACE_OPEN, input));
`, { mode: 0o755 })
  const url = `http://127.0.0.1:${server.address().port}/`
  const result = await run(process.execPath, [path.join(__dirname, 'open-dev-ui.js'), url], {
    env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, ORION_TOKEN: '',
      TRACE_MAKE: path.join(directory, 'make.json'), TRACE_OPEN: path.join(directory, 'opened'),
      ORION_SSH_HOST: 'ssh.example.test', ORION_SSH_PORT: '2200' },
    timeout: 10000,
  }).catch(error => error)
  return { result, directory, url, requests }
}

test('issues a token, checks the server and opens the normal browser without printing credentials', async t => {
  const { result, directory, url, requests } = await exercise(t, { warming: true })
  assert.equal(result.code, undefined, result.stderr)
  const opened = new URL(await fs.readFile(path.join(directory, 'opened'), 'utf8'))
  assert.equal(opened.origin, new URL(url).origin)
  assert.equal(new URLSearchParams(opened.hash.slice(1)).get('dev-token'), 'synthetic+token')
  assert.ok(requests.some(req => req.authorization === 'Bearer synthetic+token'))
  assert.ok(requests.every(req => !req.url.includes('synthetic')))
  const args = JSON.parse(await fs.readFile(path.join(directory, 'make.json'), 'utf8'))
  assert.ok(args.includes('ORION_SSH_HOST=ssh.example.test'))
  assert.ok(args.includes('ORION_SSH_PORT=2200'))
  assert.ok(!(result.stdout + result.stderr).includes('synthetic+token'))
})

test('rejects a production UI before issuing a token or opening a login link', async t => {
  const { result, directory } = await exercise(t, { dev: false })
  assert.equal(result.code, 1)
  assert.match(result.stderr, /Vite/)
  await assert.rejects(fs.access(path.join(directory, 'make.json')))
  await assert.rejects(fs.access(path.join(directory, 'opened')))
})

test('does not open the browser when the server rejects the token', async t => {
  const { result, directory } = await exercise(t, { reject: true })
  assert.equal(result.code, 1)
  assert.match(result.stderr, /token/i)
  assert.ok(!(result.stdout + result.stderr).includes('synthetic+token'))
  await assert.rejects(fs.access(path.join(directory, 'opened')))
})
