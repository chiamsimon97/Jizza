import { api } from '@/lib/api'
import type { LoginResult, MfaSetup, SecurityStatus, SessionDto, WorkspaceType } from '@/types'

export const authApi = {
  register: (body: {
    email: string
    password: string
    accountType: WorkspaceType
    organizationName?: string
    invitationToken?: string
  }) =>
    api.post<{ message: string }>('/api/auth/register', body),

  /** What an invitation sign-up link is for. Public; 404 when the link is unknown or used. */
  invitation: (token: string) =>
    api.get<{ email: string; workspaceName: string }>(`/api/auth/invitations/${encodeURIComponent(token)}`),

  login: (body: { email: string; password: string }) =>
    api.post<LoginResult>('/api/auth/login', body),

  /** `code` is the 6-digit authenticator code or a one-time recovery code. */
  verifyMfa: (body: { mfaToken: string; code: string }) =>
    api.post<LoginResult>('/api/auth/mfa/verify', body),

  me: () =>
    api.get<SecurityStatus>('/api/auth/me'),

  setupMfa: () =>
    api.post<MfaSetup>('/api/auth/mfa/setup'),

  confirmMfa: (code: string) =>
    api.post<{ codes: string[] }>('/api/auth/mfa/confirm', { code }),

  regenerateRecoveryCodes: (code: string) =>
    api.post<{ codes: string[] }>('/api/auth/mfa/recovery-codes', { code }),

  disableMfa: (code: string) =>
    api.post<{ message: string }>('/api/auth/mfa/disable', { code }),

  stepUp: (password: string) =>
    api.post<void>('/api/auth/step-up', { password }),

  logout: () =>
    api.post<void>('/api/auth/logout'),

  logoutEverywhere: () =>
    api.post<void>('/api/auth/logout-everywhere'),

  sessions: () =>
    api.get<SessionDto[]>('/api/auth/sessions'),

  revokeSession: (sessionId: string) =>
    api.delete<void>(`/api/auth/sessions/${encodeURIComponent(sessionId)}`),

  requestReset: (email: string) =>
    api.post<void>('/api/auth/recover', { email }),

  completeReset: (token: string, newPassword: string) =>
    api.post<void>('/api/auth/recover/complete', { token, newPassword }),

  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<{ message: string }>('/api/auth/password', { currentPassword, newPassword }),
}
