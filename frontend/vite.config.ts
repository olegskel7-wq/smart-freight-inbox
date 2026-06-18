import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Since the Thymeleaf→React cutover the SPA IS the application UI, served same-origin from the
// root. `base: '/'` makes built asset URLs absolute under /assets. In dev, /api is proxied to the
// Spring backend so there is no CORS and the bearer token flows to the same place it will in prod.
// The production build is still emitted into classpath:/static/app (SpaWebConfig maps it to /),
// so the outDir and the Dockerfile COPY are unchanged.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../target/classes/static/app',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      // STOMP WebSocket (Phase 3.2) — ws:true upgrades the connection to the backend.
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
