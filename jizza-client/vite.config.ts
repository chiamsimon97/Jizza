import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

// Where the backend runs. Defaults to the backend's own default port; set JIZZA_API_URL to point
// the dev server elsewhere (another port, or the end-to-end suite's isolated backend).
const api = process.env.JIZZA_API_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  // sockjs-client references Node's `global`, which browsers don't define.
  define: { global: 'globalThis' },
  resolve: {
    alias: { '@': resolve(__dirname, 'src') },
  },
  server: {
    proxy: {
      '/api': { target: api, changeOrigin: true },
      '/ws':  { target: api.replace(/^http/, 'ws'),  ws: true },
    },
  },
})
