import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { HttpTransport } from '@/shared/transport/http/http-transport'
import { StreamTransport } from '@/shared/stream/stream-transport'
import {
  SessionScope,
  createSessionStorageTokenAdapter,
  type SessionSnapshot,
  type SessionScopeValue,
} from '@/shared/session/session-scope'
import type { CanonicalError } from '@/shared/contracts/common/canonical-error'
import {
  SessionContext,
  type SessionContextValue,
} from '@/app/providers/session-context'

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [snapshot, setSnapshot] = useState<SessionSnapshot>({
    status: 'bootstrapping',
    token: null,
    user: null,
    memberships: [],
    scope: null,
    error: null,
  })
  const unauthorizedLock = useRef(false)

  const sessionRef = useRef<SessionScope | null>(null)
  const httpRef = useRef<HttpTransport | null>(null)

  if (!httpRef.current) {
    httpRef.current = new HttpTransport({
      getAccessToken: () => sessionRef.current?.getToken() ?? null,
      getScope: () => sessionRef.current?.getScope() ?? null,
      onUnauthorized: (error: CanonicalError) => {
        if (unauthorizedLock.current) return
        unauthorizedLock.current = true
        sessionRef.current?.invalidateLocalSession(error)
        queryClient.clear()
        unauthorizedLock.current = false
      },
    })
  }

  if (!sessionRef.current) {
    sessionRef.current = new SessionScope({
      http: httpRef.current,
      tokenStorage: createSessionStorageTokenAdapter(),
      onChange: setSnapshot,
      onScopeChange: (previous, next) => {
        if (
          previous?.tenantId !== next?.tenantId ||
          previous?.workspaceId !== next?.workspaceId
        ) {
          queryClient.removeQueries()
        }
      },
    })
  }

  const stream = useMemo(
    () =>
      new StreamTransport({
        getAccessToken: () => sessionRef.current?.getToken() ?? null,
        getScope: () => sessionRef.current?.getScope() ?? null,
      }),
    [],
  )

  useEffect(() => {
    void sessionRef.current?.restore().catch(() => {
      // restore failures are reflected in snapshot.error
    })
  }, [])

  const loginWithToken = useCallback(async (token: string) => {
    unauthorizedLock.current = false
    return sessionRef.current!.loginWithToken(token)
  }, [])

  const selectScope = useCallback(async (scope: SessionScopeValue) => {
    return sessionRef.current!.selectScope(scope)
  }, [])

  const logout = useCallback(async () => {
    await sessionRef.current!.logout()
    queryClient.clear()
  }, [queryClient])

  const bootstrap = useCallback(async () => sessionRef.current!.bootstrap(), [])

  const value = useMemo<SessionContextValue>(
    () => ({
      snapshot,
      session: sessionRef.current!,
      http: httpRef.current!,
      stream,
      can: (permission: string) => sessionRef.current!.can(permission),
      loginWithToken,
      selectScope,
      logout,
      bootstrap,
    }),
    [snapshot, stream, loginWithToken, selectScope, logout, bootstrap],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
