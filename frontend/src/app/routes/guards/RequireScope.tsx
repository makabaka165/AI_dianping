import type { ReactNode } from 'react'
import { useSession } from '@/app/providers/session-context'
import { EmptyState, LoadingState } from '@/shared/ui/StateViews'

export function RequireScope({ children }: { children: ReactNode }) {
  const { snapshot } = useSession()

  if (snapshot.status === 'bootstrapping') {
    return <LoadingState label="加载工作空间…" />
  }

  if (!snapshot.scope) {
    return (
      <EmptyState
        title="尚未选择可用 Workspace"
        description={
          snapshot.memberships.length === 0
            ? '当前账号没有有效的 AI workspace membership。'
            : '请先在顶部选择一个 workspace。'
        }
      />
    )
  }

  return children
}
