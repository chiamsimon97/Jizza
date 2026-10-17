import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthState {
  isAuthenticated: boolean
  userId?: string
  /** Signed in, but two-step sign-in must be set up before the rest of the app opens. */
  enrollmentRequired: boolean
  mfaPending: boolean
  mfaToken?: string
  setAuthenticated: (userId: string, enrollmentRequired?: boolean) => void
  setEnrollmentRequired: (required: boolean) => void
  setPendingMfa: (mfaToken: string) => void
  clear: () => void
}

/**
 * Only whether the user is signed in is remembered across reloads. The session itself lives in an
 * httpOnly cookie that script cannot read, and the half-finished sign-in token (`mfaToken`) is
 * kept in memory only, never in storage.
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      isAuthenticated: false,
      userId: undefined,
      enrollmentRequired: false,
      mfaPending: false,
      mfaToken: undefined,

      setAuthenticated: (userId, enrollmentRequired = false) =>
        set({ isAuthenticated: true, userId, enrollmentRequired, mfaPending: false, mfaToken: undefined }),

      setEnrollmentRequired: (required) =>
        set({ enrollmentRequired: required }),

      setPendingMfa: (mfaToken) =>
        set({ mfaPending: true, mfaToken }),

      clear: () =>
        set({ isAuthenticated: false, userId: undefined, enrollmentRequired: false, mfaPending: false, mfaToken: undefined }),
    }),
    {
      name: 'jizza-auth',
      partialize: (s) => ({ isAuthenticated: s.isAuthenticated, userId: s.userId, enrollmentRequired: s.enrollmentRequired }),
    },
  ),
)
