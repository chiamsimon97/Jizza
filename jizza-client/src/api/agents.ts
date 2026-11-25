import { api } from '@/lib/api'
import type {
  AgentCreate, AgentDependent, AgentDto, AgentEdit, AgentGalleryItem, AgentGrant, AgentRevisionDto,
  AgentTemplateUpdate, AgentToolOption, AgentTrial, ArchiveResult, ModeLine, OrgAgentTemplate, TeamNode,
  AgentBlueprint, ToolKind,
} from '@/types'

export const agentsApi = {
  /** Every agent that isn't archived; pass a status (or 'all') for others. */
  list: (status?: 'archived' | 'draft' | 'active' | 'all') =>
    api.get<AgentDto[]>(status ? `/api/agents?status=${status}` : '/api/agents'),

  get: (id: string) => api.get<AgentDto>(`/api/agents/${id}`),

  /** Whether the agent may search the web and read pages; {@code available} is false when the server has web tools off. */
  web: (id: string) => api.get<{ enabled: boolean; available: boolean }>(`/api/agents/${id}/web`),

  setWeb: (id: string, enabled: boolean) =>
    api.put<{ enabled: boolean; available: boolean }>(`/api/agents/${id}/web`, { enabled }),

  /** The model this agent answers with by default; null lets Jizza pick. */
  model: (id: string) => api.get<{ model: string | null; label: string | null }>(`/api/agents/${id}/model`),

  /** Sets the agent's default model; 'auto' lets Jizza pick again. */
  setModel: (id: string, model: string) =>
    api.put<{ model: string | null; label: string | null }>(`/api/agents/${id}/model`, { model }),

  create: (body: AgentCreate) => api.post<AgentDto>('/api/agents', body),

  /** A gallery team or one Jizza proposed, under a manager (Jizza when left out). */
  createTeam: (body: { galleryId?: string; definition?: { agents: AgentBlueprint[] }; parentAgentId?: string | null }) =>
    api.post<AgentDto[]>('/api/agents/teams', body),

  patch: (id: string, patch: AgentEdit) => api.patch<AgentDto>(`/api/agents/${id}`, patch),

  activate: (id: string) => api.post<AgentDto>(`/api/agents/${id}/activate`),

  tree: () => api.get<TeamNode>('/api/agents/tree'),

  gallery: () => api.get<AgentGalleryItem[]>('/api/agents/gallery'),

  toolOptions: () => api.get<AgentToolOption[]>('/api/agents/tool-options'),

  suggestTools: (description: string, hints: string[] = []) =>
    api.post<AgentToolOption[]>('/api/agents/tool-suggestions', { description, hints }),

  improve: (body: { name?: string; description?: string; instructions: string }) =>
    api.post<{ instructions: string }>('/api/agents/improve-instructions', body),

  revisions: (id: string) => api.get<AgentRevisionDto[]>(`/api/agents/${id}/revisions`),

  restoreRevision: (id: string, revision: number) =>
    api.post<AgentDto>(`/api/agents/${id}/revisions/${revision}/restore`),

  dependents: (id: string) => api.get<AgentDependent[]>(`/api/agents/${id}/dependents`),

  archive: (id: string, options: { reports?: 'move_up' | 'pause'; reassignTo?: string | null }) =>
    api.post<ArchiveResult>(`/api/agents/${id}/archive`, options),

  restore: (id: string) => api.post<AgentDto>(`/api/agents/${id}/restore`),

  remove: (id: string) => api.delete<{ message: string }>(`/api/agents/${id}`),

  setManager: (id: string, managerId: string) => api.put<AgentDto>(`/api/agents/${id}/manager`, { managerId }),

  tryOut: (id: string, message: string) => api.post<AgentTrial>(`/api/agents/${id}/try`, { message }),

  modes: (id: string) => api.get<ModeLine[]>(`/api/agents/${id}/modes`),

  spend: (id: string) => api.get<{ spentThisMonth: number; monthlyCostLimit?: number | null }>(`/api/agents/${id}/spend`),

  grants: (id: string) => api.get<AgentGrant[]>(`/api/agents/${id}/capabilities`),

  grant: (id: string, type: ToolKind, capabilityRef: string) =>
    api.post<{ message: string }>(`/api/agents/${id}/capabilities`, { type, capabilityRef }),

  revoke: (id: string, type: ToolKind, ref: string) =>
    api.delete<{ message: string }>(`/api/agents/${id}/capabilities/${type}/${ref}`),

  // ── Organization templates ──
  orgTemplates: () => api.get<OrgAgentTemplate[]>('/api/agent-templates'),
  installTemplate: (id: string, parentAgentId?: string | null) =>
    api.post<AgentDto[]>(`/api/agent-templates/${id}/install`, { parentAgentId }),
  templateUpdates: () => api.get<AgentTemplateUpdate[]>('/api/agent-templates/updates'),
  applyTemplateUpdate: (agentId: string) => api.post<AgentDto>(`/api/agents/${agentId}/template-update`),
}

/** An organization's own templates, for its owners and admins. */
export const orgAgentTemplatesApi = {
  list: () => api.get<OrgAgentTemplate[]>('/api/organization/agent-templates'),
  create: (body: { name: string; description?: string; definition: { agents: AgentBlueprint[] } }) =>
    api.post<OrgAgentTemplate>('/api/organization/agent-templates', body),
  update: (id: string, body: { name?: string; description?: string; definition?: { agents: AgentBlueprint[] } }) =>
    api.put<OrgAgentTemplate>(`/api/organization/agent-templates/${id}`, body),
  publish: (id: string) => api.post<OrgAgentTemplate>(`/api/organization/agent-templates/${id}/publish`),
  unpublish: (id: string, pauseCopies: boolean) =>
    api.post<OrgAgentTemplate>(`/api/organization/agent-templates/${id}/unpublish`, { pauseCopies }),
  remove: (id: string) => api.delete<{ message: string }>(`/api/organization/agent-templates/${id}`),
}
