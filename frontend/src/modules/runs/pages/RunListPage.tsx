import { Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { useRunHistory } from '@/modules/runs/queries/run-queries'
import { EmptyState, ErrorState, LoadingState, PageShell } from '@/shared/ui/StateViews'
import { isCanonicalError } from '@/shared/contracts/common/canonical-error'

function formatTimestamp(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(date)
}

export function RunListPage() {
  const query = useRunHistory({ page: 1, size: 20 })

  return (
    <PageShell
      title="Run 历史"
      actions={
        <Link className="button secondary" to="/studio/runs/new">
          <Plus aria-hidden="true" size={16} />
          新建 Run
        </Link>
      }
    >
      {query.isLoading ? <LoadingState label="加载 Run 历史…" /> : null}
      {query.isError ? (
        <ErrorState
          error={isCanonicalError(query.error) ? query.error : '加载失败'}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {query.data && query.data.items.length === 0 ? (
        <EmptyState title="暂无 Run" description="从可运行 Agent 列表创建一个 Run。" />
      ) : null}
      {query.data && query.data.items.length > 0 ? (
        <div className="surface table-scroll responsive-table-container">
          <table className="table data-table responsive-table run-history-table">
            <thead>
              <tr>
                <th>Run</th>
                <th>Agent</th>
                <th>状态</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              {query.data.items.map((run) => (
                <tr key={run.runId}>
                  <td className="table-primary" data-label="Run">
                    <Link to={`/studio/runs/${run.runId}`}>{run.runId}</Link>
                  </td>
                  <td className="table-nowrap" data-label="Agent">
                    {run.agentId} v{run.agentVersion}
                  </td>
                  <td data-label="状态">
                    <span className="badge">{run.status}</span>
                  </td>
                  <td className="table-nowrap" data-label="创建时间">
                    {run.createdAt || run.queuedAt ? (
                      <time dateTime={run.createdAt || run.queuedAt || undefined}>
                        {formatTimestamp(run.createdAt || run.queuedAt)}
                      </time>
                    ) : (
                      '-'
                    )}
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
