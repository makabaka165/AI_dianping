import { describe, expect, it, vi } from 'vitest'
import { StreamTransport } from '@/shared/stream/stream-transport'

function sseResponse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder()
  let index = 0
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index >= chunks.length) {
        controller.close()
        return
      }
      controller.enqueue(encoder.encode(chunks[index]))
      index += 1
    },
  })
  return new Response(stream, {
    status,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

describe('StreamTransport', () => {
  it('parses events across chunk boundaries and deduplicates ids', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(sseResponse(['id: 1\nevent: run.running\ndata: {"a":1}\n', '\n', 'id: 1\nevent: run.running\ndata: {"a":1}\n\n']))
    const stream = new StreamTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => 't',
      getScope: () => ({ tenantId: 'default', workspaceId: 'default' }),
    })
    const events: string[] = []
    await stream.open('/api/v1/agent-runs/r1/events', {
      onEvent: (event) => {
        events.push(event.event)
      },
      isTerminalEvent: () => false,
    })
    expect(events).toEqual(['run.running'])
  })

  it('stops without retry on 401', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: false, code: 'AI_UNAUTHENTICATED' }), { status: 401 }),
    )
    const stream = new StreamTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      maxRetries: 3,
      sleep: async () => undefined,
    })
    await expect(
      stream.open('/api/v1/agent-runs/r1/events', {
        onEvent: () => undefined,
      }),
    ).rejects.toMatchObject({ code: 'AI_UNAUTHENTICATED', status: 401 })
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })

  it('retries retryable failures with backoff then succeeds', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response('nope', { status: 503 }))
      .mockResolvedValueOnce(sseResponse(['event: run.completed\ndata: {}\n\n']))
    const sleep = vi.fn().mockResolvedValue(undefined)
    const stream = new StreamTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      sleep,
      maxRetries: 2,
      baseBackoffMs: 10,
    })
    const events: string[] = []
    await stream.open('/events', {
      onEvent: (event) => {
        events.push(event.event)
      },
      isTerminalEvent: (event) => event.event === 'run.completed',
    })
    expect(events).toEqual(['run.completed'])
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(sleep).toHaveBeenCalled()
  })
})
