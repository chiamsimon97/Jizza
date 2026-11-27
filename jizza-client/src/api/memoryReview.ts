import { api } from '@/lib/api'

export type ProposalKind = 'merge' | 'confirm' | 'retire' | 'update' | 'lesson'
export type ProposalStatus = 'open' | 'approved' | 'dismissed' | 'expired' | 'stale'

/** One pass of the memory review over the user's recent activity. */
export interface MemoryReview {
  id: string
  trigger: 'nightly' | 'manual'
  status: 'running' | 'done' | 'skipped' | 'failed'
  detail: string | null
  proposals: number
  windowStart: string
  windowEnd: string
  startedAt: string
  finishedAt: string | null
}

export interface ProposalEvidence {
  /** chat: something you said · tool: a failed tool call · step: a failed workflow step */
  kind: 'chat' | 'tool' | 'step'
  label: string
  detail: string | null
  link: string | null
  at: string | null
}

/** A change the review suggests. Nothing changes until you approve it. */
export interface MemoryProposal {
  id: string
  kind: ProposalKind
  status: ProposalStatus
  summary: string
  reason: string | null
  /** The wording it would save: a merged or reworded fact, or a lesson. */
  content: string | null
  facts: { id: string; content: string | null; status: string }[]
  agent: { id: string; key: string | null; name: string } | null
  /** For a lesson: what it is about. */
  about: string | null
  evidence: ProposalEvidence[]
  occurrences: number
  createdAt: string
  decidedAt: string | null
  resultRef: string | null
}

export interface MemoryReviewStatus {
  enabled: boolean
  last: MemoryReview | null
  open: number
  nextRunAt: string | null
}

export const memoryReviewApi = {
  status: () => api.get<MemoryReviewStatus>('/api/memory/review'),
  run: () => api.post<MemoryReview>('/api/memory/review/run', {}),
  proposals: (status: ProposalStatus[] = ['open']) =>
    api.get<MemoryProposal[]>(`/api/memory/review/proposals?${status.map(s => `status=${s}`).join('&')}`),
  approve: (id: string, content?: string) =>
    api.post<MemoryProposal>(`/api/memory/review/proposals/${id}/approve`, content ? { content } : {}),
  dismiss: (id: string) => api.post<MemoryProposal>(`/api/memory/review/proposals/${id}/dismiss`, {}),
  /** The nightly review is part of the memory settings. */
  settings: () => api.get<{ autoLearn: boolean; retentionDays: number | null; review: boolean | null }>('/api/memory/settings'),
  setReview: (s: { autoLearn: boolean; retentionDays: number | null }, review: boolean) =>
    api.put<{ autoLearn: boolean; retentionDays: number | null; review: boolean | null }>('/api/memory/settings', { ...s, review }),
}
