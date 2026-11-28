import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { AgentDto } from '@/types'
import type { HealthSummary, MailSource, Visit, VisitPrep, VitaPerson } from '@/types/vita'
import { HealthTab } from '../HealthTab'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto
const NOTE = 'Prepared by Vita from your own records. Not medical advice.'

const visit: Visit = {
  id: 'v1', personId: 'p-me', doctorId: 'd1', scheduledAt: '2099-12-03T05:30:00Z', status: 'planned', reason: 'Three-month review', notes: null, followUpOn: null,
  questions: [{ text: 'Can the night dose move earlier?' }],
}
const prep: VisitPrep = {
  visit, person: { id: 'p-me', name: 'Me', age: 62, sexAtBirth: 'female', bloodGroup: 'o_pos' },
  doctor: { id: 'd1', name: 'Dr Meera Rao', speciality: 'Diabetologist', clinic: null },
  questions: ['Can the night dose move earlier?'],
  readings: [{ kind: 'glucose', context: 'before_meal', days: 30, count: 12, outside: 3, average: 128, last: null,
    range: { status: 'set', range: '80–130', source: 'vita_default', label: 'Vita default, needs verification', needsVerification: true } }],
  medicines: [{ name: 'Metformin', strength: '1000 mg', form: 'tablet', system: 'allopathy', status: 'active', asNeeded: false,
    schedule: { times: ['08:00', '20:00'], doseUnits: 1, doseUnit: 'tablet', withFood: 'after', recurrence: 'FREQ=DAILY' } }],
  changesSince: '2026-09-15', changesSinceLabel: 'since the visit on 15 Sep 2026',
  medicineChanges: [{ onDate: '2026-11-02', kind: 'dose_changed', medicine: 'Metformin', fromText: '500 mg', toText: '1000 mg', doctorName: 'Dr Meera Rao', line: 'Metformin: 500 mg to 1000 mg (Dr Meera Rao)' }],
  labs: [{ code: 'hba1c', name: 'HbA1c', date: '2026-09-15', flag: 'high', value: '7.1', unit: '%', rangeAsPrinted: '4.0-5.6' }],
  adherence: { due: 60, taken: 54, skipped: 2, missed: 4, late: 1, exact: 90, percent: 90 },
  note: NOTE, sent: false,
}
const summary: HealthSummary = {
  person: prep.person, allergies: [{ substance: 'Penicillin', severity: 'severe', reaction: 'Rash' }], conditions: [{ name: 'Type 2 diabetes', since: '2016-01-01' }],
  medicines: prep.medicines, medicineChanges: [], readings: [], labs: [], adherence: null, preparedOn: '2026-11-28', note: NOTE, sent: false,
}
const source = (s: Partial<MailSource> = {}): MailSource => ({
  id: 'ms1', serverId: 'gmail', serverName: 'Gmail', personId: 'p-me', personName: 'Me', sender: 'reports@lab.example', subject: null,
  hasPassword: true, enabled: true, signedIn: true, lastStatus: 'nothing_new', lastCheckedAt: '2026-11-27T10:00:00Z', lastDocumentId: null, ...s,
})

function renderTab(search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrap = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[`/agents/a2?tab=health${search}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter></QueryClientProvider>
  )
  return render(<HealthTab agent={vita} />, { wrapper: wrap })
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me])
  vi.spyOn(vitaApi, 'config').mockResolvedValue([])
  vi.spyOn(vitaApi, 'conditions').mockResolvedValue([])
  vi.spyOn(vitaApi, 'doctors').mockResolvedValue([{ id: 'd1', kind: 'doctor', name: 'Dr Meera Rao' } as never])
  vi.spyOn(vitaApi, 'visits').mockResolvedValue([visit])
  vi.spyOn(vitaApi, 'checks').mockResolvedValue([])
  vi.spyOn(vitaApi, 'documents').mockResolvedValue([])
})

describe('Visit prep and summary', () => {
  it('prepares an upcoming visit with questions, readings, changes and labs, and sends nothing', async () => {
    const prepare = vi.spyOn(vitaApi, 'prepareVisit').mockResolvedValue(prep)
    renderTab('&view=visits')
    fireEvent.click(await screen.findByRole('button', { name: /Prepare/ }))
    expect(await screen.findByRole('heading', { name: 'Prepare for the visit' })).toBeInTheDocument()
    expect(prepare).toHaveBeenCalledWith('v1')
    expect(screen.getByText('Can the night dose move earlier?')).toBeInTheDocument()
    expect(screen.getByText('Medicine changes since the visit on 15 Sep 2026')).toBeInTheDocument()
    expect(screen.getByText(/Metformin: 500 mg to 1000 mg/)).toBeInTheDocument()
    expect(screen.getByText('Lab: high (4.0-5.6)')).toBeInTheDocument()
    expect(screen.getByText(/Vita default/)).toBeInTheDocument()
    expect(screen.getAllByText(NOTE).length).toBeGreaterThan(0)
    expect(screen.getByText(/Nothing is sent to the doctor/)).toBeInTheDocument()
    expect(screen.getByText(/62 years · Female · Blood group O\+/)).toBeInTheDocument()
  })

  it('has an error state for prep', async () => {
    vi.spyOn(vitaApi, 'prepareVisit').mockRejectedValue(new Error('down'))
    renderTab('&view=visit-prep&visit=v1')
    expect(await screen.findByText('Could not prepare this visit.')).toBeInTheDocument()
  })

  it('asks for private medicines only when ticked', async () => {
    const get = vi.spyOn(vitaApi, 'summary').mockResolvedValue(summary)
    renderTab('&view=visits')
    fireEvent.click(await screen.findByRole('button', { name: 'Health summary' }))
    expect(await screen.findByText('Penicillin')).toBeInTheDocument()
    expect(get).toHaveBeenLastCalledWith('p-me', { condition: null, includePrivate: false })
    fireEvent.click(screen.getByLabelText('Include medicines marked private'))
    await waitFor(() => expect(get).toHaveBeenLastCalledWith('p-me', { condition: null, includePrivate: true }))
  })

  it('keeps a visit’s questions', async () => {
    const update = vi.spyOn(vitaApi, 'updateVisit').mockResolvedValue(visit)
    renderTab('&view=visits')
    fireEvent.click(await screen.findByRole('button', { name: 'Change this visit' }))
    fireEvent.click(screen.getByRole('button', { name: 'Add a question' }))
    fireEvent.change(screen.getByLabelText('Question 2'), { target: { value: 'Is the eye check due?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save visit' }))
    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][2].questions).toEqual([{ text: 'Can the night dose move earlier?' }, { text: 'Is the eye check due?' }])
  })
})

describe('Vaccinations and stays', () => {
  it('needs a vaccine and a date, then saves the dose', async () => {
    vi.spyOn(vitaApi, 'vaccinations').mockResolvedValue([])
    const add = vi.spyOn(vitaApi, 'addVaccination').mockResolvedValue({} as never)
    renderTab('&view=vaccinations')
    expect(await screen.findByText(/No vaccinations recorded/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Add a dose/ }))
    const form = screen.getByRole('form', { name: 'Add a dose' })
    fireEvent.change(within(form).getByLabelText('Vaccine'), { target: { value: 'Influenza' } })
    expect(within(form).getByRole('button', { name: 'Save dose' })).toBeDisabled()
    fireEvent.change(within(form).getByLabelText('Given on'), { target: { value: '2026-10-02' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save dose' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-me', expect.objectContaining({ vaccine: 'Influenza', givenOn: '2026-10-02', doseNumber: null })))
  })

  it('has an error state', async () => {
    vi.spyOn(vitaApi, 'vaccinations').mockRejectedValue(new Error('down'))
    renderTab('&view=vaccinations')
    expect(await screen.findByText('Could not load the vaccinations.')).toBeInTheDocument()
  })

  it('shows a stay with its nights and refuses an end before the start', async () => {
    vi.spyOn(vitaApi, 'events').mockResolvedValue([{ id: 'e1', personId: 'p-me', kind: 'admission', title: 'Pneumonia', fromDate: '2025-03-14', toDate: '2025-03-18', hospital: 'City Hospital', doctorId: null, notes: null, documentIds: null }])
    renderTab('&view=surgeries')
    expect(await screen.findByText(/14 Mar 2025 to 18 Mar 2025 · 4 nights · City Hospital/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Add an entry' }))
    const form = screen.getByRole('form', { name: 'Add a surgery or stay' })
    fireEvent.change(within(form).getByLabelText('From'), { target: { value: '2026-05-10' } })
    fireEvent.change(within(form).getByLabelText('To (optional)'), { target: { value: '2026-05-01' } })
    expect(within(form).getByText('The end cannot be before the start.')).toBeInTheDocument()
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})

describe('Connect', () => {
  it('points to Connectors when no mailbox is connected', async () => {
    vi.spyOn(vitaApi, 'mailSources').mockResolvedValue({ sources: [], servers: [] })
    renderTab('&view=connect')
    expect(await screen.findByText(/No mailbox is connected/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Connectors' })).toHaveAttribute('href', '/connectors')
  })

  it('adds a sender with a password and never shows the password again', async () => {
    vi.spyOn(vitaApi, 'mailSources').mockResolvedValue({ sources: [], servers: [{ serverId: 'gmail', name: 'Gmail', signedIn: true, enabled: true }] })
    const add = vi.spyOn(vitaApi, 'addMailSource').mockResolvedValue(source())
    renderTab('&view=connect')
    fireEvent.click(await screen.findByRole('button', { name: 'Add a sender' }))
    const form = screen.getByRole('form', { name: 'Add a sender' })
    fireEvent.change(within(form).getByLabelText('Sender (address or name)'), { target: { value: 'reports@lab.example' } })
    fireEvent.click(within(form).getByLabelText('The lab locks its PDFs with a password'))
    fireEvent.change(within(form).getByLabelText('PDF password'), { target: { value: '01011962' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith({ serverId: 'gmail', personId: 'p-me', sender: 'reports@lab.example', subject: null, password: '01011962' }))
  })

  it('checks now, says what it found and opens the review', async () => {
    vi.spyOn(vitaApi, 'mailSources').mockResolvedValue({ sources: [source()], servers: [{ serverId: 'gmail', name: 'Gmail', signedIn: true, enabled: true }] })
    vi.spyOn(vitaApi, 'checkMailSource').mockResolvedValue({ status: 'to_review', documentId: 'doc9', found: 1, message: 'Found one report. Review what Vita read.' })
    vi.spyOn(vitaApi, 'document').mockResolvedValue({ id: 'doc9', kind: 'lab_report', title: 'Lab report', hasDraft: false, confirmed: false, needsPassword: false, extractionStatus: 'none' } as never)
    renderTab('&view=connect')
    expect(await screen.findByText('Nothing new')).toBeInTheDocument()
    expect(screen.getByText('Password kept')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Check now/ }))
    expect(await screen.findByText('Found one report. Review what Vita read.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Review what Vita read' }))
    expect(await screen.findByRole('heading', { name: 'Review: Lab report' })).toBeInTheDocument()
  })

  it('says when the mailbox needs signing in again', async () => {
    vi.spyOn(vitaApi, 'mailSources').mockResolvedValue({ sources: [source({ signedIn: false })], servers: [{ serverId: 'gmail', name: 'Gmail', signedIn: false, enabled: true }] })
    renderTab('&view=connect')
    expect(await screen.findByText('The mailbox needs signing in again')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Check now/ })).toBeDisabled()
  })
})
