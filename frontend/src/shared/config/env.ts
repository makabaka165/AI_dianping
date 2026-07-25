import { z } from 'zod'

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().optional().default(''),
  VITE_BACKEND_PROXY_TARGET: z.string().optional().default('http://localhost:8081'),
  VITE_DEV_PORT: z.string().optional().default('5173'),
})

export type AppEnv = {
  apiBaseUrl: string
  backendProxyTarget: string
  devPort: number
}

export function loadAppEnv(source: Record<string, string | undefined> = import.meta.env): AppEnv {
  const parsed = envSchema.safeParse(source)
  if (!parsed.success) {
    throw new Error(`Invalid frontend environment: ${parsed.error.message}`)
  }
  const apiBaseUrl = (parsed.data.VITE_API_BASE_URL || '').replace(/\/$/, '')
  return {
    apiBaseUrl,
    backendProxyTarget: parsed.data.VITE_BACKEND_PROXY_TARGET,
    devPort: Number(parsed.data.VITE_DEV_PORT || 5173),
  }
}

export const appEnv = loadAppEnv()
