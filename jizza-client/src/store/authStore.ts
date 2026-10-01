import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthState {
  isAuthenticated: boolean
  userId?: string
  mfaPending: boolean
  mfaToken?: string
  setAuthenticated: (userId: string) => void
  setPendingMfa: (mfaToken: string) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      isAuthenticated: false,
      userId: undefined,
      mfaPending: false,
      mfaToken: undefined,

      setAuthenticated: (userId) =>
        set({ isAuthenticated: true, userId, mfaPending: false, mfaToken: undefined }),

      setPendingMfa: (mfaToken) =>
        set({ mfaPending: true, mfaToken }),

      clear: () =>
        set({ isAuthenticated: false, userId: undefined, mfaPending: false, mfaToken: undefined }),
    }),
    { name: 'jizza-auth', partialize: (s) => ({ isAuthenticated: s.isAuthenticated, userId: s.userId }) },
  ),
)
