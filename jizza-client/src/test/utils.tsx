import type { ReactElement, ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { render } from '@testing-library/react'
import { CONTEXT_KEY } from '@/auth/useAuthorization'
import { useAuthStore } from '@/store/authStore'
import type { AuthorizationSnapshot, WorkspaceRole, WorkspaceType } from '@/types'

const PERSONAL = { id: 'ws-personal', type: 'PERSONAL' as const, name: 'Personal', role: 'OWNER' as const }
const ACME = { id: 'ws-acme', type: 'ORGANIZATION' as const, name: 'Acme', role: 'OWNER' as WorkspaceRole }

/** What the server grants each role, per the shipped permission file. */
const GRANTS: Record<WorkspaceType, Record<WorkspaceRole, string[]>> = {
  PERSONAL: {
    OWNER: ['connector_view', 'connector_create', 'connector_modify', 'connector_delete'],
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
}

export function makeSnapshot(
  type: WorkspaceType = 'PERSONAL',
  role: WorkspaceRole = 'OWNER',
  overrides: Partial<AuthorizationSnapshot> = {},
): AuthorizationSnapshot {
  const active = type === 'PERSONAL' ? PERSONAL : { ...ACME, role }
  return {
    userId: 'user-1',
    email: 'me@example.com',
    platformRole: 'NONE',
    activeWorkspace: active,
    role,
    permissions: GRANTS[type][role],
    assignableRoles: role === 'OWNER' ? ['ADMIN', 'DEVELOPER', 'USER'] : role === 'ADMIN' ? ['DEVELOPER', 'USER'] : [],
    workspaces: [PERSONAL, { ...ACME, role: type === 'ORGANIZATION' ? role : 'USER' }],
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
