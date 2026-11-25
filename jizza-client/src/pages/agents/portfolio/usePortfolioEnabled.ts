import { useQuery } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { AgentDto } from '@/types'

/** Kai is the built-in agent with this key; its name is the user's to change, the key is not. */
export const FINANCE_AGENT_KEY = 'finance'

/**
 * Whether this agent's page shows the Portfolio tab: only Kai, and only while the server's finance
 * switch is on. A switched-off server answers 404, which simply means "no tab"; it is not retried.
 */
export function usePortfolioEnabled(agent: Pick<AgentDto, 'key'> | undefined): boolean {
  const isKai = agent?.key === FINANCE_AGENT_KEY
  const { data } = useQuery({
    queryKey: ['finance', 'status'],
    queryFn: financeApi.status,
    enabled: isKai,
    retry: false,
    staleTime: 5 * 60_000,
  })
  return isKai && data?.enabled === true
}
