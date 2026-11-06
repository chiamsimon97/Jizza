import { api } from '@/lib/api'
import { toParams, viewerZone } from '@/api/activity'
import type { RunHistoryPage, RunStatus, RunTrace, TriggerKind } from '@/types'

export interface RunQuery {
  from?: string
  to?: string
  workflowId?: string
  statuses?: RunStatus[]
  triggers?: TriggerKind[]
  q?: string
  limit?: number
  offset?: number
}

export const runsApi = {
  search: (q: RunQuery) =>
    api.get<RunHistoryPage>(`/api/runs?${toParams({
      from: q.from, to: q.to, workflowId: q.workflowId, status: q.statuses, trigger: q.triggers,
      q: q.q, limit: q.limit, offset: q.offset, tz: viewerZone(),
    })}`),

  trace: (runId: string) => api.get<RunTrace>(`/api/runs/${runId}/trace`),

  /** Stops a run that is still going or waiting; anything it asked you about is closed too. */
  stop: (runId: string) => api.post<{ message: string }>(`/api/runs/${runId}/cancel`),
}
