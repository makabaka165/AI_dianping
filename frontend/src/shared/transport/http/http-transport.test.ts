import { describe, expect, it, vi } from 'vitest'
import { HttpTransport } from '@/shared/transport/http/http-transport'

describe('HttpTransport', () => {
  it('unwraps legacy Result success payload', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: true, code: 0, data: { id: '1' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const http = new HttpTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => 'token-1',
      getScope: () => ({ tenantId: 'default', workspaceId: 'default' }),
    })

    const result = await http.get<{ id: string }>('/user/me')
    expect(result).toEqual({ id: '1' })
    const init = fetchImpl.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-1')
    expect((init.headers as Record<string, string>)['X-Tenant-Id']).toBe('default')
  })

  it('maps HTTP 200 business failure to CanonicalError', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: false, code: 50000, errorMsg: 'boom' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const http = new HttpTransport({ fetchImpl: fetchImpl as unknown as typeof fetch })
    await expect(http.get('/x')).rejects.toMatchObject({
      code: '50000',
      message: 'boom',
      retryable: false,
    })
  })

  it('maps interceptor string error codes', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: false, code: 'AI_PERMISSION_DENIED' }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const http = new HttpTransport({ fetchImpl: fetchImpl as unknown as typeof fetch })
    await expect(http.get('/api/v1/agents')).rejects.toMatchObject({
      code: 'AI_PERMISSION_DENIED',
      status: 403,
    })
  })

  it('returns bare DTO responses as-is', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ runId: 'r1', status: 'QUEUED' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const http = new HttpTransport({ fetchImpl: fetchImpl as unknown as typeof fetch })
    await expect(http.get('/api/v1/agent-runs/r1')).resolves.toEqual({
      runId: 'r1',
      status: 'QUEUED',
    })
  })
})
