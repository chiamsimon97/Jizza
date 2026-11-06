import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { WorkflowGuardrails as Data } from '@/types'

vi.mock('@/api/guardrails', () => ({
  guardrailsApi: {
    workflow: vi.fn(),
    saveRule: vi.fn(async () => ({})),
    setMaintainers: vi.fn(),
  },
}))
vi.mock('@/api/agents', () => ({
  agentsApi: {
    list: vi.fn(async () => [
      { id: 'jizza', key: 'jizza', name: 'Jizza', orchestrator: true, status: 'active' },
      { id: 'kai', key: 'kai', name: 'Kai', orchestrator: false, status: 'active' },
    ]),
  },
}))

import { guardrailsApi } from '@/api/guardrails'
import { WorkflowGuardrails } from './WorkflowGuardrails'

const ask = { rule: 'ASK' as const, reason: 'Clicks ask first', actionType: 'UPDATE' as const }
const data: Data = {
  workflowId: 'wf1', name: 'Book My Show', version: 3, steps: [{
    nodeId: 'step_2', label: 'Click "Book tickets"', type: 'DEVICE_ACTION', agentId: 'jizza', agentName: 'Jizza',
    toolName: 'Click element', actionType: 'UPDATE', decision: ask,
    perAgent: [
      { agentId: 'jizza', agentName: 'Jizza', decision: ask },
      { agentId: 'kai', agentName: 'Kai', decision: ask },
    ],
  }],
  rules: [], maintainers: [{ id: 'jizza', name: 'Jizza' }, { id: 'kai', name: 'Kai' }],
}

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{children}</QueryClientProvider>)
}

afterEach(() => vi.clearAllMocks())

describe('WorkflowGuardrails', () => {
  it('shows a web step under each maintaining agent, and Auto lets it go on without asking', async () => {
    vi.mocked(guardrailsApi.workflow).mockResolvedValue(data)
    wrap(<WorkflowGuardrails workflowId="wf1" />)

    expect(await screen.findByText('Click "Book tickets"')).toBeTruthy()
    expect(screen.getByText('Jizza (leads)')).toBeTruthy()
    expect(screen.getByRole('radiogroup', { name: 'Kai: step mode' })).toBeTruthy()

    const jizzaModes = screen.getByRole('radiogroup', { name: 'Jizza: step mode' })
    fireEvent.click(jizzaModes.querySelector('[role="radio"]')!)
    await waitFor(() => expect(guardrailsApi.saveRule).toHaveBeenCalled())
    expect(vi.mocked(guardrailsApi.saveRule).mock.calls[0][0]).toMatchObject({
      agentId: 'jizza', toolRef: '*', rule: 'ALLOW', workflowId: 'wf1', nodeId: 'step_2',
    })
  })

  it('picks the agents that maintain it, keeping the order they were chosen in', async () => {
    vi.mocked(guardrailsApi.workflow).mockResolvedValue({ ...data, maintainers: [{ id: 'kai', name: 'Kai' }] })
    vi.mocked(guardrailsApi.setMaintainers).mockResolvedValue(data)
    wrap(<WorkflowGuardrails workflowId="wf1" />)

    const jizza = await screen.findByRole('checkbox', { name: /Jizza \(chat\)/ })
    fireEvent.click(jizza)
    await waitFor(() => expect(guardrailsApi.setMaintainers).toHaveBeenCalledWith('wf1', ['kai', 'jizza']))
  })
})
