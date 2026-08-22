import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dashboard calls /api/* on its own origin, so the dev server proxies that to a backend.
// Defaults to the hosted deployment; set VITE_API_TARGET=http://localhost:8080 to run against local.
const apiTarget = process.env.VITE_API_TARGET || 'https://incident-remediation-agent.onrender.com'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        secure: true,
      },
    },
  },
})
