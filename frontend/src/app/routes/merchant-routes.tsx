import type { RouteObject } from 'react-router-dom'
import { MerchantLayout } from '@/app/layouts/MerchantLayout'
import { RequireSession } from '@/app/routes/guards/RequireSession'
import { RoutePlaceholder } from '@/app/routes/RoutePlaceholder'

const merchantPlaceholderDescription =
  '当前暂无可管理的商家内容。'

export const merchantRoutes: RouteObject = {
  path: '/merchant',
  element: (
    <RequireSession>
      <MerchantLayout />
    </RequireSession>
  ),
  children: [
    {
      index: true,
      element: (
        <RoutePlaceholder title="商家概览" description={merchantPlaceholderDescription} />
      ),
    },
    {
      path: 'content',
      element: (
        <RoutePlaceholder title="商家内容" description={merchantPlaceholderDescription} />
      ),
    },
    {
      path: 'vouchers',
      element: (
        <RoutePlaceholder title="商家优惠券" description={merchantPlaceholderDescription} />
      ),
    },
  ],
}
