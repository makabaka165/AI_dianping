import { useSession } from '@/app/providers/session-context'
import { PageShell } from '@/shared/ui/StateViews'

export function AccountPage() {
  const { snapshot, can } = useSession()
  return (
    <PageShell title="账户">
      <div className="surface" style={{ padding: '1rem' }}>
        <div className="stack">
          <div>
            <strong>用户</strong>
            <p>
              {snapshot.user?.nickName} (#{snapshot.user?.id})
            </p>
          </div>
          <div>
            <strong>当前 scope</strong>
            <p>
              {snapshot.scope
                ? `${snapshot.scope.tenantId} / ${snapshot.scope.workspaceId}`
                : '未选择'}
            </p>
          </div>
          <div>
            <strong>当前权限</strong>
            <p>
              {snapshot.memberships
                .find(
                  (item) =>
                    item.tenant.id === snapshot.scope?.tenantId &&
                    item.workspace.id === snapshot.scope?.workspaceId,
                )
                ?.permissions.join(', ') || '无'}
            </p>
          </div>
          <div>
            <strong>能力探测</strong>
            <p>AGENT_RUN: {can('AGENT_RUN') ? '是' : '否'}</p>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
