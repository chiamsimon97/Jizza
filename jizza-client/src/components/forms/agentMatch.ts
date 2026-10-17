import type { AgentDto } from '@/types'

/** Resolves what Jizza called an agent (its key or its name) to the agent itself. */
export function matchAgent(agents: AgentDto[] | undefined, ref: unknown): AgentDto | undefined {
  if (!agents || typeof ref !== 'string' || !ref.trim()) return undefined
  const r = ref.trim().toLowerCase()
  return agents.find(a => a.key.toLowerCase() === r || a.name.toLowerCase() === r || a.id === ref)
}

export function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}
