import { useQuery } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { vitaKeys } from './queryKeys'

/**
 * Whether the signed-in person is linked to their own health record (a Family share marked "This
 * is them"), so the left menu lists "My medicines". Asks nothing while the health switch is off.
 */
export function useLinkedToOwnRecord(enabled: boolean): boolean {
  const status = useQuery({ queryKey: vitaKeys.status, queryFn: vitaApi.status, enabled, retry: false, staleTime: 5 * 60_000 })
  const on = enabled && status.data?.enabled === true
  const shared = useQuery({ queryKey: vitaKeys.sharedWithMe, queryFn: vitaApi.sharedWithMe, enabled: on, retry: false, staleTime: 5 * 60_000 })
  return on && (shared.data ?? []).some(e => e.asSelf)
}
