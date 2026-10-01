import { api } from '@/lib/api'
import type { LoginResult, MfaSetup, SessionDto } from '@/types'

export const authApi = {
  register: (body: { email: string; password: string; name?: string }) =>
    api.post<{ userId: string }>('/api/auth/register', body),

  login: (body: { email: string; password: string }) =>
    api.post<LoginResult>('/api/auth/login', body),

  verifyMfa: (body: { mfaToken: string; code: string }) =>
    api.post<LoginResult>('/api/auth/mfa/verify', body),

  setupMfa: () =>
    api.post<MfaSetup>('/api/auth/mfa/setup'),

  confirmMfa: (code: string) =>
    api.post<string[]>('/api/auth/mfa/confirm', { code }),

  stepUp: (password: string, code?: string) =>
    api.post<void>('/api/auth/step-up', { password, code }),

  logout: () =>
    api.post<void>('/api/auth/logout'),

  logoutEverywhere: () =>
    api.post<void>('/api/auth/logout-everywhere'),

  sessions: () =>
    api.get<SessionDto[]>('/api/auth/sessions'),

  revokeSession: (sessionId: string) =>
    api.delete<void>(`/api/auth/sessions/${sessionId}`),

  requestReset: (email: string) =>
    api.post<void>('/api/auth/recover', { email }),

  completeReset: (token: string, newPassword: string) =>
    api.post<void>('/api/auth/recover/complete', { token, newPassword }),

  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>('/api/auth/password', { currentPassword, newPassword }),
}
