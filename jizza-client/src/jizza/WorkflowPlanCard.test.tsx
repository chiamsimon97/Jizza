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

  it('keeps the plan for reuse with the guardrails the user picked', async () => {
    save.mockResolvedValue({ id: 'wf-2', name: 'Gold rate checker' })
    const offered: WorkflowPlan = {
      ...plan, planId: 'plan-2',
      reuse: {
        summary: 'When I ask for the gold rate',
        examples: ['check the gold rate'],
        inputs: [{ name: 'city', required: true }],
        guardrails: { confirmBeforeRun: true, allowScheduled: false, rules: ['Never buy more than 1g'] },
      },
    }
    renderWithSnapshot(<WorkflowPlanCard plan={offered} />, makeSnapshot())
    expect(screen.getByText('Used when: When I ask for the gold rate')).toBeInTheDocument()
    expect(screen.getByText('Never buy more than 1g')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText(/Scheduled tasks doing this job/))
    fireEvent.click(screen.getByRole('button', { name: /Approve and save/ }))

    await waitFor(() => expect(screen.getByText('Saved, and kept for Jizza to reuse')).toBeInTheDocument())
    const sent = save.mock.calls[0][0] as { reuse: { summary: string; guardrails: { confirmBeforeRun: boolean; allowScheduled: boolean } } }
    expect(sent.reuse.summary).toBe('When I ask for the gold rate')
    expect(sent.reuse.guardrails).toMatchObject({ confirmBeforeRun: true, allowScheduled: true })
  })

  it('saves without keeping it for reuse when the user unticks it', async () => {
    save.mockResolvedValue({ id: 'wf-3', name: 'Gold rate checker' })
    renderWithSnapshot(<WorkflowPlanCard plan={{ ...plan, planId: 'plan-3', reuse: { summary: 'Gold' } }} />, makeSnapshot())

    fireEvent.click(screen.getByLabelText(/Let Jizza reuse this next time/))
    fireEvent.click(screen.getByRole('button', { name: /Approve and save/ }))

    await waitFor(() => expect(screen.getByText('Saved as a draft workflow')).toBeInTheDocument())
    expect((save.mock.calls[0][0] as { reuse?: unknown }).reuse).toBeUndefined()
  })

  it('saves a tool step as a service-action step that repeats the chat\'s call', async () => {
    save.mockResolvedValue({ id: 'wf-4', name: 'Gold rate checker' })
    const captured: WorkflowPlan = {
      planId: 'plan-4', name: 'Gold rate checker',
      steps: [
        { kind: 'tool', instruction: 'Get the gold rate', toolId: 'tool-1', toolName: 'metals_get_rate', toolLabel: 'Get metal rate',
          arguments: { metal: 'gold', city: '{{ input.city }}' }, inputs: ['city'] },
        { kind: 'agent', agentId: 'kai-id', agentName: 'Kai', instruction: 'Say if it is a good day to buy' },
      ],
    }
    renderWithSnapshot(<WorkflowPlanCard plan={captured} />, makeSnapshot())
    expect(screen.getByText(/same call every run/)).toBeInTheDocument()
    expect(screen.getByText('Changes each run: city')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Approve and save/ }))

    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    const sent = save.mock.calls[0][0] as { graph: { nodes: { id: string; type: string; config: Record<string, unknown> }[] } }
    expect(sent.graph.nodes.map(n => n.type)).toEqual(['TRIGGER', 'CONNECTOR_TOOL', 'AGENT_STEP', 'END'])
    expect(sent.graph.nodes[1]).toMatchObject({ id: 'step_1', config: { toolId: 'tool-1', arguments: { metal: 'gold', city: '{{ input.city }}' } } })
  })
})
