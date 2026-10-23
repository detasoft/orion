import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { request as httpsRequest } from 'node:https'
import { promisify } from 'node:util'
import { browserUrl, orionUrl, test, expect } from './fixtures.js'

const run = promisify(execFile)
const giteaUrl = 'https://fixture.orion.test:8443'

async function restartOrion(directory, number) {
  await writeFile(join(directory, `restart-${number}.request`), 'restart')
  await expect.poll(async () => {
    try { return await readFile(join(directory, `restart-${number}.ready`), 'utf8') }
    catch (failure) {
      if (failure.code === 'ENOENT') return ''
      throw failure
    }
  }, { timeout: 45_000, message: `Orion completes restart ${number}` }).toBe('ready')
}

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
  await step('Открыть список репозиториев', page.getByRole('link', { name: 'Repositories', exact: true }),
    target => target.click())
  await expect(page.getByText(name, { exact: true })).toBeVisible()

  const listing = await request.get(`${orionUrl}/api/admin/repositories`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(listing.status()).toBe(200)
  expect((await listing.json()).repositories.map(repository => repository.name)).toContain(name)
  expect(await remoteRefs(`/r/${name}.git`, adminToken)).toBe('')

  await step('Обновить страницу и проверить сохранение репозитория', null, () => page.reload())
  await step('Снова открыть список репозиториев', page.getByRole('link', { name: 'Repositories', exact: true }),
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
    await step('Открыть удалённые репозитории', page.getByRole('link', { name: 'Remote aliases', exact: true }),
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
      page.getByRole('link', { name: 'Remote aliases', exact: true }), target => target.click())
    await expect(page.getByRole('heading', { name: aliasName, exact: true })).toBeVisible()
  } finally {
    await giteaRequest('DELETE', `/api/v1/repos/fixture/${name}`, password)
  }
})

test('existing system user signs in through Gitea OIDC and keeps restricted permissions',
  async ({ request, page, browser, adminToken }) => {
    const credentialsPath = new URL('../../external-services/.state/credentials.env', import.meta.url)
    const credentials = await readFile(credentialsPath, 'utf8')
    const password = credentials.match(/^GITEA_PASSWORD=(.+)$/m)?.[1]
    expect(password, 'The fixture supplies GITEA_PASSWORD').toBeTruthy()
    const secureUrl = new URL(browserUrl)
    if (secureUrl.protocol === 'http:') {
      secureUrl.protocol = 'https:'
      secureUrl.port = '9443'
    }
    const origin = secureUrl.origin
    const issuer = `${giteaUrl}/`
    const id = `oidc-${randomUUID().slice(0, 8)}`
    const readable = `${id}-visible`
    const hidden = `${id}-hidden`
    const account = await giteaRequest('GET', '/api/v1/user', password)
    expect(account.status).toBe(200)
    const subject = String(JSON.parse(account.body).id)
    const registered = await giteaRequest('POST', '/api/v1/user/applications/oauth2', password, {
      name: id, confidential_client: true,
      redirect_uris: [`${origin}/api/auth/oidc/callback`], skip_secondary_authorization: false,
    })
    expect(registered.status).toBe(201)
    const application = JSON.parse(registered.body)
    let userContext
    let userPage
    try {
      for (const name of [readable, hidden]) {
        const repository = await request.post(`${orionUrl}/api/admin/repositories`, {
          headers: { Authorization: `Bearer ${adminToken}` }, data: { name },
        })
        expect(repository.status()).toBe(201)
      }
      // The account exists before the UI adds its OIDC credential; it has no admin grant.
      const created = await request.post(`${orionUrl}/api/admin/users`, {
        headers: { Authorization: `Bearer ${adminToken}` },
        data: { id, email: `${id}@example.test`, repositories: [{ repository: readable, read: true }] },
      })
      expect(created.status()).toBe(201)
      await page.addInitScript(({ origin, token }) => {
        if (location.origin === origin) sessionStorage.setItem('orion.ui.token', token)
      }, { origin, token: adminToken })
      await page.goto(origin)
      await page.getByRole('link', { name: 'People', exact: true }).click()
      const provider = page.locator('.oidc-settings')
      await provider.getByLabel('Provider scope').selectOption('system')
      await provider.getByLabel('Provider ID', { exact: true }).fill(id)
      await provider.getByLabel('Issuer URL', { exact: true }).fill(issuer)
      await provider.getByLabel('Client ID', { exact: true }).fill(application.client_id)
      await provider.getByLabel('Client secret', { exact: true }).fill(application.client_secret)
      await provider.getByRole('button', { name: 'Save provider', exact: true }).click()
      await expect(provider.getByRole('status')).toHaveText('Provider saved in the selected scope.')
      const bindings = page.locator('.system-bindings')
      // Reload after provider save so both forms use the current configuration revision.
      await bindings.getByRole('button', { name: 'Reload users' }).click()
      await bindings.getByLabel('Existing system user').selectOption(id)
      await bindings.getByRole('button', { name: 'Add binding', exact: true }).click()
      await bindings.getByLabel('Issuer URL', { exact: true }).fill(issuer)
      await bindings.getByLabel('Subject', { exact: true }).fill(subject)
      await bindings.getByRole('button', { name: 'Save bindings' }).click()
      await expect(bindings.getByRole('status')).toHaveText('Bindings saved.')
      await page.reload()
      await page.getByRole('link', { name: 'People', exact: true }).click()
      await bindings.getByLabel('Existing system user').selectOption(id)
      await expect(bindings.getByLabel('Subject', { exact: true })).toHaveValue(subject)

      // A separate context has neither the injected root token nor a Gitea login cookie.
      userContext = await browser.newContext()
      userPage = await userContext.newPage()
      userPage.on('response', response => {
        const url = new URL(response.url())
        if (url.origin === origin && response.status() >= 400) {
          console.log('OIDC request rejected:', response.status(), url.pathname)
        }
      })
      await userPage.goto(origin)
      await userPage.getByRole('button', { name: 'Sign in with OIDC', exact: true }).click()
      await userPage.getByLabel('Sign-in provider').selectOption(id)
      await userPage.getByRole('button', { name: 'Sign in', exact: true }).click()
      await userPage.locator('#user_name').fill('fixture')
      await userPage.locator('#password').fill(password)
      await userPage.getByRole('button', { name: 'Sign In', exact: true }).click()
      await userPage.getByRole('button', { name: 'Authorize Application', exact: true }).click()
      await userPage.getByRole('button', { name: 'Continue', exact: true }).click()
      await expect(userPage.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
      const token = await userPage.evaluate(() => sessionStorage.getItem('orion.ui.token'))
      expect(token).toBeTruthy()
      expect(token === adminToken).toBe(false)
      const permissions = await userPage.evaluate(async userId => {
        const headers = { Authorization: `Bearer ${sessionStorage.getItem('orion.ui.token')}` }
        const identity = await fetch('/api/auth/me', { headers })
        const repositories = await fetch('/api/admin/repositories', { headers })
        const administration = await fetch('/api/admin/users', { headers })
        const creation = await fetch('/api/admin/repositories', { method: 'POST',
          headers: { ...headers, 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: `${userId}-write` }) })
        return { identity: await identity.json(), repositories: repositories.status,
          names: (await repositories.json()).repositories.map(repository => repository.name),
          administration: administration.status, creation: creation.status }
      }, id)
      expect(permissions.identity).toMatchObject({ userId: id, organization: '', admin: false })
      expect(permissions.repositories).toBe(200)
      expect(permissions.names).toEqual([readable])
      expect(permissions.administration).toBe(403)
      expect(permissions.creation).toBe(403)
      await userPage.getByRole('link', { name: 'Repositories', exact: true }).click()
      await expect(userPage.getByText(readable, { exact: true })).toBeVisible()
      await expect(userPage.getByText(hidden, { exact: true })).toHaveCount(0)
      await userPage.reload()
      await expect(userPage.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
      const renewed = await userPage.evaluate(async userId => {
        const response = await fetch('/api/auth/oidc/refresh', { method: 'POST',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scope: 'system', userId }) })
        const result = await response.json()
        return { status: response.status, scope: result.scope, userId: result.userId }
      }, id)
      expect(renewed).toEqual({ status: 200, scope: 'system', userId: id })
      await userPage.getByRole('button', { name: 'Sign out', exact: true }).click()
      await expect(userPage.getByRole('button', { name: 'Sign in with OIDC', exact: true })).toBeVisible()
      expect(await userPage.evaluate(() => sessionStorage.getItem('orion.ui.token'))).toBeNull()
      const revoked = await userPage.evaluate(async userId => {
        const response = await fetch('/api/auth/oidc/refresh', { method: 'POST',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scope: 'system', userId }) })
        return response.status
      }, id)
      expect(revoked).toBe(401)
    } catch (failure) {
      if (userPage) {
        await test.info().attach('OIDC user page', {
          body: await userPage.screenshot(), contentType: 'image/png',
        })
      }
      throw failure
    } finally {
      await userContext?.close()
      const deleted = await giteaRequest('DELETE', `/api/v1/user/applications/oauth2/${application.id}`, password)
      expect(deleted.status).toBe(204)
    }
  })

test('invited organization member signs in through Gitea without crossing organization boundaries',
  async ({ request, page, browser, adminToken }) => {
    const credentials = await readFile(
      new URL('../../external-services/.state/credentials.env', import.meta.url), 'utf8')
    const password = credentials.match(/^GITEA_PASSWORD=(.+)$/m)?.[1]
    expect(password, 'The fixture supplies GITEA_PASSWORD').toBeTruthy()
    const secureUrl = new URL(browserUrl)
    if (secureUrl.protocol === 'http:') {
      secureUrl.protocol = 'https:'
      secureUrl.port = '9443'
    }
    const origin = secureUrl.origin
    const organization = 'oidc-acme'
    const ownRepository = `${organization}/team/repository`
    const otherRepository = 'oidc-other/team/repository'
    const id = `organization-${randomUUID().slice(0, 8)}`
    const account = await giteaRequest('GET', '/api/v1/user', password)
    expect(account.status).toBe(200)
    const email = JSON.parse(account.body).email
    expect(email).toBeTruthy()
    const registered = await giteaRequest('POST', '/api/v1/user/applications/oauth2', password, {
      name: id, confidential_client: true,
      redirect_uris: [`${origin}/api/auth/oidc/callback`], skip_secondary_authorization: false,
    })
    expect(registered.status).toBe(201)
    const application = JSON.parse(registered.body)
    let userContext
    let userPage
    try {
      for (const name of [ownRepository, otherRepository]) {
        const created = await request.post(`${orionUrl}/api/admin/repositories`, {
          headers: { Authorization: `Bearer ${adminToken}` }, data: { name },
        })
        expect(created.status()).toBe(201)
      }
      await page.addInitScript(({ origin, token }) => {
        if (location.origin === origin) sessionStorage.setItem('orion.ui.token', token)
      }, { origin, token: adminToken })
      await page.goto(origin)
      await page.getByRole('link', { name: 'People', exact: true }).click()
      const provider = page.locator('.oidc-settings')
      await provider.getByLabel('Provider scope').selectOption('organization')
      await provider.getByRole('combobox', { name: 'Organization', exact: true }).selectOption(organization)
      await provider.getByLabel('Provider ID', { exact: true }).fill(id)
      await provider.getByLabel('Issuer URL', { exact: true }).fill(`${giteaUrl}/`)
      await provider.getByLabel('Client ID', { exact: true }).fill(application.client_id)
      await provider.getByLabel('Client secret', { exact: true }).fill(application.client_secret)
      await provider.getByRole('button', { name: 'Save provider', exact: true }).click()
      await expect(provider.getByRole('status')).toHaveText('Provider saved in the selected scope.')
      // Invitations load provider availability when mounted.
      await page.reload()
      await page.getByRole('link', { name: 'People', exact: true }).click()
      const invitations = page.locator('.invitations')
      await invitations.getByRole('combobox', { name: 'Organization', exact: true }).selectOption(organization)
      await invitations.getByLabel('Email', { exact: true }).fill(email)
      await invitations.getByRole('button', { name: 'Create invitation', exact: true }).click()
      const invitationUrl = await invitations.getByLabel('Invitation link', { exact: true }).inputValue()

      userContext = await browser.newContext()
      userPage = await userContext.newPage()
      await userPage.goto(invitationUrl)
      await userPage.getByLabel('Sign-in provider').selectOption(id)
      await userPage.getByRole('button', { name: 'Sign in', exact: true }).click()
      await userPage.locator('#user_name').fill('fixture')
      await userPage.locator('#password').fill(password)
      await userPage.getByRole('button', { name: 'Sign In', exact: true }).click()
      await userPage.getByRole('button', { name: 'Authorize Application', exact: true }).click()
      await expect(userPage.getByRole('heading', { name: 'Set up your profile', exact: true })).toBeVisible()
      await userPage.getByLabel('First name', { exact: true }).fill('Fixture')
      await userPage.getByLabel('Last name', { exact: true }).fill('Member')
      await userPage.getByRole('button', { name: 'Continue', exact: true }).click()
      await expect(userPage.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
      const firstIdentity = await userPage.evaluate(async () => {
        const headers = { Authorization: `Bearer ${sessionStorage.getItem('orion.ui.token')}` }
        return (await fetch('/api/auth/me', { headers })).json()
      })
      expect(firstIdentity).toMatchObject({ organization, admin: false })
      expect(firstIdentity.userId).toBeTruthy()
      await userPage.getByRole('link', { name: 'Repositories', exact: true }).click()
      await expect(userPage.getByText(ownRepository, { exact: true })).toBeVisible()
      await expect(userPage.getByText(otherRepository, { exact: true })).toHaveCount(0)
      const permissions = await userPage.evaluate(async ({ ownRepository, otherRepository }) => {
        const headers = { Authorization: `Bearer ${sessionStorage.getItem('orion.ui.token')}` }
        const repositories = await fetch('/api/admin/repositories', { headers })
        const own = await fetch(`/r/${ownRepository}.git/info/refs?service=git-upload-pack`, { headers })
        const other = await fetch(`/r/${otherRepository}.git/info/refs?service=git-upload-pack`, { headers })
        const administration = await fetch('/api/admin/invitations', { headers })
        return { names: (await repositories.json()).repositories.map(item => item.name),
          own: own.status, other: other.status, administration: administration.status }
      }, { ownRepository, otherRepository })
      expect(permissions).toEqual({ names: [ownRepository], own: 200, other: 403, administration: 403 })
      await userPage.getByRole('button', { name: 'Sign out', exact: true }).click()
      await userPage.getByRole('button', { name: 'Sign in with OIDC', exact: true }).click()
      await userPage.getByLabel('Sign-in scope').selectOption('organization')
      await userPage.getByLabel('Organization', { exact: true }).fill(organization)
      await userPage.getByRole('button', { name: 'Find sign-in providers', exact: true }).click()
      await userPage.getByLabel('Sign-in provider').selectOption(id)
      await userPage.getByRole('button', { name: 'Sign in', exact: true }).click()
      // The Gitea session and consent remain valid; Orion still completes a new OIDC exchange.
      await expect(userPage.getByRole('button', { name: 'Continue', exact: true })).toBeVisible()
      await expect(userPage.getByRole('heading', { name: 'Set up your profile', exact: true })).toHaveCount(0)
      await expect(userPage.getByLabel('First name', { exact: true })).toHaveCount(0)
      await userPage.getByRole('button', { name: 'Continue', exact: true }).click()
      await expect(userPage.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
      const returningIdentity = await userPage.evaluate(async () => {
        const headers = { Authorization: `Bearer ${sessionStorage.getItem('orion.ui.token')}` }
        return (await fetch('/api/auth/me', { headers })).json()
      })
      expect(returningIdentity).toMatchObject({ organization, userId: firstIdentity.userId, admin: false })
      const restartDirectory = process.env.ORION_OIDC_RESTART_DIRECTORY
      let oldCookie
      if (restartDirectory) {
        await restartOrion(restartDirectory, 1)
        await userPage.reload()
        await expect(userPage.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
        const renewed = await userPage.evaluate(async ({ organization, userId }) => {
          const response = await fetch('/api/auth/oidc/refresh', { method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ scope: 'organization', organization, userId }) })
          const result = await response.json()
          if (response.status !== 200) return { status: response.status }
          const headers = { Authorization: `Bearer ${result.token}` }
          const identity = await fetch('/api/auth/me', { headers })
          const repositories = await fetch('/api/admin/repositories', { headers })
          return { status: response.status, organization: result.organization, userId: result.userId,
            scope: result.scope, identity: await identity.json(),
            names: (await repositories.json()).repositories.map(item => item.name) }
        }, { organization, userId: firstIdentity.userId })
        expect(renewed).toMatchObject({ status: 200, organization, userId: firstIdentity.userId,
          scope: 'organization', identity: { organization, userId: firstIdentity.userId, admin: false },
          names: [ownRepository] })
        oldCookie = (await userContext.cookies(origin)).find(cookie => cookie.name === '__Host-orion-oidc')
        expect(Boolean(oldCookie), 'The authenticated browser has an Orion refresh cookie').toBe(true)
      }
      await userPage.getByRole('button', { name: 'Sign out', exact: true }).click()
      await expect(userPage.getByRole('button', { name: 'Sign in with OIDC', exact: true })).toBeVisible()
      expect(await userPage.evaluate(() => sessionStorage.getItem('orion.ui.token'))).toBeNull()
      if (restartDirectory) {
        await restartOrion(restartDirectory, 2)
        // Replay the pre-logout cookie: an empty cookie would not prove durable revocation.
        await userContext.addCookies([oldCookie])
        const status = await userPage.evaluate(async ({ organization, userId }) => {
          const response = await fetch('/api/auth/oidc/refresh', { method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ scope: 'organization', organization, userId }) })
          return response.status
        }, { organization, userId: firstIdentity.userId })
        expect(status).toBe(401)
      }
    } catch (failure) {
      if (userPage) {
        await test.info().attach('Organization OIDC user page', {
          body: await userPage.screenshot(), contentType: 'image/png',
        })
      }
      throw failure
    } finally {
      await userContext?.close()
      const deleted = await giteaRequest('DELETE', `/api/v1/user/applications/oauth2/${application.id}`, password)
      expect(deleted.status).toBe(204)
    }
  })
