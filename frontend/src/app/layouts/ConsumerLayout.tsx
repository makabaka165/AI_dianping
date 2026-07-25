import { Outlet } from 'react-router-dom'
import { ShellChrome } from '@/app/layouts/ShellChrome'
import { useSession } from '@/app/providers/session-context'

export function ConsumerLayout() {
  const { can } = useSession()
  return (
    <div className="consumer-shell">
      <ShellChrome
        brand="AI 点评"
        navItems={[
          { to: '/', label: '发现' },
          { to: '/community', label: '社区' },
          { to: '/account', label: '账户' },
          ...(can('AGENT_RUN') ? [{ to: '/assistant', label: '店铺助手', permission: 'AGENT_RUN' }] : []),
        ]}
      >
        <Outlet />
      </ShellChrome>
    </div>
  )
}
