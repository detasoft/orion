import { createRequire } from 'node:module'
import { after, before, test } from 'node:test'

const require = createRequire(new URL('../integration-test/playwright/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
let browser
let page
let savePasswords

before(async () => {
  browser = await chromium.connectOverCDP(process.env.BROWSER_CDP_URL || 'http://127.0.0.1:9222')
  page = await browser.contexts()[0].newPage()
  await page.goto('chrome://password-manager/settings')
  savePasswords = page.getByRole('button', { name: 'Offer to save passwords and passkeys', exact: true })
  await expect(savePasswords).toBeVisible()
})

after(async () => {
  await page?.close()
  await browser?.close()
})

test('the fixture browser disables password saving', async () => {
  await expect(savePasswords).toHaveAttribute('aria-pressed', 'false')
})

test('password saving cannot be re-enabled in the fixture browser', async () => {
  await expect(savePasswords).toBeDisabled()
})
