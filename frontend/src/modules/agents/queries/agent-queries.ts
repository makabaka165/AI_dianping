import { useQuery } from '@tanstack/react-query'
import { useSession } from '@/app/providers/session-context'
import { createAgentAdapter } from '@/modules/agents/adapters/http-agent-adapter'
import { queryKeys } from '@/shared/query/query-keys'

export function useRunnableAgents(page = 1, size = 20) {
  const { http, snapshot } = useSession()
  const adapter = createAgentAdapter(http)
  return useQuery({
    queryKey: queryKeys.agents.runnable(snapshot.scope, { page, size }),
    queryFn: () => adapter.listRunnable(page, size),
    enabled: Boolean(snapshot.scope),
  })
}
