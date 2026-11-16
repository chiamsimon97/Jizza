import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, screen } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { WorkflowForm } from './WorkflowForm'
import { useFormStore } from '@/jizza/formStore'
import { workflowsApi } from '@/api/workflows'
import { agentsApi } from '@/api/agents'

// The canvas measures itself; jsdom has no ResizeObserver.
globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} } as unknown as typeof ResizeObserver

const node = (id: string, type: string, config: Record<string, unknown> = {}, label = id) => ({ id, type, label, config })

// The shape a recorded workflow takes once a list step's choice becomes a run-time input: the
// start step declares that input, so the builder asks for it before a run.
const recorded = {
  nodes: [
    node('start', 'TRIGGER', { inputs: [{ name: 'coaches', type: 'text', group: 'IRCTC', default: '', required: false }] }, 'Start'),
    node('open', 'DEVICE_ACTION', { ui: { x: 280, y: 40 }, action: 'browser.open', inputs: { url: 'https://example.com' }, preset: 'web', target: 'sameBrowser', version: 2 }, 'Open'),
    node('step_8', 'DEVICE_ACTION', { action: 'browser.clickItem', inputs: { item: '{{ input.coaches }}' }, preset: 'web', target: 'sameBrowser', version: 1,
      list: { kind: 'text', named: true, title: 'Coaches', options: ['Sleeper (SL)', 'AC 3 Tier (3A)'] }, recorded: { item: 'Sleeper (SL)' } }, 'Choose Coaches "Sleeper (SL)"'),
    node('end', 'END', { outputs: [] }, 'End'),
  ],
  edges: [{ from: 'start', to: 'open', branch: null }, { from: 'open', to: 'step_8', branch: null }, { from: 'step_8', to: 'end', branch: null }],
}

describe('WorkflowForm', () => {
  afterEach(() => { act(() => useFormStore.getState().close()); vi.restoreAllMocks() })

  it('opens a saved workflow whose start step declares inputs', async () => {
    vi.spyOn(agentsApi, 'list').mockResolvedValue([])
    vi.spyOn(workflowsApi, 'latestRuns').mockResolvedValue([])
    renderWithSnapshot(<WorkflowForm />, makeSnapshot())
    act(() => useFormStore.getState().open('new_workflow', { id: 'w1', name: 'IRCTC Testing', status: 'DRAFT', version: 4, graph: recorded }))
    expect(await screen.findByDisplayValue('IRCTC Testing')).toBeTruthy()
  })
})
