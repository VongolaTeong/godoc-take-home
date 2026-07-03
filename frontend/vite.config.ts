import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // Dev-only: the Spring Boot API runs on :8080. In production the built assets are
    // served by Spring Boot itself (same origin), so no CORS setup is needed anywhere.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
