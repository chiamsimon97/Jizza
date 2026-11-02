import { api } from '@/lib/api'
import type { LoginResult } from '@/types'

export type SmartSignInState = 'PENDING' | 'SCANNED' | 'APPROVED' | 'DENIED' | 'EXPIRED'

export interface SmartSignInStarted {
  id: string
  /** Goes into the QR, after the `#`. */
  code: string
  /** Stays in this browser; only it can collect the session once approved. */
  pollToken: string
  expiresAt: string
}

export interface SmartSignInDetails {
  id: string
  /** Such as "Chrome on Windows". */
  browser: string
  ip: string | null
  createdAt: string
  expiresAt: string
  /** The browser and this phone reach Jizza from the same address. */
  sameNetwork: boolean
  state: SmartSignInState
}

const POLL_HEADER = 'X-Smart-Sign-In-Token'
const base = '/api/auth/smart-sign-in'

/** The page a phone opens when it scans the QR. The code is in the fragment, never sent to a server. */
export function smartSignInUrl(code: string, origin = window.location.origin): string {
  return `${origin}/smart-sign-in#${code}`
}

export const smartSignInApi = {
  config: () => api.get<{ enabled: boolean }>(`${base}/config`),

  start: () => api.post<SmartSignInStarted>(`${base}/start`),

  status: (id: string, pollToken: string) =>
    api.get<{ state: SmartSignInState }>(`${base}/attempts/${encodeURIComponent(id)}`, { [POLL_HEADER]: pollToken }),

  complete: (id: string, pollToken: string) =>
    api.post<LoginResult>(`${base}/attempts/${encodeURIComponent(id)}/complete`, { pollToken }),

  cancel: (id: string, pollToken: string) =>
    api.delete<void>(`${base}/attempts/${encodeURIComponent(id)}`, { [POLL_HEADER]: pollToken }),

  /** On the phone: claims the code and returns what to show before approving. */
  scan: (code: string) => api.post<SmartSignInDetails>(`${base}/scan`, { code }),

  decide: (code: string, approve: boolean) =>
    api.post<{ state: SmartSignInState }>(`${base}/decide`, { code, approve }),
}
