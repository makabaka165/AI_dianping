import type { ReactNode } from 'react'
import { useSession } from '@/app/providers/session-context'
import { ForbiddenState } from '@/shared/ui/StateViews'

export function RequirePermission({
  permission,
  children,
}: {
  permission: string
  children: ReactNode
}) {
  const { can } = useSession()
  if (!can(permission)) {
    return <ForbiddenState description={`需要权限 ${permission}。`} />
  }
  return children
}
