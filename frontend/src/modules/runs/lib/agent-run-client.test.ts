import { describe, expect, it, vi } from 'vitest'
import { createAgentRunClient } from '@/modules/runs/lib/agent-run-client'
import type { RunAdapter } from '@/modules/runs/adapters/http-run-adapter'
import type { AgentRunDetail } from '@/modules/runs/contracts/run-contract'
import type { StreamTransport } from '@/shared/stream/stream-transport'

function detail(status: string): AgentRunDetail {
  return {
    runId: 'run-1',
    agentId: 'agent-shop-consultant',
    agentVersion: 1,
    sessionId: 'session-1',
    status,
  }
}

describe('AgentRunClient', () => {
  it('publishes WAITING_FOR_USER when feedback event arrives before detail catches up', async () => {
    const get = vi
      .fn<() => Promise<AgentRunDetail>>()
      .mockResolvedValueOnce(detail('RUNNING'))
      .mockResolvedValueOnce(detail('WAITING_FOR_USER'))
    const adapter = { get } as unknown as RunAdapter
    const stream = {
      open: vi.fn(async (_url, options) => {
        await options.onEvent({
          id: '3',
          event: 'feedback.required',
          data: '{"sequence":3}',
          raw: {} as never,
        })
      }),
    } as unknown as StreamTransport
    const updates: string[] = []

    const result = await createAgentRunClient(adapter, stream).observe('run-1', {
      onUpdate: (observation) => updates.push(observation.status),
    })

    expect(get).toHaveBeenCalledTimes(2)
    expect(updates).toContain('WAITING_FOR_USER')
    expect(result.status).toBe('WAITING_FOR_USER')
  })
})
