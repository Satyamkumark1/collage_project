import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Port 5173 (Vite's default) is permanently held by an unrelated project on this machine, so
// this project uses a fixed, different port instead — the backend's CORS_ALLOWED_ORIGIN in
// backend/.env must match. See docs/DECISIONS.md.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5183,
    strictPort: true,
  },
})
