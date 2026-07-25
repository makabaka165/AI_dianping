/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BACKEND_PROXY_TARGET?: string
  readonly VITE_DEV_PORT?: string
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
