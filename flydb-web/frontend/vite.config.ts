import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: './',
  build: { outDir: '../target/frontend', emptyOutDir: true },
  server: { proxy: { '/api': 'http://127.0.0.1:8317' } },
})
