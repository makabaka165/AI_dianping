import { appEnv } from '@/shared/config/env'
import {
  createCanonicalError,
  type CanonicalError,
} from '@/shared/contracts/common/canonical-error'

export type ScopeHeaders = {
  tenantId?: string | null
  workspaceId?: string | null
}

export type HttpTransportOptions = {
  baseUrl?: string
  getAccessToken?: () => string | null | undefined
  getScope?: () => ScopeHeaders | null | undefined
  defaultTimeoutMs?: number
  fetchImpl?: typeof fetch
  onUnauthorized?: (error: CanonicalError) => void
}

export type RequestOptions = {
  method?: string
  headers?: Record<string, string>
  query?: Record<string, string | number | boolean | null | undefined>
  body?: unknown
  formData?: FormData
  signal?: AbortSignal
  timeoutMs?: number
  auth?: boolean
  scope?: boolean | ScopeHeaders
  parseAs?: 'json' | 'text' | 'blob' | 'void'
  requestId?: string
}

type LegacyResult = {
  success?: boolean
  code?: string | number
  errorMsg?: string
  message?: string
  data?: unknown
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isRetryableStatus(status?: number): boolean {
  if (!status) return true
  return status === 408 || status === 429 || status >= 500
}

function joinUrl(baseUrl: string, path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const base = baseUrl.replace(/\/$/, '')
  const suffix = path.startsWith('/') ? path : `/${path}`
  return `${base}${suffix}`
}

function appendQuery(url: string, query?: RequestOptions['query']): string {
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const qs = params.toString()
  if (!qs) return url
  return url.includes('?') ? `${url}&${qs}` : `${url}?${qs}`
}

function extractFieldErrors(payload: unknown): Record<string, string> | undefined {
  if (!isObject(payload)) return undefined
  const candidate = payload.fieldErrors ?? payload.errors ?? payload.details
  if (!isObject(candidate)) return undefined
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(candidate)) {
    if (typeof value === 'string') result[key] = value
    else if (Array.isArray(value) && typeof value[0] === 'string') result[key] = value[0]
  }
  return Object.keys(result).length > 0 ? result : undefined
}

export class HttpTransport {
  private readonly baseUrl: string
  private readonly getAccessToken?: () => string | null | undefined
  private readonly getScope?: () => ScopeHeaders | null | undefined
  private readonly defaultTimeoutMs: number
  private readonly fetchImpl: typeof fetch
  private readonly onUnauthorized?: (error: CanonicalError) => void

  constructor(options: HttpTransportOptions = {}) {
    this.baseUrl = options.baseUrl ?? appEnv.apiBaseUrl
    this.getAccessToken = options.getAccessToken
    this.getScope = options.getScope
    this.defaultTimeoutMs = options.defaultTimeoutMs ?? 30_000
    this.fetchImpl = options.fetchImpl ?? fetch.bind(globalThis)
    this.onUnauthorized = options.onUnauthorized
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = (options.method ?? 'GET').toUpperCase()
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...(options.headers ?? {}),
    }

    const requestId = options.requestId ?? crypto.randomUUID()
    headers['X-Request-Id'] = requestId

    if (options.auth !== false) {
      const token = this.getAccessToken?.()
      if (token) headers.Authorization = `Bearer ${token}`
    }

    if (options.scope !== false) {
      const scope = typeof options.scope === 'object' ? options.scope : this.getScope?.()
      if (scope?.tenantId) headers['X-Tenant-Id'] = scope.tenantId
      if (scope?.workspaceId) headers['X-Workspace-Id'] = scope.workspaceId
    }

    let body: BodyInit | undefined
    if (options.formData) {
      body = options.formData
    } else if (options.body !== undefined) {
      headers['Content-Type'] = headers['Content-Type'] ?? 'application/json'
      body = JSON.stringify(options.body)
    }

    const controller = new AbortController()
    const timeoutMs = options.timeoutMs ?? this.defaultTimeoutMs
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    const onAbort = () => controller.abort()
    options.signal?.addEventListener('abort', onAbort)

    try {
      const response = await this.fetchImpl(appendQuery(joinUrl(this.baseUrl, path), options.query), {
        method,
        headers,
        body,
        signal: controller.signal,
      })

      if (response.status === 204 || options.parseAs === 'void') {
        if (!response.ok) throw this.toError(response.status, null, requestId)
        return undefined as T
      }

      if (options.parseAs === 'blob') {
        if (!response.ok) throw this.toError(response.status, null, requestId)
        return (await response.blob()) as T
      }

      if (options.parseAs === 'text') {
        const text = await response.text()
        if (!response.ok) throw this.toError(response.status, text, requestId)
        return text as T
      }

      const rawText = await response.text()
      const payload = rawText ? this.safeJson(rawText) : null

      if (!response.ok) {
        const error = this.toError(response.status, payload ?? rawText, requestId)
        if (response.status === 401) this.onUnauthorized?.(error)
        throw error
      }

      return this.unwrapPayload<T>(payload, requestId, response.status)
    } catch (error) {
      if (isCanonicalErrorLike(error)) throw error
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw createCanonicalError({
          code: 'REQUEST_ABORTED',
          message: 'Request was aborted',
          requestId,
          retryable: false,
        })
      }
      throw createCanonicalError({
        code: 'NETWORK_ERROR',
        message: error instanceof Error ? error.message : 'Network request failed',
        requestId,
        retryable: true,
      })
    } finally {
      clearTimeout(timeout)
      options.signal?.removeEventListener('abort', onAbort)
    }
  }

  get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body' | 'formData'>) {
    return this.request<T>(path, { ...options, method: 'GET' })
  }

  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) {
    return this.request<T>(path, { ...options, method: 'POST', body })
  }

  private unwrapPayload<T>(payload: unknown, requestId: string, status: number): T {
    if (!isObject(payload)) return payload as T
    if ('success' in payload) {
      const legacy = payload as LegacyResult
      if (legacy.success === false) {
        throw createCanonicalError({
          code: String(legacy.code ?? 'BUSINESS_ERROR'),
          message: legacy.errorMsg || legacy.message || 'Request failed',
          requestId,
          fieldErrors: extractFieldErrors(payload),
          retryable: isRetryableStatus(status),
          status,
        })
      }
      return ('data' in legacy ? legacy.data : payload) as T
    }
    return payload as T
  }

  private toError(status: number, payload: unknown, requestId: string): CanonicalError {
    if (isObject(payload)) {
      const code = payload.code ?? payload.errorCode ?? status
      const message =
        (typeof payload.errorMsg === 'string' && payload.errorMsg) ||
        (typeof payload.message === 'string' && payload.message) ||
        (typeof payload.error === 'string' && payload.error) ||
        `HTTP ${status}`
      return createCanonicalError({
        code: String(code),
        message,
        requestId,
        fieldErrors: extractFieldErrors(payload),
        retryable: isRetryableStatus(status),
        status,
      })
    }
    if (typeof payload === 'string' && payload.trim()) {
      return createCanonicalError({
        code: `HTTP_${status}`,
        message: payload,
        requestId,
        retryable: isRetryableStatus(status),
        status,
      })
    }
    return createCanonicalError({
      code: `HTTP_${status}`,
      message: `HTTP ${status}`,
      requestId,
      retryable: isRetryableStatus(status),
      status,
    })
  }

  private safeJson(text: string): unknown {
    try {
      return JSON.parse(text)
    } catch {
      return text
    }
  }
}

function isCanonicalErrorLike(value: unknown): value is CanonicalError {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    'message' in value &&
    'retryable' in value
  )
}
