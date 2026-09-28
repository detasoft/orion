import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { request as httpsRequest } from 'node:https'
import { promisify } from 'node:util'
import { chromium, test, expect } from '@playwright/test'

const run = promisify(execFile)
const orionUrl = process.env.ORION_HTTP_URL ?? 'http://127.0.0.1:8000'
const giteaUrl = 'https://fixture.orion.test:8443'
const token = process.env.ORION_TOKEN
const authorized = { Authorization: `Bearer ${token}` }

async function giteaRequest(method, path, password, data) {
  const ca = await readFile(new URL('../../external-services/.state/step/certs/root_ca.crt', import.meta.url))
  const body = data ? JSON.stringify(data) : ''
  return new Promise((resolve, reject) => {
    const request = httpsRequest(new URL(path, giteaUrl), {
      method, ca, family: 4,
      lookup: (_hostname, _options, callback) => callback(null, '127.0.0.1', 4),
      headers: {
        Authorization: `Basic ${Buffer.from(`fixture:${password}`).toString('base64')}`,
        'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body),
      },
      timeout: 30_000,
    }, response => {
      let content = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { content += chunk })
      response.on('end', () => resolve({ status: response.statusCode, body: content }))
      response.on('error', reject)
    })
    request.on('error', reject)
    request.on('timeout', () => request.destroy(new Error('Gitea request timed out')))
    request.end(body)
  })
}

async function openOrion() {
  expect(token, 'The Java integration runner supplies ORION_TOKEN').toBeTruthy()
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222')
  const page = await browser.contexts()[0].newPage()
  page.on('requestfailed', request => console.log('Request failed:', request.url(), request.failure()?.errorText))
  page.on('response', response => {
    if (response.status() >= 400) console.log('HTTP failure:', response.status(), response.url())
  })
  await page.addInitScript(value => sessionStorage.setItem('orion.ui.token', value), token)
  await page.goto('http://orion.test:8000/')
  await expect(page.getByRole('button', { name: 'orion.test Connected' })).toBeVisible()
  return { browser, page }
}

async function remoteRefs(path) {
  const result = await run('git', ['--config-env=http.extraHeader=ORION_AUTH_HEADER',
    'ls-remote', `${orionUrl}${path}`], {
    timeout: 30_000,
    env: { ...process.env, ORION_AUTH_HEADER: `Authorization: Bearer ${token}`, GIT_TERMINAL_PROMPT: '0' },
  })
  return result.stdout
}

test('administrator creates a local repository in Orion', async ({ request }) => {
  const { browser, page } = await openOrion()
  const name = 'playwright-local'
  try {
    await page.getByRole('button', { name: 'New repository' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a repository' })
    await dialog.getByLabel('Repository name').fill(name)
    await dialog.getByRole('button', { name: 'Create repository' }).click()
    await expect(page.getByText('Repository created', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Repositories', exact: true }).click()
    await expect(page.getByText(name, { exact: true })).toBeVisible()

    const listing = await request.get(`${orionUrl}/api/admin/repositories`, { headers: authorized })
    expect(listing.status()).toBe(200)
    expect((await listing.json()).repositories.map(repository => repository.name)).toContain(name)
    expect(await remoteRefs(`/r/${name}.git`)).toBe('')

    await page.reload()
    await page.getByRole('button', { name: 'Repositories', exact: true }).click()
    await expect(page.getByText(name, { exact: true })).toBeVisible()
  } finally {
    await browser.close()
  }
})

test('administrator connects an HTTPS Git proxy to private Gitea', async ({ request }) => {
  const credentialsPath = new URL('../../external-services/.state/credentials.env', import.meta.url)
  const credentials = await readFile(credentialsPath, 'utf8')
  const password = credentials.match(/^GITEA_PASSWORD=(.+)$/m)?.[1]
  expect(password, 'The fixture supplies GITEA_PASSWORD').toBeTruthy()
  const name = `orion-proxy-${randomUUID().slice(0, 8)}`
  const created = await giteaRequest('POST', '/api/v1/user/repos', password,
    { name, private: true, auto_init: true, default_branch: 'main' })
  expect(created.status).toBe(201)
  let browser
  try {
    const branch = await giteaRequest('GET', `/api/v1/repos/fixture/${name}/branches/main`, password)
    expect(branch.status).toBe(200)
    const expectedCommit = JSON.parse(branch.body).commit.id
    const opened = await openOrion()
    browser = opened.browser
    const page = opened.page
    await page.getByRole('button', { name: 'Remote aliases', exact: true }).click()
    await page.getByRole('button', { name: 'Add alias', exact: true }).click()
    const form = page.locator('.proxy-editor')
    await form.getByLabel('Alias', { exact: true }).fill('gitea')
    await form.getByLabel('Upstream URL', { exact: true }).fill(`${giteaUrl}/fixture/${name}.git`)
    await form.getByLabel('Selected ref').fill('main')
    await form.getByLabel('Authentication').selectOption('PASSWORD')
    await form.getByLabel('HTTP username').fill('fixture')
    await form.getByLabel('New credential').fill(password)
    await form.getByRole('button', { name: 'Save', exact: true }).click()
    const alias = page.locator('.proxy-row').filter({ has: page.getByRole('heading', { name: 'gitea' }) })
    await expect(alias.getByText('Success', { exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(alias.getByText('/r/proxy/system/gitea.git', { exact: true })).toBeVisible()
    expect(await remoteRefs('/r/proxy/system/gitea.git')).toContain(`${expectedCommit}\trefs/heads/main`)

    const listing = await request.get(`${orionUrl}/api/admin/proxies`, { headers: authorized })
    expect(listing.status()).toBe(200)
    expect(await listing.text()).not.toContain(password)
    await page.reload()
    await page.getByRole('button', { name: 'Remote aliases', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'gitea', exact: true })).toBeVisible()
  } finally {
    if (browser) await browser.close()
    await giteaRequest('DELETE', `/api/v1/repos/fixture/${name}`, password)
  }
})
