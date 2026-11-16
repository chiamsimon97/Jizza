import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { RunTrace, StartRunCard as Card } from '@/types'

vi.mock('@/auth/useAuthorization', () => ({ getCachedSnapshot: () => undefined }))

const startRun = vi.fn()
const dismissRun = vi.fn()
vi.mock('@/api/chat', () => ({ chatApi: { startRun: (b: unknown) => startRun(b), dismissRun: (b: unknown) => dismissRun(b) } }))
vi.mock('@/api/runs', () => ({ runsApi: { trace: () => new Promise(() => {}), stop: vi.fn() } }))
vi.mock('@/api/notifications', () => ({ notificationsApi: { list: () => Promise.resolve([]) } }))

import { execute, useAssistantStore } from './assistantStore'
import { StartRunCard, initialValues, runInputs, stillNeeded } from './StartRunCard'
import { progressOf } from './RunInChat'

const card: Card = {
  planId: 'p1',
  workflowId: 'w1',
  name: 'Train booking',
  summary: 'Books a train ticket on the railway site',
  needsBrowser: true,
  browsers: [{ id: 'b1', name: 'Chromium browser', online: true, state: 'idle' }, { id: 'b2', name: 'Ulla browser', online: false }],
  browserId: 'b1',
  fields: [
    { name: 'from', label: 'From', type: 'text', required: true, value: 'MAS' },
    { name: 'to', label: 'To', type: 'text', required: true },
    { name: 'travelClass', label: 'Class', type: 'text', required: true, options: ['SL', 'AC 3 Tier (3A)'], value: 'AC 3 Tier (3A)' },
    { name: 'passengers', label: 'Passengers', type: 'list', required: true, columns: [{ key: 'name', label: 'Name' }, { key: 'age', label: 'Age' }], value: [{ name: 'Chiam', age: '' }] },
    { name: 'train', label: 'Train', type: 'text', required: true, ask: 'atStep' },
  ],
}

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>)
}

describe('the start card', () => {
  beforeEach(() => {
    startRun.mockReset()
    dismissRun.mockReset().mockResolvedValue(undefined)
    useAssistantStore.setState({ conversationId: 'c1' })
  })

  it('reads a start_run action off a reply', () => {
    const out = execute([{ type: 'start_run', payload: card }], false)
    expect(out.startRun?.planId).toBe('p1')
    expect(out.steps).toEqual([])
  })

  it('follows a run Jizza started, but not twice for a follow-up about it', () => {
    const out = execute([
      { type: 'reused_workflow', payload: { workflowId: 'w1', name: 'Train booking', runId: 'r1', status: 'running' } },
      { type: 'reused_workflow', payload: { workflowId: 'w1', name: 'Train booking', runId: 'r2', status: 'running', followUp: true } },
    ], false)
    expect(out.runs).toEqual([{ runId: 'r1', name: 'Train booking' }])
  })

  it('needs every required value except those asked during the run', () => {
    const values = initialValues(card)
    expect(stillNeeded(card, values).map(f => f.name)).toEqual(['to'])
    const inputs = runInputs(card, { ...values, to: 'NDLS' })
    expect(inputs).toEqual({ from: 'MAS', to: 'NDLS', travelClass: 'AC 3 Tier (3A)', passengers: [{ name: 'Chiam', age: '' }] })
    expect(inputs).not.toHaveProperty('train')
  })

  it('starts the run with the values and browser on the card, then follows it', async () => {
    startRun.mockResolvedValue({ id: 'run-1' })
    wrap(<StartRunCard card={card} />)
    const start = screen.getByRole('button', { name: /Start/ })
    expect(start).toBeDisabled()
    expect(screen.getByText(/Asked while it runs/)).toHaveTextContent('Train')

    fireEvent.change(screen.getByLabelText(/^To/), { target: { value: 'NDLS' } })
    fireEvent.change(screen.getByLabelText('Runs in'), { target: { value: 'b2' } })
    expect(screen.getByText(/Open it with the extension/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Start/ }))

    await waitFor(() => expect(startRun).toHaveBeenCalled())
    expect(startRun.mock.calls[0][0]).toMatchObject({
      conversationId: 'c1', planId: 'p1', workflowId: 'w1', browserId: 'b2',
      inputs: { from: 'MAS', to: 'NDLS', travelClass: 'AC 3 Tier (3A)' },
    })
    await waitFor(() => expect(screen.getByTestId('run-in-chat')).toBeInTheDocument())
  })

  it('shows a value that does not fit until it is changed', () => {
    const odd: Card = { ...card, fields: [{ name: 'travelClass', label: 'Class', type: 'text', required: true, value: '9Z', problem: "'9Z' is not one of the choices" }] }
    wrap(<StartRunCard card={odd} />)
    expect(screen.getByText(/not one of the choices/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/^Class/), { target: { value: 'SL' } })
    expect(screen.queryByText(/not one of the choices/)).toBeNull()
  })

  it('closes on No thanks without running anything', () => {
    wrap(<StartRunCard card={card} />)
    fireEvent.click(screen.getByRole('button', { name: /No thanks/ }))
    expect(dismissRun).toHaveBeenCalledWith({ conversationId: 'c1', planId: 'p1' })
    expect(startRun).not.toHaveBeenCalled()
    expect(screen.getByText(/Not run: Train booking/)).toBeInTheDocument()
  })

  it('warns about a step that cannot be undone', () => {
    wrap(<StartRunCard card={{ ...card, cannotBeUndone: 'Pay now' }} />)
    expect(screen.getByTestId('cannot-be-undone')).toHaveTextContent('"Pay now" can\'t be undone')
  })

  it('shows the run, not the form, once it has started', () => {
    wrap(<StartRunCard card={{ ...card, runId: 'run-9' }} />)
    expect(screen.getByTestId('run-in-chat')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Start/ })).toBeNull()
  })
})

describe('a run followed in chat', () => {
  it('says what is done and what it waits for', () => {
    const trace = {
      run: { id: 'r', status: 'PAUSED_WAITING_INSTRUCTION' },
      nodes: [{ id: 'start', type: 'TRIGGER', label: 'Start' }, { id: 'n1', type: 'DEVICE', label: 'Log in' }, { id: 'n2', type: 'DEVICE', label: 'Choose a train' }],
      edges: [],
      steps: [
        { id: 's0', nodeId: 'start', label: 'Start', type: 'TRIGGER', status: 'DONE', attempts: 1 },
        { id: 's1', nodeId: 'n1', label: 'Log in', type: 'DEVICE', status: 'DONE', attempts: 1 },
      ],
      waitingOn: 'n2',
      children: [],
    } as unknown as RunTrace
    expect(progressOf(trace)).toMatchObject({ done: ['Log in'], current: 'Choose a train', waitingForYou: true })
  })
})
