import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

const PROXY_PREFIXES = [
  '/api',
  '/user',
  '/shop',
  '/shop-type',
  '/blog',
  '/follow',
  '/voucher',
  '/voucher-order',
  '/upload',
  '/document',
  '/admin',
]

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendTarget = env.VITE_BACKEND_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    server: {
      port: Number(env.VITE_DEV_PORT || 5173),
      proxy: Object.fromEntries(
        PROXY_PREFIXES.map((prefix) => [
          prefix,
          {
            target: backendTarget,
            changeOrigin: true,
          },
        ]),
      ),
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: true,
      globals: true,
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      exclude: ['node_modules', 'dist', 'tests/e2e/**'],
    },
  }
})
