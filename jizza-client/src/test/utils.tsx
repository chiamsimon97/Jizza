import type { ReactElement, ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { render } from '@testing-library/react'
import { CONTEXT_KEY } from '@/auth/useAuthorization'
import { useAuthStore } from '@/store/authStore'
import type { AuthorizationSnapshot, WorkspaceRole, WorkspaceType } from '@/types'

const PERSONAL = { id: 'ws-personal', type: 'PERSONAL' as const, name: 'Personal', role: 'OWNER' as const }
const ACME = { id: 'ws-acme', type: 'ORGANIZATION' as const, name: 'Acme', role: 'OWNER' as WorkspaceRole }
const RAOS = { id: 'ws-family', type: 'FAMILY' as const, name: 'The Raos', role: 'OWNER' as WorkspaceRole }
const FAMILY_MEMBERS = ['members_view', 'members_invite', 'members_modify', 'members_remove']

/** What the server grants each role, per the shipped permission file. */
const GRANTS: Record<WorkspaceType, Record<WorkspaceRole, string[]>> = {
  PERSONAL: {
    OWNER: ['connector_view', 'connector_create', 'connector_modify', 'connector_delete',
      'device_view', 'device_pair', 'device_use', 'device_manage'],
    ADMIN: [], DEVELOPER: [], USER: [],
  },
  ORGANIZATION: {
    OWNER: ['connector_view', 'connector_create', 'connector_modify', 'connector_delete',
      'members_view', 'members_invite', 'members_modify', 'members_remove',
      'guardrails_view', 'guardrails_lock', 'listing_manage'],
    ADMIN: ['connector_view', 'connector_create', 'connector_modify', 'connector_delete',
      'members_view', 'members_invite', 'members_modify', 'members_remove',
      'guardrails_view', 'guardrails_lock', 'listing_manage'],
    DEVELOPER: ['connector_view', 'connector_create', 'connector_modify', 'connector_delete', 'members_view',
      'guardrails_view'],
    USER: ['members_view', 'guardrails_view'],
  },
  FAMILY: { OWNER: FAMILY_MEMBERS, ADMIN: FAMILY_MEMBERS, DEVELOPER: [], USER: ['members_view'] },
}

export function makeSnapshot(
  type: WorkspaceType = 'PERSONAL',
  role: WorkspaceRole = 'OWNER',
  overrides: Partial<AuthorizationSnapshot> = {},
): AuthorizationSnapshot {
  const active = type === 'PERSONAL' ? PERSONAL : type === 'FAMILY' ? { ...RAOS, role } : { ...ACME, role }
  const family = type === 'FAMILY'
  return {
    userId: 'user-1',
    email: 'me@example.com',
    platformRole: 'NONE',
    activeWorkspace: active,
    role,
    permissions: GRANTS[type][role],
    assignableRoles: family
      ? (role === 'OWNER' ? ['ADMIN', 'USER'] : role === 'ADMIN' ? ['USER'] : [])
      : role === 'OWNER' ? ['ADMIN', 'DEVELOPER', 'USER'] : role === 'ADMIN' ? ['DEVELOPER', 'USER'] : [],
    workspaces: family
      ? [PERSONAL, { ...RAOS, role }]
      : [PERSONAL, { ...ACME, role: type === 'ORGANIZATION' ? role : 'USER' }],
    pendingInvitationCount: 0,
    permissionsVersion: 'v1',
    ...overrides,
  }
}

/** A query client that never refetches on its own, holding the given snapshot. */
export function testQueryClient(snapshot?: AuthorizationSnapshot) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  })
  if (snapshot) client.setQueryData(CONTEXT_KEY, snapshot)
  return client
}

export function wrapperFor(client: QueryClient, route = '/') {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }
}

/** Renders signed in, with the given snapshot already loaded. */
export function renderWithSnapshot(ui: ReactElement, snapshot?: AuthorizationSnapshot, route = '/') {
  useAuthStore.setState({ isAuthenticated: true, userId: 'user-1', enrollmentRequired: false })
  const client = testQueryClient(snapshot)
  return { client, ...render(ui, { wrapper: wrapperFor(client, route) }) }
}
