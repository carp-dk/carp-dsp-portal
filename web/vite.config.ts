import react from '@vitejs/plugin-react';
import path from 'node:path';
import { defineConfig } from 'vite';

// Aliases match carp-portal so components move between the two repos
// without touching their imports.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'build',
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      '@Assets': path.resolve(import.meta.dirname, './src/assets'),
      '@Components': path.resolve(import.meta.dirname, './src/components'),
      '@Modules': path.resolve(import.meta.dirname, './src/components/modules'),
      '@Utils': path.resolve(import.meta.dirname, './src/utils'),
    },
  },
  server: {
    port: 3000,
    // `pnpm dev` talks to the Ktor server rather than to CARP directly.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/health': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
