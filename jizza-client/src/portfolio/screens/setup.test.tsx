import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { FinProfileView, GoalView, HoldingsView, ImportBatch } from '@/types/finance'
import { SAMPLE_PROFILE } from '../sample'
import { SETUP_STEPS, SetupGuide, SetupGuideBar } from './SetupGuide'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

afterEach(() => vi.restoreAllMocks())

const profile = (over: Partial<FinProfileView['settings']> = {}): FinProfileView => ({
  ...SAMPLE_PROFILE,
  setupProgress: { quickStart: 'done', peopleManaged: 0 },
  settings: { advanced: false, hideAmounts: false, ...over },
})

function mockData({ goals = 1, bank = true, unassigned = 5 } = {}) {
  vi.spyOn(financeApi, 'goals').mockResolvedValue(Array.from({ length: goals }, (_, i) => ({ goal: { id: `g${i}`, status: 'active' } }) as unknown as GoalView))
  vi.spyOn(financeApi, 'builder').mockResolvedValue({ savedAt: null } as never)
  vi.spyOn(financeApi, 'imports').mockResolvedValue(bank ? [{ id: 'b', kind: 'bank_csv', status: 'committed' } as ImportBatch] : [])
  vi.spyOn(financeApi, 'holdings').mockResolvedValue({ rows: [{ instrumentId: 'i', unassignedUnits: unassigned }] } as unknown as HoldingsView)
  vi.spyOn(financeApi, 'policies').mockResolvedValue([])
}

describe('setup guide', () => {
  it('ticks the steps Kai can see and opens on the first one left', async () => {
    mockData()
    render(<SetupGuide profile={profile()} onGo={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByRole('img', { name: `3 of ${SETUP_STEPS.length} setup steps done` })).toBeInTheDocument()
    const steps = screen.getByRole('navigation', { name: 'Setup steps' })
    expect(within(steps).getByText('1 goal saved')).toBeInTheDocument()
    expect(within(steps).getByText('1 statement imported')).toBeInTheDocument()
    expect(within(steps).getByText('1 holding still has unassigned units')).toBeInTheDocument()
    // People is the first step neither done nor skipped.
    expect(screen.getByRole('heading', { name: 'Add the people you manage' })).toBeInTheDocument()
  })

  it('marks a step by hand, skips an optional one and opens a step', async () => {
    mockData()
    const patch = vi.spyOn(financeApi, 'updateSettings').mockResolvedValue({ advanced: false, hideAmounts: false })
    const onGo = vi.fn()
    render(<SetupGuide profile={profile()} onGo={onGo} />, { wrapper: wrap() })
    await screen.findByRole('heading', { name: 'Add the people you manage' })
    fireEvent.click(screen.getByRole('button', { name: /Skip this/ }))
    await waitFor(() => expect(patch).toHaveBeenCalledWith({ setupMarks: { people: 'skipped' } }))

    fireEvent.click(screen.getByRole('button', { name: /Compare tax regimes/ }))
    fireEvent.click(screen.getByRole('button', { name: /Mark as done/ }))
    await waitFor(() => expect(patch).toHaveBeenCalledWith({ setupMarks: { tax: 'done' } }))
    fireEvent.click(screen.getByRole('button', { name: /Go to Tax/ }))
    expect(onGo).toHaveBeenCalledWith(expect.objectContaining({ id: 'tax', view: 'tax' }))
  })

  it('counts hand marks, but never undoes what the data shows', async () => {
    mockData({ unassigned: 0 })
    render(<SetupGuide profile={profile({ setupMarks: { people: 'skipped', settings: 'done' } })} onGo={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByRole('img', { name: `6 of ${SETUP_STEPS.length} setup steps done` })).toBeInTheDocument()
    expect(screen.getByText('Every unit of 1 holding has a goal')).toBeInTheDocument()
  })

  it('shows the bar on other screens with the next step left', async () => {
    mockData()
    const onGo = vi.fn()
    render(<SetupGuideBar profile={profile()} stepId="goals" onGuide={() => {}} onGo={onGo} onClose={() => {}} />, { wrapper: wrap() })
    const bar = await screen.findByRole('region', { name: 'Setup guide' })
    expect(bar).toHaveTextContent('step 4 of 11')
    // Plan builder is next; bank files are already in.
    fireEvent.click(await within(bar).findByRole('button', { name: /Next: Build your plan/ }))
    expect(onGo).toHaveBeenCalledWith(expect.objectContaining({ id: 'plan-builder' }))
  })
})
