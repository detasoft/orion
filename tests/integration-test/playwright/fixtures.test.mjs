import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { mkdtemp, mkdir, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import { test } from 'node:test'
import { chromium } from '@playwright/test'

const run = promisify(execFile)
const fixturesUrl = new URL('./fixtures.js', import.meta.url).href
const configUrl = new URL('./playwright.config.js', import.meta.url).href
const cli = new URL('./node_modules/@playwright/test/cli.js', import.meta.url).pathname
const endpoint = process.env.BROWSER_CDP_URL || 'http://127.0.0.1:9222'

async function fakeMake(source, operation) {
  const directory = await mkdtemp(path.join(tmpdir(), 'orion-browser-auth-test-'))
  try {
    await writeFile(path.join(directory, 'make'), `#!/bin/sh\n${source}\n`, { mode: 0o755 })
    return await operation({ PATH: `${directory}:${process.env.PATH}`, ORION_TOKEN: '' })
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

async function runScenario(source, observe = '0', environment = {}) {
  const directory = await mkdtemp(path.join(tmpdir(), 'orion-browser-fixture-test-'))
  const results = path.join(directory, 'results')
  const browser = await chromium.connectOverCDP(endpoint)
  const originalPages = browser.contexts()[0].pages()
  const originalContexts = browser.contexts().length
  try {
    await mkdir(results)
    await writeFile(path.join(directory, 'package.json'), '{"type":"module"}')
    await writeFile(path.join(directory, 'playwright.config.mjs'), `
      import config from ${JSON.stringify(configUrl)}
      export default { ...config, testDir: ${JSON.stringify(directory)}, timeout: 20000,
        outputDir: ${JSON.stringify(results)}, reporter: 'list' }
    `)
    await writeFile(path.join(directory, 'scenario.spec.js'), `
      import { test, expect } from ${JSON.stringify(fixturesUrl)}
      test.beforeEach(async ({ page }) => {
        await page.route('http://orion.test/**', route => route.fulfill({
          contentType: 'text/html', body: '<button>Continue</button>'
        }))
        await page.goto('http://orion.test/fixture-check')
      })
      ${source}
    `)
    let completed
    try {
      const output = await run(process.execPath, [cli, 'test', '--config',
        path.join(directory, 'playwright.config.mjs')], {
        env: { ...process.env, ORION_HTTP_URL: 'http://orion.test',
          ORION_TOKEN: 'synthetic-fixture-token', ORION_BROWSER_OBSERVE: observe,
          BROWSER_CDP_URL: endpoint, ...environment },
        timeout: 45000,
      })
      completed = { code: 0, output: output.stdout + output.stderr }
    } catch (error) {
      if (typeof error.code !== 'number') throw error
      completed = { code: error.code, output: error.stdout + error.stderr }
    }
    assert.equal(browser.contexts().length, originalContexts, 'Test contexts must be closed')
    for (const page of originalPages) {
      assert.equal(page.isClosed(), false, 'Existing pages must stay open')
    }
    assert.equal(browser.contexts()[0].pages().length, originalPages.length,
      'Tests must not leave pages in the shared context')
    completed.artifacts = await readdir(results, { recursive: true })
    return completed
  } finally {
    await browser.close()
    await rm(directory, { recursive: true, force: true })
  }
}

test('each scenario has clean storage and closes its pages', async () => {
  const completed = await runScenario(`
    test('sets browser state', async ({ page, context, step }) => {
      await page.evaluate(() => localStorage.setItem('fixture-check', 'previous scenario'))
      await context.addCookies([{ name: 'fixture-check', value: 'previous', url: 'http://orion.test' }])
      await step('Continue without presentation delays', page.getByRole('button'), async target => {
        await expect(target).not.toHaveAttribute('data-orion-test-target', '')
        await target.click()
      })
      await expect(page.locator('[data-orion-test-observer]')).toHaveCount(0)
    })
    test('starts with clean browser state', async ({ page, context }) => {
      expect(await page.evaluate(() => localStorage.getItem('fixture-check'))).toBeNull()
      expect(await context.cookies()).toEqual([])
    })
  `)
  assert.equal(completed.code, 0, completed.output)
})

test('a failing scenario closes its pages and retains a trace', async () => {
  const completed = await runScenario(`
    test('fails after opening its page', async ({ page }) => {
      await page.getByRole('button', { name: 'Continue' }).click()
      expect('actual result').toBe('expected result')
    })
  `)
  assert.equal(completed.code, 1, completed.output)
  assert.ok(completed.artifacts.some(file => file.endsWith('trace.zip')), 'Failure must retain its trace')
})

test('observed scenarios show their name and click position', async () => {
  const completed = await runScenario(`
    test('observed fixture check', async ({ page }) => {
      await expect(page.locator('[data-orion-test-observer]')).toHaveText('observed fixture check')
      await page.getByRole('button', { name: 'Continue' }).click()
      await expect(page.locator('[data-orion-test-click]')).toBeVisible()
    })
  `, '1')
  assert.equal(completed.code, 0, completed.output)
})

test('observed steps explain and highlight the target before acting, then show the result', async () => {
  const completed = await runScenario(`
    test('explained action', async ({ page, step }) => {
      const started = Date.now()
      await step('Нажать Continue', page.getByRole('button'), async target => {
        await expect(page.locator('[data-orion-test-observer]')).toContainText('Шаг 1: Нажать Continue')
        await expect(target).toHaveAttribute('data-orion-test-target', '')
        expect(Date.now() - started).toBeGreaterThanOrEqual(1900)
        await target.click()
      })
      expect(Date.now() - started).toBeGreaterThanOrEqual(2900)
      await expect(page.locator('[data-orion-test-target]')).toHaveCount(0)
      await step('Обновить страницу', null, () => page.reload())
      await expect(page.locator('[data-orion-test-observer]')).toContainText('Шаг 2: Обновить страницу')
    })
  `, '1')
  assert.equal(completed.code, 0, completed.output)
})

test('a supplied token is installed in the test session', async () => {
  const completed = await runScenario(`
    test('uses its supplied token', async ({ page }) => {
      expect(await page.evaluate(() => sessionStorage.getItem('orion.ui.token')))
        .toBe('synthetic-fixture-token')
    })
  `)
  assert.equal(completed.code, 0, completed.output)
})

test('the explicit server URL controls navigation, including its port and path', async () => {
  const completed = await runScenario(`
    const { orionUrl, browserUrl } = await import(${JSON.stringify(fixturesUrl)})
    test('uses the selected server', async ({ page }) => {
      expect(orionUrl).toBe('http://review.orion.test:18081/console')
      expect(browserUrl).toBe(orionUrl)
      await page.route(browserUrl, route => route.fulfill({ body: 'Selected server' }))
      await page.goto(browserUrl)
      expect(page.url()).toBe(orionUrl)
    })
  `, '0', { ORION_HTTP_URL: 'http://review.orion.test:18081/console/' })
  assert.equal(completed.code, 0, completed.output)
})

test('a host loopback URL keeps its port and path through the container mapping', async () => {
  const completed = await runScenario(`
    const { orionUrl, browserUrl } = await import(${JSON.stringify(fixturesUrl)})
    test('uses the container route to the selected host server', async ({ page }) => {
      expect(orionUrl).toBe('http://localhost:18082/console')
      expect(browserUrl).toBe('http://orion.test:18082/console')
      await page.route(browserUrl, route => route.fulfill({ body: 'Selected host server' }))
      await page.goto(browserUrl)
      expect(page.url()).toBe(browserUrl)
    })
  `, '0', { ORION_HTTP_URL: 'http://localhost:18082/console' })
  assert.equal(completed.code, 0, completed.output)
})

test('without a supplied token the fixture obtains one through the existing SSH command', async () => {
  const completed = await fakeMake("printf '%s\\n' 'synthetic-issued-token'", environment => runScenario(`
    test('uses the issued token', async ({ page }) => {
      expect(await page.evaluate(() => sessionStorage.getItem('orion.ui.token')))
        .toBe('synthetic-issued-token')
    })
  `, '0', environment))
  assert.equal(completed.code, 0, completed.output)
})

test('failed SSH authentication closes the test context without touching existing pages', async () => {
  const completed = await fakeMake('exit 1', environment => runScenario(`
    test('cannot start without authentication', async ({ page }) => {
      await page.getByRole('button', { name: 'Continue' }).click()
    })
  `, '0', environment))
  assert.equal(completed.code, 1, completed.output)
  assert.match(completed.output, /Cannot issue an admin token over SSH/)
})

test('an unsupported observation mode fails instead of silently running unattended', async () => {
  const completed = await runScenario(`
    test('requires an explicit valid mode', async ({ page }) => {
      await page.getByRole('button', { name: 'Continue' }).click()
    })
  `, 'unexpected')
  assert.equal(completed.code, 1, completed.output)
  assert.match(completed.output, /0 or 1/)
})

test('test contexts trust the fixture CA without disabling certificate validation', async () => {
  const completed = await runScenario(`
    test('validates the fixture HTTPS certificate', async ({ page }) => {
      const response = await page.goto('https://fixture.orion.test:8443/api/v1/version')
      expect(response.status()).toBe(200)
      expect(await response.json()).toHaveProperty('version')
      expect(await page.evaluate(() => sessionStorage.getItem('orion.ui.token'))).toBeNull()
    })
  `)
  assert.equal(completed.code, 0, completed.output)
})
