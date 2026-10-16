import { api } from '@/lib/api'
import type { AuthorizationSnapshot, Invitation, Member, WorkspaceRole, WorkspaceSummary } from '@/types'

/** The active organization: its members and membership requests. */
export const workspacesApi = {
  /** Needs a fresh step-up. The caller becomes its Owner; it is not made active. */
  create: (name: string) =>
    api.post<WorkspaceSummary>('/api/workspaces', { name }),

  /** Leaves the active organization. The answer is the Personal account's snapshot. */
  leave: () =>
    api.delete<AuthorizationSnapshot>('/api/workspaces/current/membership'),

  members: () =>
    api.get<Member[]>('/api/workspaces/current/members'),

  invitations: () =>
    api.get<Invitation[]>('/api/workspaces/current/invitations'),

  /** Needs a fresh step-up. Answers the same whether or not the email has an account. */
  invite: (email: string, role: WorkspaceRole) =>
    api.post<{ message: string }>('/api/workspaces/current/invitations', { email, role }),

  revokeInvitation: (invitationId: string) =>
    api.post<{ message: string }>(`/api/workspaces/current/invitations/${encodeURIComponent(invitationId)}/revoke`),

  /** Needs a fresh step-up. */
  changeRole: (memberId: string, role: WorkspaceRole) =>
    api.patch<Member>(`/api/workspaces/current/members/${encodeURIComponent(memberId)}`, { role }),

  /** Needs a fresh step-up. */
  removeMember: (memberId: string) =>
    api.delete<{ message: string }>(`/api/workspaces/current/members/${encodeURIComponent(memberId)}`),
}
