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
      // The phone app's API, so a phone can pair with the web address (http://<mac-ip>:5173) and
      // the login page's sign-in QR matches the server the phone is paired with. The trailing slash keeps
      // the /agents pages in the web app.
      '/agent/': { target: api, changeOrigin: true, ws: true },
    },
  },
})
