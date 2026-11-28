import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { AgentDto } from '@/types'
import type { ConditionSummary, HomeView, LabReport, LabResult, LabTrend, RangeView, VitaCheck, VitaPerson } from '@/types/vita'
import { HealthTab } from '../HealthTab'
import { changeWords, groupResults } from '../labs'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto

const report = (r: Partial<LabReport> = {}): LabReport => ({
  id: 'r1', personId: 'p-me', labName: 'Sample Diagnostics', collectedOn: '2026-09-15', reportedOn: null, documentId: 'doc1', doctorId: null,
  status: 'confirmed', notes: null, version: 3, date: '2026-09-15', rows: 3, labFlagged: 2, ...r,
})
const result = (r: Partial<LabResult> = {}): LabResult => ({
  id: 'x1', reportId: 'r1', testCode: 'hba1c', testNameAsPrinted: 'HbA1c', section: null, valueAsPrinted: '7.1', valueNum: 7.1, valueText: null,
  unit: '%', refText: '4.0-5.6', flagAsPrinted: 'H', flag: 'high', confidence: 0.97, sortOrder: 0,
  test: { code: 'hba1c', name: 'HbA1c', group: 'Diabetes', unit: '%', decimals: 1 },
  shown: { value: '7.1', unit: '%', range: '4.0–5.6', converted: false },
  labFlag: { flag: 'high', label: 'Lab: high (4.0–5.6)' },
  target: { status: 'outside_range', range: '7.0 or lower', source: 'vita_default', label: 'Vita default, needs verification', needsVerification: true, outside: [] },
  secondary: [{ what: 'HbA1c in mmol/mol', value: 54.1, shown: '54.1', unit: 'mmol/mol', needsVerification: true, source: null }],
  ...r,
})
const ldl = result({
  id: 'x2', testCode: 'ldl', testNameAsPrinted: 'LDL Cholesterol', valueAsPrinted: '3.1', valueNum: 3.1, unit: 'mmol/L', refText: '<2.6', flagAsPrinted: null, sortOrder: 1,
  test: { code: 'ldl', name: 'LDL cholesterol', group: 'Lipids', unit: 'mg/dL', decimals: 0 },
  shown: { value: '120', unit: 'mg/dL', range: '<101', converted: true, asPrinted: '3.1 mmol/L', needsVerification: true },
  labFlag: { flag: 'high', label: 'Lab: high (<101)' }, target: { status: 'not_set', label: 'Not set: ask your doctor' }, secondary: undefined,
})
const count = result({
  id: 'x3', testCode: 'tlc', testNameAsPrinted: 'Total leucocyte count', valueAsPrinted: '7,500', valueNum: 7500, unit: '/cumm', refText: '4000-11000', flagAsPrinted: null, flag: 'normal', confidence: 0.6, sortOrder: 2,
  test: { code: 'tlc', name: 'Total leucocyte count', group: 'Blood count', unit: '/cumm', decimals: 0 },
  shown: { value: '7500', unit: '/cumm', range: '4000–11000', converted: false },
  labFlag: { flag: 'normal', label: 'Lab: normal (4000–11000)' }, target: { status: 'not_set', label: 'Not set: ask your doctor' }, secondary: undefined,
})
const point = (id: string, date: string, value: string, status: 'in_range' | 'outside_range') => ({
  ...result({ id, shown: { value, unit: '%', range: '4.0–5.6', converted: false }, target: { status, label: 'Vita default, needs verification' } }),
  date, reportId: 'r-' + id, labName: 'Sample Diagnostics', comparable: true,
})
const trend: LabTrend = {
  test: { code: 'hba1c', name: 'HbA1c', group: 'Diabetes', unit: '%', decimals: 1 },
  target: { status: 'set', range: '7.0 or lower', source: 'vita_default', label: 'Vita default, needs verification', needsVerification: true },
  points: [point('t1', '2026-03-10', '8.2', 'outside_range'), point('t2', '2026-06-12', '7.6', 'outside_range'), point('t3', '2026-09-15', '7.1', 'outside_range')],
  count: 3, changeSinceFirst: { by: -1.1, shown: '-1.1', direction: 'down' }, changeSincePrevious: { by: -0.5, shown: '-0.5', direction: 'down' },
}
const hba1cRange: RangeView = {
  kind: null, labTestCode: 'hba1c', context: null, setting: null, status: 'set', range: '7.0 or lower', source: 'vita_default',
  label: 'Vita default, needs verification', needsVerification: true, doctorId: null, doctorName: null, setOn: null, own: null, doctorTarget: null,
}

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
  vi.spyOn(vitaApi, 'labs').mockResolvedValue([report(), report({ id: 'r0', date: '2026-06-12', labName: null, labFlagged: 0, rows: 2 })])
  vi.spyOn(vitaApi, 'lab').mockResolvedValue({ report: report(), date: '2026-09-15', results: [result(), ldl, count] })
  vi.spyOn(vitaApi, 'labTrend').mockResolvedValue(trend)
  vi.spyOn(vitaApi, 'ranges').mockResolvedValue([hba1cRange])
  vi.spyOn(vitaApi, 'labTests').mockResolvedValue([
    { code: 'hba1c', name: 'HbA1c', group: 'Diabetes', unit: '%', decimals: 1, conversions: [] },
    { code: 'ldl', name: 'LDL cholesterol', group: 'Lipids', unit: 'mg/dL', decimals: 0, conversions: [] },
  ])
  vi.spyOn(vitaApi, 'checks').mockResolvedValue([])
})

describe('Lab results list', () => {
  it('lists reports with how many tests the lab flagged, in words', async () => {
    renderTab('&view=labs')
    expect(await screen.findByText('2 flagged by the lab')).toBeInTheDocument()
    expect(screen.getByText('None flagged by the lab')).toBeInTheDocument()
    expect(screen.getByText(/Lab not named · 2 tests/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Open the report of 15 Sep 2026/ }))
    expect(await screen.findByRole('heading', { name: 'Lab report, 15 Sep 2026' })).toBeInTheDocument()
  })

  it('has an empty and an error state', async () => {
    vi.spyOn(vitaApi, 'labs').mockResolvedValueOnce([])
    const { unmount } = renderTab('&view=labs')
    expect(await screen.findByText(/No reports yet/)).toBeInTheDocument()
    unmount()
    vi.spyOn(vitaApi, 'labs').mockRejectedValueOnce(new Error('down'))
    renderTab('&view=labs')
    expect(await screen.findByText('Could not load the lab reports.')).toBeInTheDocument()
  })

  it('sets a doctor’s lab target by test code', async () => {
    const put = vi.spyOn(vitaApi, 'putRange').mockResolvedValue({} as never)
    renderTab('&view=labs')
    fireEvent.click(await screen.findByRole('button', { name: 'Add doctor’s target' }))
    fireEvent.change(screen.getByLabelText('Up to, %'), { target: { value: '7.5' } })
    await screen.findByRole('option', { name: 'Dr Meera Rao' })
    fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd1' } })
    fireEvent.change(screen.getByLabelText('Set on'), { target: { value: '2026-10-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(put).toHaveBeenCalledWith('p-me', { labTestCode: 'hba1c', setBy: 'doctor', low: null, high: '7.5', doctorId: 'd1', setOn: '2026-10-01', note: null }))
  })
})

describe('A lab report', () => {
  it('keeps the lab flag and the target apart, and labels each figure', async () => {
    renderTab('&view=lab&report=r1')
    const panel = (await screen.findByRole('heading', { name: 'Diabetes' })).closest('section') as HTMLElement
    expect(within(panel).getByText('Lab: high (4.0–5.6)')).toBeInTheDocument()
    expect(within(panel).getByText('Outside range')).toBeInTheDocument()
    expect(within(panel).getByText(/Vita default/)).toBeInTheDocument()
    expect(within(panel).getAllByText('Needs verification').length).toBeGreaterThan(0)
    expect(within(panel).getByText(/HbA1c in mmol\/mol/)).toBeInTheDocument()
    expect(screen.getByText(/Printed 3.1 mmol\/L, shown in mg\/dL/)).toBeInTheDocument()
    expect(screen.getAllByText('Not set: ask your doctor').length).toBe(2)
    expect(screen.getByText(/Vita was unsure of this row/)).toBeInTheDocument()
  })

  it('groups tests in the brief’s order', async () => {
    renderTab('&view=lab&report=r1')
    await screen.findByRole('heading', { name: 'Diabetes' })
    const headings = screen.getAllByRole('heading').map(h => h.textContent)
    expect(headings.indexOf('Diabetes')).toBeLessThan(headings.indexOf('Lipids'))
    expect(headings.indexOf('Lipids')).toBeLessThan(headings.indexOf('Blood count'))
  })

  it('opens a test’s trend with the change since the first report and a table view', async () => {
    renderTab('&view=lab&report=r1')
    fireEvent.click(await screen.findByRole('button', { name: 'Trend of HbA1c' }))
    expect(await screen.findByText('HbA1c over time')).toBeInTheDocument()
    expect(screen.getByText('−1.1 %')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'HbA1c across 3 reports' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show as a table' }))
    expect(screen.getAllByRole('row')).toHaveLength(4)
  })

  it('sends only the corrected rows with the version', async () => {
    const save = vi.spyOn(vitaApi, 'updateLab').mockResolvedValue({ report: report(), date: '2026-09-15', results: [result()] })
    renderTab('&view=lab&report=r1')
    fireEvent.click(await screen.findByRole('button', { name: /Correct/ }))
    fireEvent.change(screen.getAllByLabelText('Value')[2], { target: { value: '7500' } })
    fireEvent.click(screen.getAllByLabelText('Remove this row')[1])
    fireEvent.click(screen.getByRole('button', { name: 'Save corrections' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('r1', {
      labName: 'Sample Diagnostics', collectedOn: '2026-09-15', version: 3,
      results: [{ id: 'x2', remove: true }, { id: 'x3', valueAsPrinted: '7500' }],
    }))
  })

  it('asks before deleting a report', async () => {
    const remove = vi.spyOn(vitaApi, 'removeLab').mockResolvedValue(undefined)
    renderTab('&view=lab&report=r1')
    fireEvent.click(await screen.findByRole('button', { name: 'Delete this report' }))
    expect(remove).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Delete report' }))
    await waitFor(() => expect(remove).toHaveBeenCalledWith('r1'))
  })

  it('has an error state', async () => {
    vi.spyOn(vitaApi, 'lab').mockRejectedValue(new Error('gone'))
    renderTab('&view=lab&report=r1')
    expect(await screen.findByText('Could not open this report.')).toBeInTheDocument()
  })
})

describe('Checks', () => {
  it('needs a date before a check can be saved', async () => {
    const add = vi.spyOn(vitaApi, 'addCheck').mockResolvedValue({} as VitaCheck)
    vi.spyOn(vitaApi, 'visits').mockResolvedValue([])
    renderTab('&view=visits')
    fireEvent.click(await screen.findByRole('button', { name: 'Record a check' }))
    const form = screen.getByRole('form', { name: 'Record a check' })
    expect(within(form).getByRole('button', { name: 'Save check' })).toBeDisabled()
    fireEvent.change(within(form).getByLabelText('Done on'), { target: { value: '2026-05-01' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save check' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-me', { kind: 'eye', customName: null, conditionId: null, doneOn: '2026-05-01', nextDueOn: null, resultNote: null }))
  })

  it('shows on a condition page what is due, from where, and lab results in place of the placeholder', async () => {
    const summary: ConditionSummary = {
      condition: { id: 'c1', name: 'Type 2 diabetes', template: 'diabetes_t2', status: 'active', since: null, advice: null, private: false, notes: null },
      template: 'diabetes_t2', measures: [], medicines: [], labResults: [trend],
      checks: [
        { check: 'eye', state: 'needs_verification', label: 'Vita default · needs verification', interval: { timesPerYearAtLeast: 1 }, status: 'due', lastDone: '2025-05-01', lastDoneFrom: 'check', dueOn: '2026-05-01', dueFrom: 'interval' },
        { check: 'hba1c', state: 'needs_verification', label: 'Vita default · needs verification', status: 'upcoming', lastDone: '2026-09-15', lastDoneFrom: 'lab', dueOn: '2026-12-15', dueFrom: 'doctor' },
        { check: 'lipids', state: 'not_set', label: 'Not set: ask your doctor', status: 'not_set', statusLabel: 'Not set: ask your doctor' },
      ],
    }
    vi.spyOn(vitaApi, 'conditionSummary').mockResolvedValue(summary)
    renderTab('&view=condition&condition=c1')
    expect(await screen.findByText('Due')).toBeInTheDocument()
    expect(screen.getByText(/Due since 1 May 2026 \(from the interval\)/)).toBeInTheDocument()
    expect(screen.getByText('Coming up')).toBeInTheDocument()
    expect(screen.getByText(/Last done 15 Sep 2026 \(from a lab report\) · Next by 15 Dec 2026 \(the doctor’s date\)/)).toBeInTheDocument()
    expect(screen.getByText(/−1.1 since the first report/)).toBeInTheDocument()
    expect(screen.queryByText(/Add a report/)).toBeNull()
  })
})

describe('Home', () => {
  it('shows the last lab report', async () => {
    const home: HomeView = {
      person: { id: 'p-me', name: 'Me', recordOpen: true, status: 'active' },
      quickStart: { completed: [], skipped: [], next: null, done: true, total: 8 },
      latest: [], redFlags: [], conditions: [], nextVisit: null, slots: [], reminders: [],
      lastLabReport: { id: 'r1', date: '2026-09-15', labName: 'Sample Diagnostics', rows: 6, labFlagged: 3 },
    }
    vi.spyOn(vitaApi, 'home').mockResolvedValue(home)
    vi.spyOn(vitaApi, 'emergencyCard').mockRejectedValue(new Error('not needed'))
    renderTab()
    expect(await screen.findByText('Last lab report, 15 Sep 2026')).toBeInTheDocument()
    expect(screen.getByText(/6 tests · 3 flagged by the lab/)).toBeInTheDocument()
  })
})

describe('helpers', () => {
  it('orders groups and words a change', () => {
    expect(groupResults([count, ldl, result()]).map(([g]) => g)).toEqual(['Diabetes', 'Lipids', 'Blood count'])
    expect(changeWords({ shown: '-1.1', direction: 'down' })).toBe('−1.1')
    expect(changeWords({ shown: '0.4', direction: 'up' })).toBe('+0.4')
    expect(changeWords({ shown: '0', direction: 'same' })).toBe('no change')
  })
})
