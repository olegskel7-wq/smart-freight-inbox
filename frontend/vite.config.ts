import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The SPA is served under /app/ (same-origin) during the Thymeleaf→React migration, so it
// never collides with the legacy server-rendered routes. `base` makes built asset URLs
// absolute under /app/. In dev, /api is proxied to the Spring backend so there is no CORS and
// the bearer token flows to the same place it will in prod. The production build is emitted
// straight into the backend's static resources so `mvn package` ships it inside the jar.
export default defineConfig({
  plugins: [react()],
  base: '/app/',
  build: {
    outDir: '../target/classes/static/app',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
