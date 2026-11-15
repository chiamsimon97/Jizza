import { describe, expect, it, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { WorkflowsPage } from './WorkflowsPage'
import { workflowsApi } from '@/api/workflows'
import { useFormStore } from '@/jizza/formStore'
import type { WorkflowDto } from '@/types'

// The builder itself is not under test here; only what it is opened with.
vi.mock('@/components/forms/WorkflowForm', async (original) => ({
  ...(await original<typeof import('@/components/forms/WorkflowForm')>()),
  WorkflowForm: () => null,
}))

const wf = (over: Partial<WorkflowDto>): WorkflowDto =>
  ({ id: 'w1', name: 'IRCTC Testing', graph: { nodes: [], edges: [] }, origin: 'RECORDED', status: 'DRAFT', version: 1, createdAt: '2026-01-01T00:00:00Z', ...over })

describe('WorkflowsPage', () => {
  it('opens ?open=<id> at its newest version, not the one the list still holds', async () => {
    // Just after an edited recording saved version 2, the cached list can still show version 1.
    vi.spyOn(workflowsApi, 'list').mockResolvedValue([wf({ version: 1 })])
    const get = vi.spyOn(workflowsApi, 'get').mockResolvedValue(wf({ version: 2, graph: { nodes: [{ id: 'new' }], edges: [] } }))
    renderWithSnapshot(<WorkflowsPage />, makeSnapshot(), '/workflows?open=w1')
    await waitFor(() => expect(useFormStore.getState().request?.fields.version).toBe(2))
    expect(get).toHaveBeenCalledWith('w1')
    expect(useFormStore.getState().request?.fields.id).toBe('w1')
    useFormStore.getState().close()
  })
})
