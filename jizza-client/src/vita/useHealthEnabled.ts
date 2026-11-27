import { useQuery } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { AgentDto } from '@/types'
import { vitaKeys } from './queryKeys'

/** Vita is the built-in agent with this key; its name is the user's to change, the key is not. */
export const HEALTH_AGENT_KEY = 'health'

/**
 * Whether this agent's page shows the Health tab: only Vita, and only while the server's health
 * switch is on. A switched-off server answers 404, which simply means "no tab"; it is not retried.
 */
export function useHealthEnabled(agent: Pick<AgentDto, 'key'> | undefined): boolean {
  const isVita = agent?.key === HEALTH_AGENT_KEY
  const { data } = useQuery({
    queryKey: vitaKeys.status,
    queryFn: vitaApi.status,
    enabled: isVita,
    retry: false,
    staleTime: 5 * 60_000,
  })
  return isVita && data?.enabled === true
}
