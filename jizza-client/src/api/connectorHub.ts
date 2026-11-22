import { api } from '@/lib/api'
import type { ConnectorCard } from '@/types'

/** The Connectors hub: services, MCP servers and devices as one list, and who may use each. */
export const connectorHubApi = {
  list: () => api.get<ConnectorCard[]>('/api/connector-hub'),
  /** Asks for step-up. Agent keys; ['*'] gives it to every agent. */
  setAgents: (kind: ConnectorCard['kind'], id: string, agentKeys: string[]) =>
    api.put<ConnectorCard>(`/api/connector-hub/${kind}/${id}/agents`, { agentKeys }),
}
