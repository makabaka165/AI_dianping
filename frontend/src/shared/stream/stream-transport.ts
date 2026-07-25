import { createParser, type EventSourceMessage } from 'eventsource-parser'
import {
  createCanonicalError,
  type CanonicalError,
} from '@/shared/contracts/common/canonical-error'

export type StreamEvent = {
  id?: string
  event: string
  data: string
  raw: EventSourceMessage
}

export type StreamTransportOptions = {
  fetchImpl?: typeof fetch
  getAccessToken?: () => string | null | undefined
  getScope?: () => { tenantId?: string | null; workspaceId?: string | null } | null | undefined
  maxRetries?: number
  baseBackoffMs?: number
  maxBackoffMs?: number
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>
}

export type OpenStreamOptions = {
  method?: string
  headers?: Record<string, string>
  body?: unknown
  signal?: AbortSignal
  lastEventId?: string
  auth?: boolean
  scope?: boolean | { tenantId?: string | null; workspaceId?: string | null }
  onEvent: (event: StreamEvent) => void | Promise<void>
  shouldRetry?: (error: CanonicalError, attempt: number) => boolean
  isTerminalEvent?: (event: StreamEvent) => boolean
}

const NON_RETRYABLE = new Set(['AI_UNAUTHENTICATED', 'AI_PERMISSION_DENIED', 'HTTP_401', 'HTTP_403', 'HTTP_404'])

function defaultSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(createCanonicalError({ code: 'REQUEST_ABORTED', message: 'Request was aborted', retryable: false }))
      return
    }
    const timer = setTimeout(resolve, ms)
    const onAbort = () => {
      clearTimeout(timer)
      reject(createCanonicalError({ code: 'REQUEST_ABORTED', message: 'Request was aborted', retryable: false }))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

export class StreamTransport {
  private readonly fetchImpl: typeof fetch
  private readonly getAccessToken?: () => string | null | undefined
  private readonly getScope?: StreamTransportOptions['getScope']
  private readonly maxRetries: number
  private readonly baseBackoffMs: number
  private readonly maxBackoffMs: number
  private readonly sleep: (ms: number, signal?: AbortSignal) => Promise<void>

  constructor(options: StreamTransportOptions = {}) {
    this.fetchImpl = options.fetchImpl ?? fetch.bind(globalThis)
    this.getAccessToken = options.getAccessToken
    this.getScope = options.getScope
    this.maxRetries = options.maxRetries ?? 5
    this.baseBackoffMs = options.baseBackoffMs ?? 300
    this.maxBackoffMs = options.maxBackoffMs ?? 5_000
    this.sleep = options.sleep ?? defaultSleep
  }

  async open(url: string, options: OpenStreamOptions): Promise<void> {
    let attempt = 0
    let lastEventId = options.lastEventId
    const seen = new Set<string>()

    while (true) {
      try {
        const terminal = await this.openOnce(url, options, lastEventId, seen, (id) => {
          lastEventId = id
        })
        if (terminal) return
        // clean close without terminal event still ends unless retry decides otherwise
        return
      } catch (error) {
        const canonical = toCanonical(error)
        const retryable =
          options.shouldRetry?.(canonical, attempt) ??
          (canonical.retryable && !NON_RETRYABLE.has(canonical.code) && attempt < this.maxRetries)
        if (!retryable || options.signal?.aborted) throw canonical
        const delay = Math.min(this.maxBackoffMs, this.baseBackoffMs * 2 ** attempt)
        attempt += 1
        await this.sleep(delay, options.signal)
      }
    }
  }

  private async openOnce(
    url: string,
    options: OpenStreamOptions,
    lastEventId: string | undefined,
    seen: Set<string>,
    onLastEventId: (id: string) => void,
  ): Promise<boolean> {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      ...(options.headers ?? {}),
    }
    if (lastEventId) headers['Last-Event-ID'] = lastEventId
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
    if (options.body !== undefined) {
      headers['Content-Type'] = headers['Content-Type'] ?? 'application/json'
      body = JSON.stringify(options.body)
    }

    const response = await this.fetchImpl(url, {
      method: options.method ?? 'GET',
      headers,
      body,
      signal: options.signal,
    })

    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => '')
      throw createCanonicalError({
        code: response.status === 401 ? 'AI_UNAUTHENTICATED' : `HTTP_${response.status}`,
        message: text || `SSE request failed with ${response.status}`,
        status: response.status,
        retryable: response.status >= 500 || response.status === 429,
      })
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let terminal = false
    let settleError: CanonicalError | null = null

    const parser = createParser({
      onEvent: (event) => {
        if (terminal || settleError) return
        const streamEvent: StreamEvent = {
          id: event.id,
          event: event.event || 'message',
          data: event.data,
          raw: event,
        }
        if (event.id) {
          if (seen.has(event.id)) return
          seen.add(event.id)
          onLastEventId(event.id)
        }
        const maybeTerminal = options.isTerminalEvent?.(streamEvent) ?? false
        Promise.resolve(options.onEvent(streamEvent))
          .then(() => {
            if (maybeTerminal) terminal = true
          })
          .catch((error) => {
            settleError = toCanonical(error)
          })
      },
    })

    while (!terminal && !settleError) {
      const { done, value } = await reader.read()
      if (done) break
      parser.feed(decoder.decode(value, { stream: true }))
    }
    parser.feed(decoder.decode())
    if (settleError) throw settleError
    return terminal
  }
}

function toCanonical(error: unknown): CanonicalError {
  if (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    'message' in error &&
    'retryable' in error
  ) {
    return error as CanonicalError
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return createCanonicalError({ code: 'REQUEST_ABORTED', message: 'Request was aborted', retryable: false })
  }
  return createCanonicalError({
    code: 'STREAM_ERROR',
    message: error instanceof Error ? error.message : 'Stream failed',
    retryable: true,
  })
}
