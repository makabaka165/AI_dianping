export type ScopeKey = {
  tenantId: string
  workspaceId: string
}

function scopePart(scope?: ScopeKey | null): string[] {
  if (!scope) return ['no-scope']
  return [scope.tenantId, scope.workspaceId]
}

export const queryKeys = {
  session: {
    root: ['session'] as const,
    bootstrap: ['session', 'bootstrap'] as const,
  },
  agents: {
    root: (scope?: ScopeKey | null) => ['agents', ...scopePart(scope)] as const,
    runnable: (scope?: ScopeKey | null, filters?: Record<string, unknown>) =>
      ['agents', ...scopePart(scope), 'runnable', filters ?? {}] as const,
  },
  runs: {
    root: (scope?: ScopeKey | null) => ['runs', ...scopePart(scope)] as const,
    list: (scope?: ScopeKey | null, filters?: Record<string, unknown>) =>
      ['runs', ...scopePart(scope), 'list', filters ?? {}] as const,
    detail: (scope?: ScopeKey | null, runId?: string) =>
      ['runs', ...scopePart(scope), 'detail', runId ?? 'unknown'] as const,
  },
}
