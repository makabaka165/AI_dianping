import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from '@/app/providers/session-context'
import { LoadingState, ErrorState } from '@/shared/ui/StateViews'

export function RequireSession({ children }: { children: ReactNode }) {
  const { snapshot, bootstrap } = useSession()
  const location = useLocation()

  if (snapshot.status === 'bootstrapping') {
    return <LoadingState label="恢复登录态…" />
  }

  if (snapshot.status === 'error' && snapshot.token) {
    return <ErrorState error={snapshot.error ?? '会话恢复失败'} onRetry={() => void bootstrap()} />
  }

  if (!snapshot.token || snapshot.status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return children
}
