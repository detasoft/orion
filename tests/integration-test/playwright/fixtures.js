import { execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { test as base, expect } from '@playwright/test'

const run = promisify(execFile)
const repository = fileURLToPath(new URL('../../../', import.meta.url))
const observationMode = process.env.ORION_BROWSER_OBSERVE || '0'
if (!['0', '1'].includes(observationMode)) throw new Error('ORION_BROWSER_OBSERVE must be 0 or 1')
const observe = observationMode === '1'
if (!process.env.ORION_HTTP_URL) throw new Error('Set ORION_HTTP_URL to the server under test')
const target = new URL(process.env.ORION_HTTP_URL)
if (!['http:', 'https:'].includes(target.protocol)) throw new Error('The server URL must use HTTP or HTTPS')
export const orionUrl = target.href.replace(/\/$/, '')
if (['localhost', '127.0.0.1', '[::1]'].includes(target.hostname)) target.hostname = 'orion.test'
export const browserUrl = target.href.replace(/\/$/, '')

export const test = base.extend({
  adminToken: [async ({}, use) => {
    let token = process.env.ORION_TOKEN
    if (!token) {
      const host = process.env.ORION_SSH_HOST || new URL(orionUrl).hostname
      const port = process.env.ORION_SSH_PORT || '8022'
      try {
        const issued = await run('make', ['-s', 'issue-token-raw',
          `ORION_SSH_HOST=${host}`, `ORION_SSH_PORT=${port}`], {
          cwd: repository, timeout: 20000,
        })
        token = issued.stdout.trim()
      } catch {
        throw new Error('Cannot issue an admin token over SSH. Enroll your key, configure ORION_SSH_HOST/' +
          'ORION_SSH_PORT, or supply ORION_TOKEN')
      }
    }
    expect(token, 'An Orion admin token is required').toBeTruthy()
    await use(token)
  }, { scope: 'worker' }],
  browser: [async ({ playwright }, use) => {
    console.log(`Server URL: ${orionUrl}`)
    console.log(`Browser URL: ${browserUrl}; mode: ${observe ? 'observe (1000 ms)' : 'unattended'}`)
    const browser = await playwright.chromium.connectOverCDP(
      process.env.BROWSER_CDP_URL || 'http://127.0.0.1:9222', { slowMo: observe ? 1000 : 0 })
    try {
      await use(browser)
    } finally {
      await browser.close()
    }
  }, { scope: 'worker' }],
  context: async ({ context, adminToken }, use, testInfo) => {
    await context.addInitScript(({ token, origin }) => {
      if (location.origin === origin) sessionStorage.setItem('orion.ui.token', token)
    }, { token: adminToken, origin: new URL(browserUrl).origin })
    if (observe) {
      await context.addInitScript(title => {
        document.addEventListener('DOMContentLoaded', () => {
          const label = document.createElement('div')
          label.setAttribute('data-orion-test-observer', '')
          label.textContent = title
          label.style.cssText = 'position:fixed;bottom:12px;left:12px;z-index:2147483647;' +
            'padding:8px 12px;background:#17202a;color:white;font:14px sans-serif;pointer-events:none'
          document.documentElement.append(label)
          document.addEventListener('pointerdown', event => {
            const marker = document.createElement('div')
            marker.setAttribute('data-orion-test-click', '')
            marker.style.cssText = 'position:fixed;z-index:2147483647;width:24px;height:24px;' +
              'border:3px solid #ff7043;border-radius:50%;transform:translate(-50%,-50%);' +
              `left:${event.clientX}px;top:${event.clientY}px;pointer-events:none`
            document.documentElement.append(marker)
            setTimeout(() => marker.remove(), 2000)
          }, true)
        }, { once: true })
      }, testInfo.title)
    }
    await use(context)
  },
  page: async ({ page }, use) => {
    if (observe) await page.bringToFront()
    await use(page)
  },
})

export { expect }
