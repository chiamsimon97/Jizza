import { api } from '@/lib/api'
import type {
  AutonomyRuleDto, GuardrailDecision, GuardrailMode, GuardrailsOverview, QuietHours, WorkflowGuardrails,
} from '@/types'

/** A rule as the Guardrails screen saves it. With a workflow it covers that workflow's current version. */
export interface RuleInput {
  id?: string
  agentId?: string | null
  toolRef: string
  rule: GuardrailMode
  limits?: Record<string, unknown> | null
  precedence: number
  workflowId?: string | null
  nodeId?: string | null
  locked?: boolean
}

export interface SimulationInput {
  agentId: string
  toolRef: string
  args?: unknown
  workflowId?: string | null
  nodeId?: string | null
}

export const guardrailsApi = {
  overview: () => api.get<GuardrailsOverview>('/api/guardrails'),

  workflow: (workflowId: string) => api.get<WorkflowGuardrails>(`/api/guardrails/workflows/${workflowId}`),

  /** What would happen if this call were made now. Nothing runs and nothing is spent. */
  simulate: (input: SimulationInput) => api.post<GuardrailDecision>('/api/guardrails/simulate', input),

  saveRule: (rule: RuleInput) => api.put<AutonomyRuleDto>('/api/guardrails/rules', rule),

  deleteRule: (id: string) => api.delete<{ message: string }>(`/api/guardrails/rules/${id}`),

  saveQuietHours: (hours: QuietHours) => api.put<QuietHours>('/api/guardrails/quiet-hours', hours),

  /** Pauses or resumes every agent at once. */
  setPaused: (paused: boolean) => api.post<{ message: string }>('/api/autonomy/kill-switch', { paused }),

  organizationLocks: () => api.get<AutonomyRuleDto[]>('/api/guardrails/organization'),

  lockForOrganization: (lock: { id?: string; toolRef: string; rule: GuardrailMode }) =>
    api.put<AutonomyRuleDto>('/api/guardrails/organization', lock),

  unlockForOrganization: (id: string) => api.delete<{ message: string }>(`/api/guardrails/organization/${id}`),
}
