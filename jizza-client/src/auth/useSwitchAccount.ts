import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { meApi } from '@/api/me'
import type { AuthorizationSnapshot } from '@/types'
import { CONTEXT_KEY, accountHome } from './useAuthorization'

/**
 * The account changed (switched, or left an organization): nothing cached for the previous account
 * may linger, so the whole cache goes and only the new snapshot is kept.
 */
export function applySnapshot(qc: QueryClient, snapshot: AuthorizationSnapshot) {
  qc.clear()
  qc.setQueryData(CONTEXT_KEY, snapshot)
}

/** Makes another account active, then opens that account's home. */
export function useSwitchAccount() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  return useMutation({
    mutationFn: (workspaceId: string) => meApi.switchWorkspace(workspaceId),
    onSuccess: snapshot => {
      applySnapshot(qc, snapshot)
      navigate(accountHome(snapshot), { replace: true })
    },
  })
}
