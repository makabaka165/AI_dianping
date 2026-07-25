import type { RouteObject } from 'react-router-dom'
import { ConsumerLayout } from '@/app/layouts/ConsumerLayout'
import { RequireSession } from '@/app/routes/guards/RequireSession'
import { RoutePlaceholder } from '@/app/routes/RoutePlaceholder'
import { AccountPage } from '@/modules/account'

export const consumerRoutes: RouteObject = {
  path: '/',
  element: (
    <RequireSession>
      <ConsumerLayout />
    </RequireSession>
  ),
  children: [
    {
      index: true,
      element: (
        <RoutePlaceholder
          title="店铺发现"
          description="当前暂无可展示的店铺内容。"
        />
      ),
    },
    {
      path: 'community',
      element: (
        <RoutePlaceholder
          title="社区"
          description="当前暂无可展示的社区内容。"
        />
      ),
    },
    {
      path: 'account',
      element: <AccountPage />,
    },
    {
      path: 'assistant',
      element: (
        <RoutePlaceholder
          title="店铺助手"
          description="当前账户暂不可使用店铺助手。"
        />
      ),
    },
  ],
}
