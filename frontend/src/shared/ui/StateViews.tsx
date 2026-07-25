import type { ReactNode } from 'react'
import { RefreshCw } from 'lucide-react'
import type { CanonicalError } from '@/shared/contracts/common/canonical-error'

export function LoadingState({ label = '加载中…' }: { label?: string }) {
  return (
    <div className="state-view" role="status" aria-live="polite">
      <p>{label}</p>
    </div>
  )
}

export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="state-view">
      <h2>{title}</h2>
      {description ? <p>{description}</p> : null}
    </div>
  )
}

export function ErrorState({
  error,
  onRetry,
}: {
  error: CanonicalError | Error | string
  onRetry?: () => void
}) {
  const message =
    typeof error === 'string'
      ? error
      : 'message' in error
        ? error.message
        : '发生未知错误'
  return (
    <div className="state-view state-view-error" role="alert">
      <h2>请求失败</h2>
      <p>{message}</p>
      {onRetry ? (
        <button className="button secondary" type="button" onClick={onRetry}>
          <RefreshCw aria-hidden="true" size={16} />
          重试
        </button>
      ) : null}
    </div>
  )
}

export function ForbiddenState({ description = '你没有访问该资源的权限。' }: { description?: string }) {
  return (
    <div className="state-view" role="alert">
      <h2>403 无权限</h2>
      <p>{description}</p>
    </div>
  )
}

export function NotFoundState({ description = '资源不存在或已不在当前 workspace。' }: { description?: string }) {
  return (
    <div className="state-view">
      <h2>404 未找到</h2>
      <p>{description}</p>
    </div>
  )
}

export function PageShell({
  title,
  actions,
  children,
}: {
  title: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="page-shell">
      <header className="page-shell__header">
        <h1>{title}</h1>
        {actions ? <div className="page-shell__actions">{actions}</div> : null}
      </header>
      <div className="page-shell__body">{children}</div>
    </section>
  )
}
