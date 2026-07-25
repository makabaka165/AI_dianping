import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '@/app/providers/session-context'
import { createRunAdapter } from '@/modules/runs/adapters/http-run-adapter'
import { queryKeys } from '@/shared/query/query-keys'
import type { CreateRunInput } from '@/modules/runs/contracts/run-contract'

export function useRunHistory(filters: {
  page?: number
  size?: number
  agentId?: string
  status?: string
} = {}) {
  const { http, snapshot } = useSession()
  const adapter = createRunAdapter(http)
  return useQuery({
    queryKey: queryKeys.runs.list(snapshot.scope, filters),
    queryFn: () => adapter.list(filters),
    enabled: Boolean(snapshot.scope),
  })
}

export function useRunDetail(runId?: string) {
  const { http, snapshot } = useSession()
  const adapter = createRunAdapter(http)
  return useQuery({
    queryKey: queryKeys.runs.detail(snapshot.scope, runId),
    queryFn: () => adapter.get(runId!),
    enabled: Boolean(snapshot.scope && runId),
  })
}

export function useCreateRunMutation() {
  const { http, snapshot } = useSession()
  const adapter = createRunAdapter(http)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateRunInput) => adapter.create(input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.runs.root(snapshot.scope) })
    },
  })
}
