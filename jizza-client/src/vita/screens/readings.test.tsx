import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { ApiError } from '@/lib/api'
import type { AgentDto } from '@/types'
import type {
  ConditionSummary, Doctor, EmergencyCard, Evaluation, HomeView, RangeView, Reading, TrendView, VitaConfigItem, VitaPerson,
} from '@/types/vita'
import { HealthTab } from '../HealthTab'
import { bandsFromText } from '../components/Readings'
import { showValue } from '../readings'
import { targetWords } from './Visits'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const mother: VitaPerson = { ...me, id: 'p-mum', displayName: 'Mother', kind: 'parent', sortOrder: 1 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto
const units = { weight: 'kg', height: 'cm', temperature: 'C' } as const

const evalOf = (e: Partial<Evaluation>): Evaluation => ({
  status: 'in_range', outside: [], range: 'below 135/85', rangeSource: 'vita_default', doctorId: null, doctorName: null, setOn: null,
  needsVerification: true, label: 'Vita default, needs verification', redFlag: null, ...e,
})
const bp = (e: Partial<Reading> = {}): Reading => ({
  id: 'm1', personId: 'p-me', kind: 'bp', value: 132, value2: 84, value3: 72, unit: 'mmHg', context: null, meal: null, setting: 'home',
  takenAt: '2026-11-27T02:30:00Z', note: null, source: 'manual', pairId: null, evaluation: evalOf({}), ...e,
})
const card: EmergencyCard = {
  personId: 'p-me', name: 'Me', age: null, bloodGroup: 'b_pos', organDonor: null, preferredHospital: 'City Hospital', preferredHospitalPhone: '080 1234',
  allergiesNoneKnown: true, allergies: [], conditionsNoneKnown: false, conditions: [], medicines: [], medicinesAvailable: false,
  emergencyContacts: [], emergencyNumbers: { emergency: '112' }, emergencyNumbersNeedVerification: true, updatedOn: '2026-11-27', closed: false,
}
const item = (key: string, extra: Partial<VitaConfigItem>): VitaConfigItem => ({
  key, area: key.split('.')[0], value: null, defaultValue: null, source: 'vita_default', isOverride: false, effectiveFrom: '2026-01-01',
  sourceUrl: null, verifiedOn: null, needsVerification: true, state: 'needs_verification', label: 'Vita default · needs verification', ...extra,
})
const config: VitaConfigItem[] = [
  item('display.units', { value: units, state: 'product_default', needsVerification: false, label: 'Vita default' }),
  item('clinical.redflags.bp', { value: { unit: 'mmHg', systolicAbove: 180, diastolicAbove: 120, question: 'Any of these now?', signs: ['chest pain', 'breathlessness'] } }),
]
const home = (h: Partial<HomeView> = {}): HomeView => ({
  person: { id: 'p-me', name: 'Me', recordOpen: true, status: 'active' },
  quickStart: { completed: ['dob', 'sex', 'height_weight', 'blood_group', 'conditions', 'allergies', 'medicines', 'contact'], skipped: [], next: null, done: true, total: 8 },
  latest: [], redFlags: [], conditions: [], nextVisit: null, slots: [], reminders: [], ...h,
})
const trend = (t: Partial<TrendView['trend']> = {}, range: TrendView['range'] = { status: 'set', range: 'below 135/85', source: 'vita_default', label: 'Vita default, needs verification', needsVerification: true }): TrendView => ({
  kind: 'bp', days: 30, zone: 'Asia/Kolkata', range,
  trend: {
    kind: 'bp', count: 2, last: null,
    all: { value: { sum: 264, count: 2, mean: 132, shown: 132, min: 130, max: 134 }, value2: { sum: 168, count: 2, mean: 84, shown: 84, min: 82, max: 86 }, count: 2, outside: 0 },
    morning: { value: { sum: 130, count: 1, mean: 130, shown: 130, min: 130, max: 130 }, value2: { sum: 82, count: 1, mean: 82, shown: 82, min: 82, max: 82 }, count: 1, outside: 0 },
    evening: { value: { sum: 134, count: 1, mean: 134, shown: 134, min: 134, max: 134 }, value2: { sum: 86, count: 1, mean: 86, shown: 86, min: 86, max: 86 }, count: 1, outside: 0 },
    days: [{ date: '2026-11-27', morning: [{ id: 'm1', takenAt: '2026-11-27T02:30:00Z', value: 130, value2: 82, pairId: null, status: 'in_range' }], evening: [{ id: 'm2', takenAt: '2026-11-27T13:30:00Z', value: 134, value2: 86, pairId: null, status: 'in_range' }] }],
    ...t,
  },
})
const ranges: RangeView[] = [
  { kind: 'bp', context: null, setting: 'home', status: 'set', range: 'below 135/85', source: 'vita_default', label: 'Vita default, needs verification', needsVerification: true, doctorId: null, doctorName: null, setOn: null, own: null, doctorTarget: null },
  { kind: 'bp', context: null, setting: 'clinic', status: 'set', range: '130/80 or lower', source: 'doctor', label: 'from Dr S Iyer, set on 2 Nov 2026', needsVerification: false, doctorId: 'd1', doctorName: 'S Iyer', setOn: '2026-11-02', own: null,
    doctorTarget: { id: 'r1', kind: 'bp', context: null, setting: 'clinic', low: null, high: 130, low2: null, high2: 80, setBy: 'doctor', doctorId: 'd1', setOn: '2026-11-02', note: null } },
]
const doctor: Doctor = {
  id: 'd1', kind: 'doctor', name: 'Dr S Iyer', speciality: 'Physician', clinic: 'Iyer Clinic', phone: '98450 00000', address: null, notes: null,
  nextVisit: { visitId: 'v1', scheduledAt: '2026-12-05T04:30:00Z', personId: 'p-mum', personName: 'Mother' },
  targets: [{ id: 'r1', kind: 'bp', context: null, setting: 'clinic', low: null, high: 130, low2: null, high2: 80, setBy: 'doctor', doctorId: 'd1', setOn: '2026-11-02', note: null, personId: 'p-mum', personName: 'Mother' }],
}

function mockApi() {
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me, mother])
  vi.spyOn(vitaApi, 'config').mockResolvedValue(config)
  vi.spyOn(vitaApi, 'home').mockResolvedValue(home())
  vi.spyOn(vitaApi, 'emergencyCard').mockResolvedValue(card)
  vi.spyOn(vitaApi, 'trend').mockResolvedValue(trend())
  vi.spyOn(vitaApi, 'readings').mockResolvedValue([])
  vi.spyOn(vitaApi, 'ranges').mockResolvedValue(ranges)
  vi.spyOn(vitaApi, 'doctors').mockResolvedValue([doctor])
  vi.spyOn(vitaApi, 'visits').mockResolvedValue([])
  vi.spyOn(vitaApi, 'conditions').mockResolvedValue([])
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

describe('reading words', () => {
  it('shows values in the person’s units and reads the server’s range words', () => {
    expect(showValue('bp', 132, 84, units)).toBe('132/84 mmHg')
    expect(showValue('weight', 64.5, null, { ...units, weight: 'lb' })).toBe('142.2 lb')
    expect(showValue('temperature', 37, null, { ...units, temperature: 'F' })).toBe('98.6 °F')
    expect(bandsFromText('below 135/85')).toEqual([{ low: null, high: 135 }, { low: null, high: 85 }])
    expect(bandsFromText('80–130')).toEqual([{ low: 80, high: 130 }])
    expect(bandsFromText('7.0 or lower')).toEqual([{ low: null, high: 7 }])
    expect(bandsFromText('something else')).toBeNull()
    expect(targetWords({ low: null, high: 130, low2: null, high2: 80 })).toBe('up to 130 / up to 80')
  })
})

describe('Vitals', () => {
  it('shows the target with who set it, the averages and morning against evening, never by colour alone', async () => {
    renderTab('&view=vitals')
    expect(await screen.findByText('Average, 30 days')).toBeInTheDocument()
    expect(screen.getAllByText('Vita default').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Needs verification').length).toBeGreaterThan(0)
    expect(screen.getByText('Mornings')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Blood pressure, 2 readings/ })).toBeInTheDocument()
    expect(await screen.findByText(/from Dr S Iyer, set on 2 Nov 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Systolic above 180 or diastolic above 120/)).toBeInTheDocument()
  })

  it('says "Target not set: ask your doctor" when no range holds', async () => {
    vi.mocked(vitaApi.trend).mockResolvedValue(trend({}, { status: 'not_set', label: 'Not set: ask your doctor' }))
    renderTab('&view=vitals')
    expect(await screen.findByText('Target not set: ask your doctor')).toBeInTheDocument()
  })

  it('asks "Already saved. Add anyway?" on a duplicate and adds it only on yes', async () => {
    const add = vi.spyOn(vitaApi, 'addReading')
      .mockRejectedValueOnce(new ApiError(409, { type: 'conflict', title: 'Conflict', status: 409, detail: 'Already saved. Add anyway?' }))
      .mockResolvedValueOnce(bp())
    renderTab('&view=vitals')
    fireEvent.click(await screen.findByRole('button', { name: /Add reading/ }))
    const form = screen.getByRole('form', { name: 'Add a reading' })
    fireEvent.change(within(form).getByLabelText('Systolic (top)'), { target: { value: '132' } })
    fireEvent.change(within(form).getByLabelText('Diastolic (bottom)'), { target: { value: '84' } })
    fireEvent.change(within(form).getByLabelText('Pulse (optional)'), { target: { value: '72' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save reading' }))
    expect(await screen.findByText('Already saved. Add anyway?')).toBeInTheDocument()
    expect(add).toHaveBeenCalledWith('p-me', { kind: 'bp', value: '132', value2: '84', value3: '72', unit: 'mmHg', setting: 'home' }, { secondOf: undefined, confirmDuplicate: false })
    fireEvent.click(screen.getByRole('button', { name: 'Add anyway' }))
    expect(await screen.findByText('Add a second reading')).toBeInTheDocument()
    expect(add).toHaveBeenLastCalledWith('p-me', expect.objectContaining({ kind: 'bp' }), { secondOf: undefined, confirmDuplicate: true })
  })

  it('a second reading is paired with the first and shows the average', async () => {
    const add = vi.spyOn(vitaApi, 'addReading').mockResolvedValueOnce(bp()).mockResolvedValueOnce(bp({ id: 'm2', value: 128, value2: 80, pairId: 'm1' }))
    renderTab('&view=vitals')
    fireEvent.click(await screen.findByRole('button', { name: /Add reading/ }))
    let form = screen.getByRole('form', { name: 'Add a reading' })
    fireEvent.change(within(form).getByLabelText('Systolic (top)'), { target: { value: '132' } })
    fireEvent.change(within(form).getByLabelText('Diastolic (bottom)'), { target: { value: '84' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save reading' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Add a second reading' }))
    form = screen.getByRole('form', { name: 'Add a reading' })
    fireEvent.change(within(form).getByLabelText('Systolic (top)'), { target: { value: '128' } })
    fireEvent.change(within(form).getByLabelText('Diastolic (bottom)'), { target: { value: '80' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save reading' }))
    expect(await screen.findByText('130/82 mmHg')).toBeInTheDocument()
    expect(add).toHaveBeenLastCalledWith('p-me', expect.anything(), { secondOf: 'm1', confirmDuplicate: false })
  })

  it('a typed red flag asks "Is 182/112 right?"; a no alerts nobody', async () => {
    const flagged = bp({ value: 182, value2: 112, evaluation: evalOf({ status: 'red_flag' }), confirmNeeded: true, confirmQuestion: 'Is 182/112 right?' })
    vi.spyOn(vitaApi, 'addReading').mockResolvedValue(flagged)
    const answer = vi.spyOn(vitaApi, 'answerRedFlag').mockResolvedValue({ ...flagged, alerted: false, note: 'Nobody was alerted.' })
    renderTab('&view=vitals')
    fireEvent.click(await screen.findByRole('button', { name: /Add reading/ }))
    const form = screen.getByRole('form', { name: 'Add a reading' })
    fireEvent.change(within(form).getByLabelText('Systolic (top)'), { target: { value: '182' } })
    fireEvent.change(within(form).getByLabelText('Diastolic (bottom)'), { target: { value: '112' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save reading' }))
    expect(await screen.findByText('Is 182/112 right?')).toBeInTheDocument()
    expect(screen.getByText('Nobody is alerted until you say it is right.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'No, I typed it wrong' }))
    expect(await screen.findByText('Nobody was alerted. Correct the reading or delete it.')).toBeInTheDocument()
    expect(answer).toHaveBeenCalledWith('p-me', 'm1', false, null)
  })

  it('a yes shows the server’s help at its level', async () => {
    const flagged = bp({ value: 182, value2: 112, evaluation: evalOf({ status: 'red_flag' }), confirmNeeded: true, confirmQuestion: 'Is 182/112 right?' })
    vi.spyOn(vitaApi, 'addReading').mockResolvedValue(flagged)
    vi.spyOn(vitaApi, 'answerRedFlag').mockResolvedValue({
      ...flagged, alerted: true,
      help: { level: 'urgent', wording: 'Act today. This is at a level where doctors advise urgent care.', advice: 'Call the doctor today.', question: 'Any of these now?', signs: ['chest pain'], emergencyNumbers: { emergency: '112' } },
    })
    renderTab('&view=vitals')
    fireEvent.click(await screen.findByRole('button', { name: /Add reading/ }))
    const form = screen.getByRole('form', { name: 'Add a reading' })
    fireEvent.change(within(form).getByLabelText('Systolic (top)'), { target: { value: '182' } })
    fireEvent.change(within(form).getByLabelText('Diastolic (bottom)'), { target: { value: '112' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save reading' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Yes, it’s right' }))
    const banner = await screen.findByRole('alert', { name: 'Act today' })
    expect(banner).toHaveTextContent('Call the doctor today.')
    expect(screen.getByText('The record’s manager was alerted.')).toBeInTheDocument()
  })

  it('moves a reading to the person it belongs to', async () => {
    vi.mocked(vitaApi.readings).mockResolvedValue([bp({ takenAt: new Date().toISOString() })])
    const move = vi.spyOn(vitaApi, 'moveMeasurement').mockResolvedValue({} as never)
    renderTab('&view=vitals')
    fireEvent.click(await screen.findByRole('button', { name: 'Move to another person' }))
    fireEvent.change(screen.getByLabelText('Move to'), { target: { value: 'p-mum' } })
    fireEvent.click(screen.getByRole('button', { name: 'Move reading' }))
    await waitFor(() => expect(move).toHaveBeenCalledWith('p-me', 'm1', 'p-mum'))
  })
})

describe('Home', () => {
  it('shows the latest readings with status in words and who set the target', async () => {
    vi.mocked(vitaApi.home).mockResolvedValue(home({ latest: [bp({ evaluation: evalOf({ status: 'outside_range', outside: ['systolic high'] }) })] }))
    renderTab()
    expect(await screen.findByText('Outside range: systolic high')).toBeInTheDocument()
    expect(screen.getAllByText('132/84 mmHg').length).toBeGreaterThan(0)
  })

  it('asks to confirm an unconfirmed red flag before showing Call now', async () => {
    vi.mocked(vitaApi.home).mockResolvedValue(home({
      latest: [bp({ value: 182, value2: 112, evaluation: evalOf({ status: 'red_flag' }) })],
      redFlags: [{ measurementId: 'm1', kind: 'bp', level: 'urgent', rule: 'bp', confirmed: false, advice: 'Call the doctor today.', question: 'Any of these now?', needsVerification: true, wording: 'Act today. This is at a level where doctors advise urgent care.' }],
    }))
    renderTab()
    expect(await screen.findByText('Is 182/112 mmHg right?')).toBeInTheDocument()
    expect(screen.queryByRole('alert', { name: 'Act today' })).toBeNull()
  })

  it('a confirmed red flag shows the banner with the rule’s signs from Settings', async () => {
    vi.mocked(vitaApi.home).mockResolvedValue(home({
      latest: [bp({ value: 182, value2: 112, evaluation: evalOf({ status: 'red_flag' }) })],
      redFlags: [{ measurementId: 'm1', kind: 'bp', level: 'urgent', rule: 'bp', confirmed: true, advice: 'Call the doctor today.', question: 'Any of these now?', needsVerification: true, wording: 'Act today. This is at a level where doctors advise urgent care.' }],
    }))
    const answer = vi.spyOn(vitaApi, 'answerRedFlag').mockResolvedValue({ ...bp(), alerted: true, help: { level: 'emergency', wording: 'Call now. This is at a level where doctors advise emergency care.', emergencyNumbers: { emergency: '112' } } })
    renderTab()
    const banner = await screen.findByRole('alert', { name: 'Act today' })
    expect(banner).toHaveTextContent('Act today. This is at a level where doctors advise urgent care.')
    fireEvent.click(within(banner).getByRole('button', { name: 'chest pain' }))
    fireEvent.click(within(banner).getByRole('button', { name: 'Yes, one of these now' }))
    await waitFor(() => expect(answer).toHaveBeenCalledWith('p-me', 'm1', true, true))
    expect(await screen.findByRole('alert', { name: 'Call now' })).toHaveTextContent('Call 112')
  })
})

describe('Doctors and visits', () => {
  it('lists doctors with phone, next visit and the targets they set', async () => {
    renderTab('&view=visits')
    expect(await screen.findByText('Dr S Iyer')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /98450 00000/ })).toHaveAttribute('href', 'tel:98450 00000')
    expect(screen.getByText(/Target for Mother/)).toHaveTextContent('up to 130 / up to 80, set on 2 Nov 2026')
    expect(screen.getByText(/No visits yet/)).toBeInTheDocument()
  })

  it('adds a visit with the doctor', async () => {
    const add = vi.spyOn(vitaApi, 'addVisit').mockResolvedValue({} as never)
    renderTab('&view=visits')
    fireEvent.click(await screen.findByRole('button', { name: /Add visit/ }))
    const form = screen.getByRole('form', { name: 'Add a visit' })
    fireEvent.change(within(form).getByLabelText('When'), { target: { value: '2026-12-05T10:00' } })
    await screen.findByRole('option', { name: 'Dr S Iyer' })
    fireEvent.change(within(form).getByLabelText('Doctor'), { target: { value: 'd1' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save visit' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-me', expect.objectContaining({ doctorId: 'd1', scheduledAt: '2026-12-05T10:00', status: 'planned' })))
  })
})

describe('Condition page', () => {
  it('shows key measures with their target labels and checks that are not set', async () => {
    const summary: ConditionSummary = {
      condition: { id: 'c1', name: 'High blood pressure', template: 'hypertension', status: 'active', since: '2020-01-01', advice: 'Walk daily.', private: false, notes: null, doctorId: 'd1' },
      template: 'hypertension', doctor: doctor,
      measures: [{ kind: 'bp', last7: { ...trend(), days: 7 }, last30: trend() }],
      labs: 'Add a report to see kidney and lipid tests.',
      checks: [{ check: 'kidney_function', state: 'not_set', label: 'Not set: ask your doctor' }],
      medicines: [],
    }
    vi.spyOn(vitaApi, 'conditionSummary').mockResolvedValue(summary)
    renderTab('&view=condition&condition=c1')
    expect(await screen.findByRole('heading', { name: 'High blood pressure' })).toBeInTheDocument()
    expect(screen.getByText(/Standing advice/).closest('p')).toHaveTextContent('Walk daily.')
    expect(screen.getByText('Kidney function')).toBeInTheDocument()
    expect(screen.getAllByText('Not set: ask your doctor').length).toBeGreaterThan(0)
    expect(screen.getByText('Average, 7 days')).toBeInTheDocument()
    expect(screen.getByText('Add a report to see kidney and lipid tests.')).toBeInTheDocument()
  })
})
