import { useRoutes } from 'react-router-dom'
import { consumerRoutes } from '@/app/routes/consumer-routes'
import { merchantRoutes } from '@/app/routes/merchant-routes'
import { studioRoutes } from '@/app/routes/studio-routes'
import { LoginPage } from '@/modules/account'
import { NotFoundState } from '@/shared/ui/StateViews'

export function AppRouter() {
  return useRoutes([
    { path: '/login', element: <LoginPage /> },
    consumerRoutes,
    merchantRoutes,
    studioRoutes,
    { path: '*', element: <NotFoundState /> },
  ])
}
