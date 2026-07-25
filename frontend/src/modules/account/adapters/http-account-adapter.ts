import type { HttpTransport } from '@/shared/transport/http/http-transport'
import type { LoginFormValues, SendCodeResult } from '@/modules/account/contracts/account-contract'

export function createAccountAdapter(http: HttpTransport) {
  return {
    sendCode(phone: string) {
      return http.post<SendCodeResult | null>(`/user/code?phone=${encodeURIComponent(phone)}`, undefined, {
        auth: false,
        scope: false,
      })
    },
    login(values: LoginFormValues) {
      return http.post<string>('/user/login', values, {
        auth: false,
        scope: false,
      })
    },
  }
}

export type AccountAdapter = ReturnType<typeof createAccountAdapter>
