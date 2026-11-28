import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { AgentDto } from '@/types'
import type { EmergencyCard, HomeView, VitaConfigItem, VitaPerson, VitaProfileView, VitaQuickStartView } from '@/types/vita'
import { HealthTab, splitPeople } from '../HealthTab'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const mother: VitaPerson = { ...me, id: 'p-mum', displayName: 'Mother', kind: 'parent', sortOrder: 1 }
const grandpa: VitaPerson = { ...me, id: 'p-gp', displayName: 'Grandfather', kind: 'parent', status: 'deceased', sortOrder: 2 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto

const fresh: VitaQuickStartView = { personId: 'p-me', progress: { completed: [], skipped: [], next: 'dob', done: false, total: 8 } }
const profileView: VitaProfileView = {
  person: { id: 'p-me', displayName: 'Me', kind: 'self', dob: null, age: null, status: 'active', statusOn: null },
  profile: {
    id: 'prof', personId: 'p-me', bloodGroup: null, heightCm: null, sexAtBirth: null, organDonor: null, preferredHospital: null,
    preferredHospitalPhone: null, abhaLast4: '1234', timezone: 'Asia/Kolkata', allergiesNoneKnown: false, conditionsNoneKnown: false,
    reminderMode: 'each_slot', notes: null,
  },
}
const emptyCard: EmergencyCard = {
  personId: 'p-me', name: 'Me', age: null, bloodGroup: null, organDonor: null, preferredHospital: null, preferredHospitalPhone: null,
  allergiesNoneKnown: false, allergies: [], conditionsNoneKnown: false, conditions: [], medicines: [], medicinesAvailable: false,
  emergencyContacts: [], emergencyNumbers: { emergency: '112' }, emergencyNumbersNeedVerification: true, updatedOn: '2026-11-27', closed: false,
}
const item = (key: string, extra: Partial<VitaConfigItem>): VitaConfigItem => ({
  key, area: key.split('.')[0], value: null, defaultValue: null, source: 'vita_default', isOverride: false, effectiveFrom: '2026-01-01',
  sourceUrl: null, verifiedOn: null, needsVerification: false, state: 'product_default', label: 'Vita default', ...extra,
})
const config: VitaConfigItem[] = [
  item('display.units', { value: { weight: 'kg', height: 'cm', temperature: 'C' } }),
  item('emergency.numbers', { value: { emergency: '112', ambulanceWhereTheyLive: '' }, state: 'needs_verification', needsVerification: true, label: 'Vita default · needs verification' }),
  item('clinical.ranges.general.bp_home', { value: { unit: 'mmHg', systolic: { below: 135 }, diastolic: { below: 85 } }, state: 'needs_verification', needsVerification: true, label: 'Vita default · needs verification', sourceUrl: 'ISH 2020 guidelines (search summary)' }),
  item('clinical.ranges.general.spo2', { source: 'none', effectiveFrom: null, state: 'not_set', label: 'Not set: ask your doctor' }),
]

const emptyHome: HomeView = {
  person: { id: 'p-me', name: 'Me', recordOpen: true, status: 'active' }, quickStart: fresh.progress, latest: [], redFlags: [],
  conditions: [], nextVisit: null, slots: [], reminders: [],
}

function mockApi() {
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me, mother, grandpa])
  vi.spyOn(vitaApi, 'quickStart').mockResolvedValue(fresh)
  vi.spyOn(vitaApi, 'conditions').mockResolvedValue([])
  vi.spyOn(vitaApi, 'allergies').mockResolvedValue([])
  vi.spyOn(vitaApi, 'contacts').mockResolvedValue([])
  vi.spyOn(vitaApi, 'measurements').mockResolvedValue([])
  vi.spyOn(vitaApi, 'emergencyCard').mockResolvedValue(emptyCard)
  vi.spyOn(vitaApi, 'profile').mockResolvedValue(profileView)
  vi.spyOn(vitaApi, 'config').mockResolvedValue(config)
  vi.spyOn(vitaApi, 'home').mockResolvedValue(emptyHome)
}

function renderTab(search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrap = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[`/agents/a2?tab=health${search}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter></QueryClientProvider>
  )
  return render(<HealthTab agent={vita} />, { wrapper: wrap })
}

beforeEach(mockApi)
afterEach(() => vi.restoreAllMocks())

describe('HealthTab shell', () => {
  it('puts You first and closed records under Past records', () => {
    const { open, past } = splitPeople([mother, grandpa, me])
    expect(open.map(p => p.id)).toEqual(['p-me', 'p-mum'])
    expect(past.map(p => p.id)).toEqual(['p-gp'])
  })

  it('shows the person switcher, the sub-navigation and Home with its Quick start empty state', async () => {
    renderTab()
    expect(await screen.findByText('Today for you')).toBeInTheDocument()
    const person = screen.getByRole('combobox', { name: 'Person' })
    expect(within(person).getByRole('option', { name: 'You' })).toBeInTheDocument()
    expect(within(person).getByRole('group', { name: 'Past records' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Health sections' })).toHaveTextContent('Emergency card')
    expect(await screen.findByRole('button', { name: 'Start Quick start' })).toBeInTheDocument()
    expect(screen.getByText(/Not medical advice/)).toBeInTheDocument()
  })

  it('shows what a later view will hold', async () => {
    renderTab('&view=activity')
    expect(await screen.findByText(/Activity and sleep comes with the phone and watch update/)).toBeInTheDocument()
  })

  it('shows a closed record read-only', async () => {
    renderTab('&person=p-gp&view=conditions')
    expect(await screen.findByText(/record stays here to read under “Past records”/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Add condition/ })).toBeNull()
  })

  it('says what failed and offers Try again when the people list cannot load', async () => {
    vi.mocked(vitaApi.people).mockRejectedValue(new Error('down'))
    renderTab()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not open Vita’s Health tab.')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('views', () => {
  it('Conditions: empty state with "None known", then marking it saves to the profile', async () => {
    const save = vi.spyOn(vitaApi, 'saveProfile').mockResolvedValue(profileView)
    renderTab('&view=conditions')
    expect(await screen.findByText(/No conditions recorded/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Mark “None known”' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('p-me', { conditionsNoneKnown: true }))
  })

  it('Conditions: error state', async () => {
    vi.mocked(vitaApi.conditions).mockRejectedValue(new Error('x'))
    renderTab('&view=conditions')
    expect(await screen.findByText('Could not load the conditions.')).toBeInTheDocument()
  })

  it('Allergies: empty state with "None known"', async () => {
    renderTab('&view=allergies')
    expect(await screen.findByText('No allergies recorded.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mark “None known”' })).toBeInTheDocument()
  })

  it('Allergies: error state', async () => {
    vi.mocked(vitaApi.allergies).mockRejectedValue(new Error('x'))
    renderTab('&view=allergies')
    expect(await screen.findByText('Could not load the allergies.')).toBeInTheDocument()
  })

  it('Emergency card: the card, Print, and an empty contacts list', async () => {
    renderTab('&view=emergency')
    expect(await screen.findByRole('article', { name: 'Emergency card for Me' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Print or save as PDF/ })).toBeInTheDocument()
    expect(await screen.findByText(/No contacts yet/)).toBeInTheDocument()
  })

  it('Emergency card: error state', async () => {
    vi.mocked(vitaApi.emergencyCard).mockRejectedValue(new Error('x'))
    renderTab('&view=emergency')
    expect(await screen.findByText('Could not load the emergency card.')).toBeInTheDocument()
  })

  it('Settings: a seeded figure carries the badge, an unsourced one says "Not set" with no number', async () => {
    renderTab('&view=settings')
    expect(await screen.findByText('Below 135/85 mmHg')).toBeInTheDocument()
    expect(screen.getAllByText('Needs verification').length).toBeGreaterThan(0)
    expect(screen.getByText('Oxygen (SpO2)').closest('li')).toHaveTextContent('Not set: ask your doctor')
    expect(screen.getByText('Oxygen (SpO2)').closest('li')).toHaveTextContent('No number')
  })

  it('Settings: error state', async () => {
    vi.mocked(vitaApi.config).mockRejectedValue(new Error('x'))
    renderTab('&view=settings')
    expect(await screen.findByText('Could not load Vita’s settings.')).toBeInTheDocument()
  })

  it('Profile: shows only the ABHA number’s last four digits', async () => {
    renderTab('&view=profile')
    expect(await screen.findByLabelText('ABHA number (ends in 1234)')).toHaveValue('')
  })

  it('Quick start: opens on the next step, and Skip saves the skip and moves on', async () => {
    vi.mocked(vitaApi.quickStart).mockResolvedValue({ personId: 'p-me', progress: { completed: ['dob'], skipped: [], next: 'sex', done: false, total: 8 } })
    const skip = vi.spyOn(vitaApi, 'skipQuickStart').mockResolvedValue({ personId: 'p-me', progress: { completed: ['dob'], skipped: ['sex'], next: 'height_weight', done: false, total: 8 } })
    renderTab('&view=quick-start')
    expect(await screen.findByText('Sex at birth')).toBeInTheDocument()
    expect(screen.getByText(/step 2 of 8/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    await waitFor(() => expect(skip).toHaveBeenCalledWith('p-me', 'sex'))
    expect(await screen.findByText('Height and weight')).toBeInTheDocument()
  })

  it('Quick start: an answer is sent for the step and nothing else', async () => {
    const save = vi.spyOn(vitaApi, 'saveQuickStart').mockResolvedValue({ personId: 'p-me', progress: { completed: ['blood_group'], skipped: [], next: 'conditions', done: false, total: 8 } })
    vi.mocked(vitaApi.quickStart).mockResolvedValue({ personId: 'p-me', progress: { completed: [], skipped: [], next: 'blood_group', done: false, total: 8 } })
    renderTab('&view=quick-start')
    fireEvent.click(await screen.findByRole('button', { name: 'O+' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('p-me', 'blood_group', { bloodGroup: 'o_pos' }))
  })
})
