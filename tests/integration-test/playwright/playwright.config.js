import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.js',
  timeout: 180_000,
  workers: 1,
  use: { actionTimeout: 10_000, trace: 'retain-on-failure' },
  outputDir: '../target/playwright',
  reporter: [['list'], ['html', { outputFolder: '../target/playwright-report', open: 'never' }]]
})
