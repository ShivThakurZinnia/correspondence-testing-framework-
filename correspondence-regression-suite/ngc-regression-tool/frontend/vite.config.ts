/// <reference types="vitest" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The built UI is served by the Spring Boot backend from its static resources.
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../backend/src/main/resources/static', emptyOutDir: true },
  server: { port: 5173, proxy: { '/api': 'http://localhost:8085' } },
  test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test-setup.ts'], css: false },
});
