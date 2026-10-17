import { useQuery } from '@tanstack/react-query'
import { agentsApi } from '@/api/agents'

/** Agent id to its character key ("finance", "health"…), so events can show each agent's logo. */
export function useAgentKeys(): Record<string, string> {
  const { data } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })
  return Object.fromEntries((data ?? []).map(a => [a.id, a.key]))
}
