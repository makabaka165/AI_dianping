import { createContext, useContext } from 'react'
import type { HttpTransport } from '@/shared/transport/http/http-transport'
import type { StreamTransport } from '@/shared/stream/stream-transport'
import type {
  SessionScope,
  SessionSnapshot,
  SessionScopeValue,
} from '@/shared/session/session-scope'

export type SessionContextValue = {
  snapshot: SessionSnapshot
  session: SessionScope
  http: HttpTransport
  stream: StreamTransport
  can: (permission: string) => boolean
  loginWithToken: (token: string) => Promise<SessionSnapshot>
  selectScope: (scope: SessionScopeValue) => Promise<SessionSnapshot>
  logout: () => Promise<void>
  bootstrap: () => Promise<SessionSnapshot>
}

export const SessionContext = createContext<SessionContextValue | null>(null)

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (!value) throw new Error('useSession must be used within SessionProvider')
  return value
}
