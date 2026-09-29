import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { request as httpsRequest } from 'node:https'
import { promisify } from 'node:util'
import { browserUrl, orionUrl, test, expect } from './fixtures.js'

const run = promisify(execFile)
const giteaUrl = 'https://fixture.orion.test:8443'

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

async function openOrion(page, step) {
  page.on('requestfailed', request => {
    console.log('Request failed:', request.url(), request.failure()?.errorText)
  })
  page.on('response', response => {
    if (response.status() >= 400) console.log('HTTP failure:', response.status(), response.url())
  })
  await step('Открыть Orion', null, () => page.goto(browserUrl))
  await expect(page.getByRole('button', { name: `${new URL(browserUrl).hostname} Connected` })).toBeVisible()
}

async function remoteRefs(path, token) {
  const result = await run('git', ['--config-env=http.extraHeader=ORION_AUTH_HEADER',
    'ls-remote', `${orionUrl}${path}`], {
    timeout: 30_000,
    env: { ...process.env, ORION_AUTH_HEADER: `Authorization: Bearer ${token}`, GIT_TERMINAL_PROMPT: '0' },
  })
  return result.stdout
}

test('administrator creates a local repository in Orion', async ({ request, page, adminToken, step }) => {
  await openOrion(page, step)
  const name = `playwright-local-${randomUUID().slice(0, 8)}`
  await step('Открыть создание репозитория', page.getByRole('button', { name: 'New repository' }),
    target => target.click())
  const dialog = page.getByRole('dialog', { name: 'Create a repository' })
  await step('Ввести имя репозитория', dialog.getByLabel('Repository name'), target => target.fill(name))
  await step('Создать репозиторий', dialog.getByRole('button', { name: 'Create repository' }),
    target => target.click())
  await expect(page.getByText('Repository created', { exact: true })).toBeVisible()
  await step('Открыть список репозиториев', page.getByRole('button', { name: 'Repositories', exact: true }),
    target => target.click())
  await expect(page.getByText(name, { exact: true })).toBeVisible()

  const listing = await request.get(`${orionUrl}/api/admin/repositories`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(listing.status()).toBe(200)
  expect((await listing.json()).repositories.map(repository => repository.name)).toContain(name)
  expect(await remoteRefs(`/r/${name}.git`, adminToken)).toBe('')

  await step('Обновить страницу и проверить сохранение репозитория', null, () => page.reload())
  await step('Снова открыть список репозиториев', page.getByRole('button', { name: 'Repositories', exact: true }),
    target => target.click())
  await expect(page.getByText(name, { exact: true })).toBeVisible()
})

test('administrator connects an HTTPS Git proxy to private Gitea', async ({ request, page, adminToken, step }) => {
  const credentialsPath = new URL('../../external-services/.state/credentials.env', import.meta.url)
  const credentials = await readFile(credentialsPath, 'utf8')
  const password = credentials.match(/^GITEA_PASSWORD=(.+)$/m)?.[1]
  expect(password, 'The fixture supplies GITEA_PASSWORD').toBeTruthy()
  const name = `orion-proxy-${randomUUID().slice(0, 8)}`
  const aliasName = `gitea-${randomUUID().slice(0, 8)}`
  const proxyPath = `/r/proxy/system/${aliasName}.git`
  const created = await giteaRequest('POST', '/api/v1/user/repos', password,
    { name, private: true, auto_init: true, default_branch: 'main' })
  expect(created.status).toBe(201)
  try {
    const branch = await giteaRequest('GET', `/api/v1/repos/fixture/${name}/branches/main`, password)
    expect(branch.status).toBe(200)
    const expectedCommit = JSON.parse(branch.body).commit.id
    await openOrion(page, step)
    await step('Открыть удалённые репозитории', page.getByRole('button', { name: 'Remote aliases', exact: true }),
      target => target.click())
    await step('Добавить подключение к Gitea', page.getByRole('button', { name: 'Add alias', exact: true }),
      target => target.click())
    const form = page.locator('.proxy-editor')
    await step('Ввести имя подключения', form.getByLabel('Alias', { exact: true }),
      target => target.fill(aliasName))
    await step('Указать URL репозитория Gitea', form.getByLabel('Upstream URL', { exact: true }),
      target => target.fill(`${giteaUrl}/fixture/${name}.git`))
    await step('Выбрать ветку main', form.getByLabel('Selected ref'), target => target.fill('main'))
    await step('Выбрать вход по паролю', form.getByLabel('Authentication'),
      target => target.selectOption('PASSWORD'))
    await step('Ввести пользователя Gitea', form.getByLabel('HTTP username'), target => target.fill('fixture'))
    await step('Ввести пароль Gitea', form.getByLabel('New credential'), target => target.fill(password))
    await step('Сохранить подключение', form.getByRole('button', { name: 'Save', exact: true }),
      target => target.click())
    const alias = page.locator('.proxy-row').filter({ has: page.getByRole('heading', { name: aliasName }) })
    await expect(alias.getByText('Success', { exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(alias.getByText(proxyPath, { exact: true })).toBeVisible()
    expect(await remoteRefs(proxyPath, adminToken)).toContain(`${expectedCommit}\trefs/heads/main`)

    const listing = await request.get(`${orionUrl}/api/admin/proxies`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(listing.status()).toBe(200)
    expect(await listing.text()).not.toContain(password)
    await step('Обновить страницу и проверить сохранение подключения', null, () => page.reload())
    await step('Снова открыть удалённые репозитории',
      page.getByRole('button', { name: 'Remote aliases', exact: true }), target => target.click())
    await expect(page.getByRole('heading', { name: aliasName, exact: true })).toBeVisible()
  } finally {
    await giteaRequest('DELETE', `/api/v1/repos/fixture/${name}`, password)
  }
})
