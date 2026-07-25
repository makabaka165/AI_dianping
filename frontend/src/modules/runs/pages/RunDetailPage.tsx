import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { useSession } from '@/app/providers/session-context'
import { createRunAdapter } from '@/modules/runs/adapters/http-run-adapter'
import { createAgentRunClient } from '@/modules/runs/lib/agent-run-client'
import {
  extractFallbackReason,
  extractResponseBlocks,
  isTerminalRunStatus,
} from '@/modules/runs/lib/map-run-output'
import { useRunDetail } from '@/modules/runs/queries/run-queries'
import { ResponseBlockRenderer } from '@/modules/runs/ui/ResponseBlockRenderer'
import type { AgentRunObservation } from '@/modules/runs/lib/agent-run-client'
import type { AgentRunEvent as RunEvent } from '@/modules/runs/contracts/run-contract'
import { ErrorState, LoadingState, PageShell } from '@/shared/ui/StateViews'
import { isCanonicalError } from '@/shared/contracts/common/canonical-error'

export function RunDetailPage() {
  const { runId = '' } = useParams()
  const { http, stream } = useSession()
  const detailQuery = useRunDetail(runId)
  const [observation, setObservation] = useState<AgentRunObservation | null>(null)
  const [observeError, setObserveError] = useState<string | null>(null)

  useEffect(() => {
    if (!runId) return
    const controller = new AbortController()
    const adapter = createRunAdapter(http)
    const client = createAgentRunClient(adapter, stream)
    setObserveError(null)
    void client
      .observe(runId, {
        signal: controller.signal,
        onUpdate: setObservation,
      })
      .catch((error) => {
        setObserveError(error instanceof Error ? error.message : '观察失败')
      })
    return () => controller.abort()
  }, [http, runId, stream])

  const detail = observation?.detail ?? detailQuery.data ?? null
  const events: RunEvent[] = observation?.events ?? []
  const blocks = useMemo(() => extractResponseBlocks(detail?.output), [detail?.output])
  const fallbackReason = useMemo(() => extractFallbackReason(detail?.output), [detail?.output])

  if (detailQuery.isLoading && !detail) return <LoadingState label="加载 Run 详情…" />
  if (detailQuery.isError && !detail) {
    return (
      <ErrorState
        error={isCanonicalError(detailQuery.error) ? detailQuery.error : '加载失败'}
        onRetry={() => void detailQuery.refetch()}
      />
    )
  }

  return (
    <PageShell
      title={`Run ${runId}`}
      actions={
        <Link className="button secondary" to="/studio/runs">
          <ArrowLeft aria-hidden="true" size={16} />
          返回列表
        </Link>
      }
    >
      <div className="surface stack" style={{ padding: '1rem' }}>
        <div className="cluster">
          <span className="badge">{observation?.status || detail?.status || 'UNKNOWN'}</span>
          {observation?.observing ? <span className="badge">观察中</span> : null}
          {detail && isTerminalRunStatus(detail.status) ? <span className="badge">终态</span> : null}
        </div>
        <p>
          Agent: {detail?.agentId} v{detail?.agentVersion}
        </p>
        {detail?.errorMessage ? <p className="field-error">{detail.errorMessage}</p> : null}
        {observeError ? (
          <p role="status">观测中断：{observeError}（不等于 Run 失败，以下以详情接口为准）</p>
        ) : null}
      </div>

      <section className="stack">
        <h2>生命周期事件</h2>
        <div className="surface" style={{ padding: '1rem' }}>
          {events.length === 0 ? <p>暂无事件，或尚未建立 SSE。</p> : null}
          <ol className="stack">
            {events.map((event) => (
              <li key={`${event.sequence}-${event.type}`}>
                <code>
                  #{event.sequence} {event.type}
                </code>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="stack">
        <h2>最终输出</h2>
        <ResponseBlockRenderer blocks={blocks} fallbackReason={fallbackReason} />
      </section>
    </PageShell>
  )
}
