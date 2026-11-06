import { describe, expect, it, vi } from 'vitest'

vi.mock('@/auth/useAuthorization', () => ({ getCachedSnapshot: () => undefined }))

import { execute, runLogPath } from './assistantStore'

describe('a workflow Jizza ran from chat', () => {
  const runId = '0b7c5f1e-3a2d-4c8e-9f10-1a2b3c4d5e6f'

  it('links to that run on the Run history page', () => {
    expect(runLogPath(runId)).toBe(`/runs?run=${runId}`)
  })

  it('shows a step that opens the run log', () => {
    const { steps } = execute([{ type: 'reused_workflow', payload: { workflowId: 'w1', name: 'Ramayanam Ticket Booking', runId, status: 'running' } }], false)

    expect(steps).toEqual([{ label: 'Ran your saved workflow "Ramayanam Ticket Booking"', href: `/runs?run=${runId}`, hrefLabel: 'View run log' }])
  })
})
