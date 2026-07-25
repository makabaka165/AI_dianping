import { describe, expect, it, vi } from 'vitest'
import { HttpTransport } from '@/shared/transport/http/http-transport'
import { SessionScope, createMemoryTokenAdapter } from '@/shared/session/session-scope'

describe('SessionScope', () => {
  it('bootstraps memberships and default scope', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          user: { id: '1', nickName: 'owner', icon: '' },
          memberships: [
            {
              tenant: { id: 'default', name: 'Default' },
              workspace: { id: 'default', name: 'Default' },
              roles: ['OWNER'],
              permissions: ['AGENT_RUN', 'ADMIN'],
              isDefault: true,
              status: 'ACTIVE',
            },
          ],
          defaultScope: { tenantId: 'default', workspaceId: 'default' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    const tokenStorage = createMemoryTokenAdapter('token-1')
    const http = new HttpTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => tokenStorage.get(),
    })
    const session = new SessionScope({ http, tokenStorage })
    const snapshot = await session.loginWithToken('token-1')
    expect(snapshot.status).toBe('ready')
    expect(session.can('AGENT_RUN')).toBe(true)
    expect(session.getScope()).toEqual({ tenantId: 'default', workspaceId: 'default' })
  })

  it('handles users without memberships', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          user: { id: '2', nickName: 'guest', icon: '' },
          memberships: [],
          defaultScope: null,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    const tokenStorage = createMemoryTokenAdapter('token-2')
    const http = new HttpTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => tokenStorage.get(),
    })
    const session = new SessionScope({ http, tokenStorage })
    const snapshot = await session.loginWithToken('token-2')
    expect(snapshot.memberships).toEqual([])
    expect(snapshot.scope).toBeNull()
    expect(session.can('AGENT_RUN')).toBe(false)
  })

  it('clears local session on 401 bootstrap', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: false, code: 'AI_UNAUTHENTICATED' }), { status: 401 }),
    )
    const tokenStorage = createMemoryTokenAdapter('bad-token')
    const http = new HttpTransport({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      getAccessToken: () => tokenStorage.get(),
    })
    const session = new SessionScope({ http, tokenStorage })
    await expect(session.loginWithToken('bad-token')).rejects.toMatchObject({ status: 401 })
    expect(tokenStorage.get()).toBeNull()
    expect(session.getSnapshot().status).toBe('error')
  })
})
