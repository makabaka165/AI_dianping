import { NavLink } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import type { ReactNode } from 'react'
import { useSession } from '@/app/providers/session-context'

export type ShellNavItem = {
  to: string
  label: string
  permission?: string
}

export function ShellChrome({
  brand,
  navItems,
  children,
}: {
  brand: string
  navItems: ShellNavItem[]
  children: ReactNode
}) {
  const { snapshot, can, selectScope, logout } = useSession()
  const visibleNav = navItems.filter((item) => !item.permission || can(item.permission))

  return (
    <div className="app-shell">
      <aside className="app-shell__sidebar">
        <div className="app-shell__brand">{brand}</div>
        <nav className="app-shell__nav" aria-label="主导航">
          {visibleNav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/' || item.to === '/merchant' || item.to === '/studio'}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="app-shell__main">
        <div className="app-shell__topbar">
          <div className="cluster">
            <span className="badge">{snapshot.user?.nickName || '未登录'}</span>
            {snapshot.memberships.length > 0 ? (
              <label className="field workspace-selector">
                <span className="sr-only">Workspace</span>
                <select
                  aria-label="选择 workspace"
                  value={
                    snapshot.scope
                      ? `${snapshot.scope.tenantId}::${snapshot.scope.workspaceId}`
                      : ''
                  }
                  onChange={(event) => {
                    const [tenantId, workspaceId] = event.target.value.split('::')
                    if (tenantId && workspaceId) {
                      void selectScope({ tenantId, workspaceId })
                    }
                  }}
                >
                  <option value="" disabled>
                    选择 workspace
                  </option>
                  {snapshot.memberships
                    .filter((item) => item.status === 'ACTIVE')
                    .map((item) => (
                      <option
                        key={`${item.tenant.id}-${item.workspace.id}`}
                        value={`${item.tenant.id}::${item.workspace.id}`}
                      >
                        {item.tenant.name} / {item.workspace.name}
                      </option>
                    ))}
                </select>
              </label>
            ) : null}
          </div>
          <div className="cluster">
            {snapshot.token ? (
              <button type="button" className="button secondary" onClick={() => void logout()}>
                <LogOut aria-hidden="true" size={16} />
                退出登录
              </button>
            ) : null}
          </div>
        </div>
        {children}
      </div>
    </div>
  )
}
