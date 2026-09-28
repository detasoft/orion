import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { chromium, expect } from '@playwright/test'

let browser
let context
let page
let lifecycle = ''

before(async () => {
  browser = await chromium.connectOverCDP(process.env.BROWSER_CDP_URL || 'http://127.0.0.1:9222')
  context = await browser.newContext()
  await context.addInitScript(() => sessionStorage.setItem('orion.ui.token', 'synthetic-review-token'))
  await context.route('http://orion.test:4173/**', async route => {
    const incoming = new URL(route.request().url())
    const response = await fetch(`http://localhost:4173${incoming.pathname}${incoming.search}`,
      { signal: AbortSignal.timeout(10000) })
    await route.fulfill({ status: response.status,
      contentType: response.headers.get('content-type') ?? 'application/octet-stream',
      body: Buffer.from(await response.arrayBuffer()) })
  })
  await context.route('http://orion.test:4173/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const data = {
      '/api/auth/me': { userId: 'admin', organization: '', admin: true },
      '/api/admin/routes': { routes: [] },
      '/api/admin/transports': {},
      '/api/admin/repositories': { repositories: [] },
    }
    if (path === '/api/admin/lifecycle/state') {
      await route.fulfill({ contentType: 'text/plain', body: lifecycle })
    } else if (Object.hasOwn(data, path)) {
      await route.fulfill({ json: data[path] })
    } else {
      await route.abort()
    }
  })
  page = await context.newPage()
})

after(async () => {
  await context?.close()
  await browser?.close()
})

test('Overview preserves lifecycle lines and indentation', async () => {
  lifecycle = 'runtime: RUNNING\n  access-control: RUNNING\n  transports: RUNNING\n    http: DISABLED'
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('http://orion.test:4173/')
  const state = page.locator('.state-value')
  await expect(state).toBeVisible()
  const positions = await state.evaluate(element => {
    const text = element.firstChild
    return ['runtime:', 'access-control:', 'transports:', 'http:'].map(label => {
      const start = text.textContent.indexOf(label)
      const range = document.createRange()
      range.setStart(text, start)
      range.setEnd(text, start + label.length)
      const { left, top } = range.getBoundingClientRect()
      return { left, top }
    })
  })
  for (let index = 1; index < positions.length; index += 1) {
    assert.ok(positions[index].top > positions[index - 1].top, 'Each lifecycle line must have its own row')
  }
  assert.ok(positions[1].left > positions[0].left, 'Child indentation must be preserved')
  assert.equal(positions[1].left, positions[2].left, 'Sibling indentation must align')
  assert.ok(positions[3].left > positions[2].left, 'Nested indentation must be preserved')
})

test('Overview wraps long lifecycle values without clipping on mobile', async () => {
  lifecycle = `runtime: RUNNING\n  service-${'x'.repeat(180)}: DISABLED`
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('http://orion.test:4173/')
  const state = page.locator('.state-value')
  await expect(state).toBeVisible()
  const dimensions = await state.evaluate(element => ({
    width: element.clientWidth, contentWidth: element.scrollWidth,
    height: element.clientHeight, lineHeight: parseFloat(getComputedStyle(element).lineHeight),
  }))
  assert.ok(dimensions.contentWidth <= dimensions.width, 'Long values must fit the available width')
  assert.ok(dimensions.height > dimensions.lineHeight * 2, 'Long values must wrap to additional rows')
})
