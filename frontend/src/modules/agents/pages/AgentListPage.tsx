import { Link } from 'react-router-dom'
import { Play } from 'lucide-react'
import { useRunnableAgents } from '@/modules/agents/queries/agent-queries'
import { EmptyState, ErrorState, LoadingState, PageShell } from '@/shared/ui/StateViews'
import { isCanonicalError } from '@/shared/contracts/common/canonical-error'

export function AgentListPage() {
  const query = useRunnableAgents()

  return (
    <PageShell title="可运行 Agent">
      {query.isLoading ? <LoadingState label="加载可运行 Agent…" /> : null}
      {query.isError ? (
        <ErrorState
          error={isCanonicalError(query.error) ? query.error : '加载失败'}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {query.data && query.data.items.length === 0 ? (
        <EmptyState title="当前 workspace 没有已发布 Agent" description="请先在管理端发布版本。" />
      ) : null}
      {query.data && query.data.items.length > 0 ? (
        <div className="surface table-scroll responsive-table-container">
          <table className="table data-table responsive-table agent-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>Code</th>
                <th>已发布版本</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {query.data.items.map((agent) => (
                <tr key={agent.id}>
                  <td className="table-primary" data-label="Agent">
                    <strong>{agent.name}</strong>
                    <div className="table-secondary">{agent.description}</div>
                  </td>
                  <td data-label="Code">
                    <code>{agent.code}</code>
                  </td>
                  <td className="table-nowrap" data-label="版本">
                    v{agent.publishedVersion}
                  </td>
                  <td className="table-nowrap" data-label="操作">
                    <Link
                      className="button secondary"
                      to={`/studio/runs/new?agentId=${encodeURIComponent(agent.id)}&version=${agent.publishedVersion}`}
                    >
                      <Play aria-hidden="true" size={16} />
                      创建 Run
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </PageShell>
  )
}
