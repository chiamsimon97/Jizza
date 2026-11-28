import { useQuery } from '@tanstack/react-query'
import { meApi } from '@/api/me'
import { queryClient } from '@/lib/queryClient'
import { useAuthStore } from '@/store/authStore'
import type { AuthorizationSnapshot } from '@/types'

export const CONTEXT_KEY = ['me', 'context'] as const

/**
 * The signed-in person's snapshot for the active account: who they are, their role and the
 * permissions the server grants them. Only fetched once two-step sign-in is satisfied, since the
 * server refuses it before then. Refetched whenever the window regains focus, so a new membership
 * request or a role change shows up without a reload.
 */
export function useAuthorization() {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const enrollmentRequired = useAuthStore(s => s.enrollmentRequired)
  return useQuery({
    queryKey: CONTEXT_KEY,
    queryFn: meApi.getContext,
    enabled: isAuthenticated && !enrollmentRequired,
    refetchOnWindowFocus: 'always',
  })
}

/** The snapshot as last fetched, for code outside React (Jizza's page navigation). */
export function getCachedSnapshot(): AuthorizationSnapshot | undefined {
  return queryClient.getQueryData<AuthorizationSnapshot>(CONTEXT_KEY)
}

export { accountHome, hasMembers, isFamily, isOrganization } from '@/lib/account'
