import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/',
  plugins: [vue()],
  build: {
    outDir: 'target/dist',
    emptyOutDir: true,
  },
  server: {
    port: Number(process.env.VITE_PORT || 4173),
    proxy: {
      '/api': process.env.VITE_BACKEND_URL || 'http://localhost:8000',
    },
  },
  test: {
    environment: 'jsdom',
  },
})
