import { api } from '@/lib/api'
import type { AuthorizationSnapshot, PermissionDefinition, ReceivedInvitation, WorkspaceSummary } from '@/types'

export const meApi = {
  /** Who the signed-in person is in the active account, and what they may do there. */
  getContext: () =>
    api.get<AuthorizationSnapshot>('/api/me/context'),

  /** Makes another account active. The server issues a new session cookie. */
  switchWorkspace: (workspaceId: string) =>
    api.post<AuthorizationSnapshot>('/api/me/active-workspace', { workspaceId }),

  listInvitations: () =>
    api.get<ReceivedInvitation[]>('/api/me/invitations'),

  accept: (invitationId: string) =>
    api.post<WorkspaceSummary>(`/api/me/invitations/${encodeURIComponent(invitationId)}/accept`),

  decline: (invitationId: string) =>
    api.post<{ message: string }>(`/api/me/invitations/${encodeURIComponent(invitationId)}/decline`),

  /** Labels and descriptions for every permission name. */
  permissions: () =>
    api.get<PermissionDefinition[]>('/api/permissions'),
}
