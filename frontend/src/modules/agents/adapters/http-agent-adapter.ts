import type { HttpTransport } from '@/shared/transport/http/http-transport'
import type { PageResponse } from '@/shared/contracts/common/page'
import type { RunnableAgent } from '@/modules/agents/contracts/agent-contract'

export function createAgentAdapter(http: HttpTransport) {
  return {
    listRunnable(page = 1, size = 20) {
      return http.get<PageResponse<RunnableAgent>>('/api/v1/runnable-agents', {
        query: { page, size },
      })
    },
  }
}

export type AgentAdapter = ReturnType<typeof createAgentAdapter>
