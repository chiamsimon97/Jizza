import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { WorkflowPlan } from '@/types'

const save = vi.fn()
vi.mock('@/api/workflows', () => ({ workflowsApi: { save: (wf: unknown) => save(wf) } }))
vi.mock('@/api/agents', () => ({ agentsApi: { list: () => Promise.resolve([]) } }))

import { WorkflowPlanCard } from './WorkflowPlanCard'
import { useAssistantStore } from './assistantStore'

const plan: WorkflowPlan = {
  planId: 'plan-1',
  name: 'Gold rate checker',
  description: 'Checks gold every morning',
  steps: [
    { kind: 'agent', agentId: 'kai-id', agentKey: 'finance', agentName: 'Kai', instruction: 'Fetch the gold rate' },
    { kind: 'agent', agentId: 'kai-id', agentName: 'Kai', instruction: 'Buy 1g', changesThings: true, approval: 'ask' },
    { kind: 'ask', instruction: 'Is the limit still right?' },
  ],
  assumptions: ['Runs at 9am'],
}

describe('WorkflowPlanCard', () => {
  beforeEach(() => {
    save.mockReset()
    useAssistantStore.setState({ savedPlans: {}, messages: [] })
  })

  it('shows each step, where it stops for the user, and what Jizza assumed', () => {
    renderWithSnapshot(<WorkflowPlanCard plan={plan} />, makeSnapshot())
    expect(screen.getByText('Fetch the gold rate')).toBeInTheDocument()
    expect(screen.getByText(/Asks you first/)).toBeInTheDocument()
    expect(screen.getByText('Runs at 9am')).toBeInTheDocument()
    expect(screen.getByText(/stop for you at 2 steps/)).toBeInTheDocument()
  })

  it('saves nothing until approved, then saves the plan as a workflow graph once', async () => {
    save.mockResolvedValue({ id: 'wf-1', name: 'Gold rate checker' })
    renderWithSnapshot(<WorkflowPlanCard plan={plan} />, makeSnapshot())
    expect(save).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /Approve and save/ }))

    await waitFor(() => expect(screen.getByText('Saved as a draft workflow')).toBeInTheDocument())
    expect(save).toHaveBeenCalledTimes(1)
    const sent = save.mock.calls[0][0] as { name: string; graph: { nodes: { type: string; config: Record<string, unknown> }[] } }
    expect(sent.name).toBe('Gold rate checker')
    expect(sent.graph.nodes.map(n => n.type)).toEqual(['TRIGGER', 'AGENT_STEP', 'AGENT_STEP', 'HUMAN_INTERVENTION', 'END'])
    expect(sent.graph.nodes[1].config).toEqual({ agentId: 'kai-id', instruction: 'Fetch the gold rate' })
    expect(useAssistantStore.getState().savedPlans['plan-1']).toBe('wf-1')
    expect(screen.queryByRole('button', { name: /Approve and save/ })).not.toBeInTheDocument()
  })
})
