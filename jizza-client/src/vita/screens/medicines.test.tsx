import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { useAuthStore } from '@/store/authStore'
import type { AgentDto } from '@/types'
import type { Adherence, DoseSlot, HomeView, Medicine, VitaPerson } from '@/types/vita'
import { HealthTab } from '../HealthTab'
import { groupOf, recurrenceWords, scheduleWords } from '../medicines'
import { MEDICINES_PROMISE, adherenceWords } from './Medicines'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto

const med = (m: Partial<Medicine> = {}): Medicine => ({
  id: 'med1', personId: 'p-me', name: 'Metformin', brandName: 'Glycomet', genericName: null, strength: '500 mg', form: 'tablet', system: 'allopathy',
  conditionId: null, prescribedBy: null, startDate: '2026-11-01', endDate: null, asNeeded: false, trackStock: true, stockUnits: 24, unitsPerPack: 30,
  refillLeadDays: 5, private: false, notes: null, status: 'active', refillDue: false,
  schedule: { times: ['08:00', '20:00'], recurrence: 'FREQ=DAILY', doseUnits: 1, doseUnit: 'tablet', withFood: 'after' }, ...m,
})
const slot = (s: Partial<DoseSlot> = {}): DoseSlot => ({
  personId: 'p-me', personName: 'Me', dueAt: '2026-11-28T02:30:00Z', localTime: '08:00', localDate: '2026-11-28', zone: 'Asia/Kolkata',
  status: 'due', snoozedUntil: null, markedBy: null, markedAt: null, missedAt: '2026-11-28T05:30:00Z',
  doses: [
    { id: 'd1', medicineId: 'med1', medicineName: 'Metformin', strength: '500 mg', doseUnits: 1, doseUnit: 'tablet', withFood: 'after', status: 'due', snoozedUntil: null, takenAt: null, actedAt: null, markedBy: null, late: false, version: 1 },
    { id: 'd2', medicineId: 'med2', medicineName: 'Telmisartan', strength: '40 mg', doseUnits: 1, doseUnit: 'tablet', withFood: 'any', status: 'due', snoozedUntil: null, takenAt: null, actedAt: null, markedBy: null, late: false, version: 1 },
  ],
  ...s,
})
const home = (h: Partial<HomeView> = {}): HomeView => ({
  person: { id: 'p-me', name: 'Me', recordOpen: true, status: 'active' },
  quickStart: { completed: [], skipped: [], next: null, done: true, total: 8 },
  latest: [], redFlags: [], conditions: [], nextVisit: null, slots: [], reminders: [], refillsDue: 0, ...h,
})
const adherence: Adherence = {
  from: '2026-10-28', to: '2026-11-26',
  overall: { due: 60, taken: 52, skipped: 2, missed: 6, late: 3, exact: 86.67, percent: 87 },
  medicines: [{ medicineId: 'med1', name: 'Metformin', result: { due: 60, taken: 52, skipped: 2, missed: 6, late: 3, exact: 86.67, percent: 87 } }],
}

function renderTab(search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrap = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[`/agents/a2?tab=health${search}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter></QueryClientProvider>
  )
  return render(<HealthTab agent={vita} />, { wrapper: wrap })
}

beforeEach(() => {
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me])
  vi.spyOn(vitaApi, 'config').mockResolvedValue([])
  vi.spyOn(vitaApi, 'home').mockResolvedValue(home())
  vi.spyOn(vitaApi, 'emergencyCard').mockRejectedValue(new Error('not needed'))
  vi.spyOn(vitaApi, 'medicines').mockResolvedValue([med()])
  vi.spyOn(vitaApi, 'conditions').mockResolvedValue([])
  vi.spyOn(vitaApi, 'doctors').mockResolvedValue([])
  vi.spyOn(vitaApi, 'adherence').mockResolvedValue(adherence)
  vi.spyOn(vitaApi, 'medicineEvents').mockResolvedValue([])
  vi.spyOn(vitaApi, 'doses').mockResolvedValue({ zone: 'Asia/Kolkata', slots: [] })
  useAuthStore.setState({ userId: 'u-me' })
})
afterEach(() => vi.restoreAllMocks())

describe('medicine words', () => {
  it('says the schedule, days and adherence in plain words', () => {
    expect(scheduleWords(med().schedule)).toBe('1 tablet at 8:00 AM and 8:00 PM, every day, after food')
    expect(recurrenceWords('FREQ=WEEKLY;BYDAY=MO,TH')).toBe('on Mon and Thu')
    expect(adherenceWords(adherence.overall)).toBe('87% taken (52 of 60)')
    expect(adherenceWords(undefined)).toBe('No doses due yet')
    expect(groupOf(med({ form: 'supply', schedule: null }))).toBe('supplies')
    expect(groupOf(med({ status: 'paused' }))).toBe('paused')
    expect(groupOf(med({ asNeeded: true, schedule: null }))).toBe('as_needed')
  })
})

describe('Medicines', () => {
  it('lists medicines grouped, with schedule, stock and 30-day adherence, and says Vita never changes a dose', async () => {
    vi.mocked(vitaApi.medicines).mockResolvedValue([med(), med({ id: 'med3', name: 'Strips', form: 'supply', schedule: null, trackStock: false }), med({ id: 'med4', name: 'Old pill', status: 'stopped' })])
    renderTab('&view=medicines')
    expect(await screen.findByText('Active (1)')).toBeInTheDocument()
    expect(screen.getByText('Supplies (1)')).toBeInTheDocument()
    expect(screen.getByText('Stopped (1)')).toBeInTheDocument()
    expect(screen.getByText(MEDICINES_PROMISE)).toBeInTheDocument()
    expect(screen.getAllByText('1 tablet at 8:00 AM and 8:00 PM, every day, after food').length).toBeGreaterThan(0)
    expect(await screen.findByText(/24 left · Last 30 days: 87% taken \(52 of 60\)/)).toBeInTheDocument()
  })

  it('adds a medicine with its times from the side panel', async () => {
    const add = vi.spyOn(vitaApi, 'addMedicine').mockResolvedValue(med())
    vi.mocked(vitaApi.medicines).mockResolvedValue([])
    renderTab('&view=medicines')
    fireEvent.click(await screen.findByRole('button', { name: /Add medicine/ }))
    const form = screen.getByRole('form', { name: 'Add a medicine' })
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Metformin' } })
    fireEvent.change(within(form).getByLabelText('Strength (optional)'), { target: { value: '500 mg' } })
    fireEvent.click(within(form).getByRole('button', { name: /Add a time/ }))
    fireEvent.change(within(form).getByLabelText('Food'), { target: { value: 'after' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add medicine' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-me', expect.objectContaining({
      name: 'Metformin', strength: '500 mg', form: 'tablet',
      schedule: { times: ['08:00', '20:00'], recurrence: 'FREQ=DAILY', doseUnits: 1, doseUnit: 'tablet', withFood: 'after' },
    })))
  })

  it('changes the dose from a date with the doctor, keeping the old one in the history', async () => {
    const change = vi.spyOn(vitaApi, 'changeMedicine').mockResolvedValue(med())
    renderTab('&view=medicines')
    fireEvent.click(await screen.findByRole('button', { name: 'Change dose or times' }))
    const form = screen.getByRole('form', { name: 'Change Metformin' })
    expect(within(form).getByText(/The old schedule stays in the history/)).toBeInTheDocument()
    fireEvent.change(within(form).getByLabelText('New strength'), { target: { value: '1000 mg' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save change' }))
    await waitFor(() => expect(change).toHaveBeenCalledWith('p-me', 'med1', expect.objectContaining({ strength: '1000 mg' })))
  })

  it('pauses, and stops only after saying so', async () => {
    const pause = vi.spyOn(vitaApi, 'pauseMedicine').mockResolvedValue(med({ status: 'paused' }))
    const stop = vi.spyOn(vitaApi, 'stopMedicine').mockResolvedValue(med({ status: 'stopped' }))
    renderTab('&view=medicines')
    fireEvent.click(await screen.findByRole('button', { name: /Pause/ }))
    await waitFor(() => expect(pause).toHaveBeenCalledWith('p-me', 'med1'))
    fireEvent.click(screen.getByRole('button', { name: /^Stop$/ }))
    expect(stop).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Stop it' }))
    await waitFor(() => expect(stop).toHaveBeenCalledWith('p-me', 'med1', null))
  })

  it('opens a medicine to its changes', async () => {
    vi.mocked(vitaApi.medicineEvents).mockResolvedValue([{ id: 'e1', medicineId: 'med1', kind: 'dose_changed', onDate: '2026-11-20', fromText: '500 mg', toText: '1000 mg', reason: 'Sugar high', doctorId: null }])
    renderTab('&view=medicines')
    fireEvent.click(await screen.findByRole('button', { name: 'Open Metformin' }))
    expect(await screen.findByText(/Dose or times changed: 500 mg → 1000 mg · Sugar high/)).toBeInTheDocument()
  })
})

describe('Home dose cards', () => {
  it('marks a whole slot taken with All taken', async () => {
    vi.mocked(vitaApi.home).mockResolvedValue(home({ slots: [slot()] }))
    const act = vi.spyOn(vitaApi, 'actSlot').mockResolvedValue(slot({ status: 'taken' }))
    renderTab()
    const card = await screen.findByRole('region', { name: 'Medicines at 8:00 AM' })
    expect(within(card).getByText('Metformin 500 mg')).toBeInTheDocument()
    fireEvent.click(within(card).getByRole('button', { name: 'All taken' }))
    await waitFor(() => expect(act).toHaveBeenCalledWith('p-me', '2026-11-28T02:30:00Z', { action: 'taken' }))
  })

  it('ticks one medicine alone, and snoozes the rest', async () => {
    vi.mocked(vitaApi.home).mockResolvedValue(home({ slots: [slot()] }))
    const one = vi.spyOn(vitaApi, 'actDose').mockResolvedValue(slot())
    const all = vi.spyOn(vitaApi, 'actSlot').mockResolvedValue(slot({ status: 'snoozed' }))
    renderTab()
    const card = await screen.findByRole('region', { name: 'Medicines at 8:00 AM' })
    fireEvent.click(within(card).getByRole('button', { name: 'Mark Telmisartan taken' }))
    await waitFor(() => expect(one).toHaveBeenCalledWith('d2', { action: 'taken' }))
    fireEvent.click(within(card).getByRole('button', { name: /Snooze 30 min/ }))
    await waitFor(() => expect(all).toHaveBeenCalledWith('p-me', '2026-11-28T02:30:00Z', { action: 'snoozed', snoozeMinutes: 30 }))
  })

  it('a marked slot says who marked it, in words', async () => {
    const taken = slot({
      status: 'taken', markedBy: 'u-me', markedAt: '2026-11-28T02:40:00Z',
      doses: slot().doses.map(d => ({ ...d, status: 'taken' as const, markedBy: 'u-me' })),
    })
    vi.mocked(vitaApi.home).mockResolvedValue(home({ slots: [taken], refillsDue: 1 }))
    renderTab()
    const card = await screen.findByRole('region', { name: 'Medicines at 8:00 AM' })
    expect(within(card).getByText(/Marked by you/)).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'All taken' })).toBeNull()
    expect(screen.getByText('One medicine needs a refill')).toBeInTheDocument()
  })
})
