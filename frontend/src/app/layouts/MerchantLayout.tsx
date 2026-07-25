import { Outlet } from 'react-router-dom'
import { ShellChrome } from '@/app/layouts/ShellChrome'

export function MerchantLayout() {
  return (
    <div className="merchant-shell">
      <ShellChrome
        brand="商家工作台"
        navItems={[
          { to: '/merchant', label: '概览' },
          { to: '/merchant/content', label: '内容' },
          { to: '/merchant/vouchers', label: '优惠券' },
        ]}
      >
        <Outlet />
      </ShellChrome>
    </div>
  )
}
