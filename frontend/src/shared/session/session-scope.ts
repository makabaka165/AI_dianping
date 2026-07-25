import type { HttpTransport } from '@/shared/transport/http/http-transport'
import { createCanonicalError, type CanonicalError } from '@/shared/contracts/common/canonical-error'

export type SessionUser = {
  id: string
  nickName: string
  icon: string
}

export type SessionMembership = {
  tenant: { id: string; name: string }
  workspace: { id: string; name: string }
  roles: string[]
  permissions: string[]
  isDefault: boolean
  status: string
}

export type SessionScopeValue = {
  tenantId: string
  workspaceId: string
}

export type SessionSnapshot = {
  status: 'anonymous' | 'bootstrapping' | 'ready' | 'error'
  token: string | null
  user: SessionUser | null
  memberships: SessionMembership[]
  scope: SessionScopeValue | null
  error: CanonicalError | null
}

export type TokenStorage = {
  get(): string | null
  set(token: string): void
  clear(): void
}

export type SessionScopeOptions = {
  http: HttpTransport
  tokenStorage: TokenStorage
  onChange?: (snapshot: SessionSnapshot) => void
  onScopeChange?: (previous: SessionScopeValue | null, next: SessionScopeValue | null) => void
}

const ANONYMOUS: SessionSnapshot = {
  status: 'anonymous',
  token: null,
  user: null,
  memberships: [],
  scope: null,
  error: null,
}

export class SessionScope {
  private snapshot: SessionSnapshot = { ...ANONYMOUS }
  private readonly http: HttpTransport
  private readonly tokenStorage: TokenStorage
  private readonly onChange?: (snapshot: SessionSnapshot) => void
  private readonly onScopeChange?: (previous: SessionScopeValue | null, next: SessionScopeValue | null) => void
  private bootstrapPromise: Promise<SessionSnapshot> | null = null

  constructor(options: SessionScopeOptions) {
    this.http = options.http
    this.tokenStorage = options.tokenStorage
    this.onChange = options.onChange
    this.onScopeChange = options.onScopeChange
  }

  getSnapshot(): SessionSnapshot {
    return this.snapshot
  }

  getToken(): string | null {
    return this.snapshot.token ?? this.tokenStorage.get()
  }

  getScope(): SessionScopeValue | null {
    return this.snapshot.scope
  }

  can(permission: string): boolean {
    const scope = this.snapshot.scope
    if (!scope) return false
    const membership = this.snapshot.memberships.find(
      (item) => item.tenant.id === scope.tenantId && item.workspace.id === scope.workspaceId,
    )
    if (!membership || membership.status !== 'ACTIVE') return false
    return membership.permissions.includes(permission) || membership.permissions.includes('ADMIN')
  }

  async restore(): Promise<SessionSnapshot> {
    const token = this.tokenStorage.get()
    if (!token) {
      this.setSnapshot({ ...ANONYMOUS })
      return this.snapshot
    }
    this.setSnapshot({
      ...this.snapshot,
      status: 'bootstrapping',
      token,
      error: null,
    })
    return this.bootstrap()
  }

  async loginWithToken(token: string): Promise<SessionSnapshot> {
    this.tokenStorage.set(token)
    this.setSnapshot({
      ...ANONYMOUS,
      status: 'bootstrapping',
      token,
    })
    return this.bootstrap()
  }

  async bootstrap(): Promise<SessionSnapshot> {
    if (this.bootstrapPromise) return this.bootstrapPromise
    this.bootstrapPromise = this.doBootstrap().finally(() => {
      this.bootstrapPromise = null
    })
    return this.bootstrapPromise
  }

  async selectScope(scope: SessionScopeValue): Promise<SessionSnapshot> {
    const membership = this.snapshot.memberships.find(
      (item) => item.tenant.id === scope.tenantId && item.workspace.id === scope.workspaceId,
    )
    if (!membership || membership.status !== 'ACTIVE') {
      throw createCanonicalError({
        code: 'SCOPE_NOT_AVAILABLE',
        message: 'Selected workspace is not available for the current user',
        retryable: false,
      })
    }
    const previous = this.snapshot.scope
    this.setSnapshot({
      ...this.snapshot,
      scope,
      status: 'ready',
      error: null,
    })
    this.onScopeChange?.(previous, scope)
    return this.snapshot
  }

  async logout(): Promise<void> {
    const token = this.getToken()
    try {
      if (token) {
        await this.http.post('/user/logout', undefined, { auth: true, scope: false })
      }
    } catch {
      // logout should still clear local session even if server call fails
    } finally {
      this.invalidateLocalSession()
    }
  }

  invalidateLocalSession(error?: CanonicalError): void {
    this.tokenStorage.clear()
    const previous = this.snapshot.scope
    this.setSnapshot({
      ...ANONYMOUS,
      status: error ? 'error' : 'anonymous',
      error: error ?? null,
    })
    this.onScopeChange?.(previous, null)
  }

  private async doBootstrap(): Promise<SessionSnapshot> {
    try {
      const payload = await this.http.get<{
        user: SessionUser
        memberships: SessionMembership[]
        defaultScope?: SessionScopeValue | null
      }>('/api/v1/session/bootstrap', { auth: true, scope: false })

      const memberships = payload.memberships ?? []
      const defaultScope = payload.defaultScope ?? null
      const previous = this.snapshot.scope
      this.setSnapshot({
        status: 'ready',
        token: this.getToken(),
        user: payload.user,
        memberships,
        scope: defaultScope,
        error: null,
      })
      if (previous?.tenantId !== defaultScope?.tenantId || previous?.workspaceId !== defaultScope?.workspaceId) {
        this.onScopeChange?.(previous, defaultScope)
      }
      return this.snapshot
    } catch (error) {
      const canonical =
        typeof error === 'object' && error && 'code' in error
          ? (error as CanonicalError)
          : createCanonicalError({
              code: 'BOOTSTRAP_FAILED',
              message: 'Failed to bootstrap session',
              retryable: true,
            })
      if (canonical.status === 401 || canonical.code === 'AI_UNAUTHENTICATED' || canonical.code === '40100') {
        this.invalidateLocalSession(canonical)
      } else {
        this.setSnapshot({
          ...this.snapshot,
          status: 'error',
          error: canonical,
        })
      }
      throw canonical
    }
  }

  private setSnapshot(next: SessionSnapshot): void {
    this.snapshot = next
    this.onChange?.(next)
  }
}

export function createSessionStorageTokenAdapter(storage: Storage = sessionStorage): TokenStorage {
  const KEY = 'hmdp.auth.token'
  return {
    get() {
      try {
        return storage.getItem(KEY)
      } catch {
        return null
      }
    },
    set(token: string) {
      storage.setItem(KEY, token)
    },
    clear() {
      storage.removeItem(KEY)
    },
  }
}

export function createMemoryTokenAdapter(initial: string | null = null): TokenStorage {
  let token = initial
  return {
    get: () => token,
    set: (value) => {
      token = value
    },
    clear: () => {
      token = null
    },
  }
}
