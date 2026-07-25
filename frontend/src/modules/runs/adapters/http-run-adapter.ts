import type { HttpTransport } from '@/shared/transport/http/http-transport'
import type { PageResponse } from '@/shared/contracts/common/page'
import type {
  AgentRunCreated,
  AgentRunDetail,
  AgentRunSummary,
  CreateRunInput,
} from '@/modules/runs/contracts/run-contract'

export function createRunAdapter(http: HttpTransport) {
  return {
    list(params: {
      page?: number
      size?: number
      agentId?: string
      status?: string
      createdFrom?: string
      createdTo?: string
    } = {}) {
      return http.get<PageResponse<AgentRunSummary>>('/api/v1/agent-runs', {
        query: {
          page: params.page ?? 1,
          size: params.size ?? 20,
          agentId: params.agentId,
          status: params.status,
          createdFrom: params.createdFrom,
          createdTo: params.createdTo,
        },
      })
    },
    get(runId: string) {
      return http.get<AgentRunDetail>(`/api/v1/agent-runs/${encodeURIComponent(runId)}`)
    },
    create(input: CreateRunInput) {
      return http.post<AgentRunCreated>('/api/v1/agent-runs', {
        agentId: input.agentId,
        agentVersion: input.agentVersion,
        sessionId: input.sessionId,
        input: {
          text: input.text,
          parts: [],
          attachments: [],
          referenceUris: [],
        },
        responseMode: input.responseMode ?? 'STREAM',
        metadata: { channel: 'web' },
      })
    },
    cancel(runId: string) {
      return http.post<AgentRunCreated>(`/api/v1/agent-runs/${encodeURIComponent(runId)}/cancel`)
    },
    retry(runId: string) {
      return http.post<AgentRunCreated>(`/api/v1/agent-runs/${encodeURIComponent(runId)}/retry`)
    },
  }
}

export type RunAdapter = ReturnType<typeof createRunAdapter>
