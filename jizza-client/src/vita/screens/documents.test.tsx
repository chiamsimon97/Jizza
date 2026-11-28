import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { ApiError } from '@/lib/api'
import type { AgentDto } from '@/types'
import type { DocumentDraft, VitaDocument, VitaPerson } from '@/types/vita'
import { HealthTab } from '../HealthTab'
import { rowsToSave } from './DocumentReview'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const mother: VitaPerson = { id: 'p-mom', displayName: 'Mother', kind: 'parent', type: 'individual', dob: null, status: 'active', sortOrder: 1 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto

const doc = (d: Partial<VitaDocument> = {}): VitaDocument => ({
  id: 'doc1', personId: 'p-me', kind: 'prescription', title: 'Dr Rao prescription', documentDate: '2026-11-20', doctorId: null, conditionId: null,
  fileId: 'f1', contentType: 'image/jpeg', sizeBytes: 1000, pageCount: 1, extractionStatus: 'done', extractionError: null, extractedAt: null,
  needsPassword: false, hasDraft: true, draftRows: 3, confirmed: false, confirmedAt: null, confirmedByUserId: null, private: false, notes: null, createdAt: '2026-11-20T10:00:00Z', ...d,
})
const draft = (d: Partial<DocumentDraft> = {}): DocumentDraft => ({
  kind: 'prescription', documentDate: '2026-11-20', readFrom: 'photo', patient: { name: 'Mrs S', age: '64', sex: 'F' },
  patientCheck: { status: 'match' },
  tests: [{ name: 'HbA1c', valueAsPrinted: '7.9', value: 7.9, unit: '%', rangeAsPrinted: '4.0-5.6', flagAsPrinted: 'H', flag: 'high', needsCheck: false, confidence: 0.95 }],
  medicines: [
    { name: 'Metformin', nameUnsure: false, strength: '500 mg', form: 'tablet', doseAsPrinted: '1-0-1', timingAsPrinted: 'after food', durationAsPrinted: '30 days',
      schedule: { times: ['08:00', '20:00'], recurrence: 'FREQ=DAILY', doseUnits: 1, doseUnit: 'tablet', withFood: 'after' }, asNeeded: false, startDate: null, endDate: null, needsCheck: false },
    { name: null, nameUnsure: true, strength: null, form: 'tablet', doseAsPrinted: '0-0-1', timingAsPrinted: null, durationAsPrinted: null,
      schedule: { times: ['21:00'], recurrence: 'FREQ=DAILY', doseUnits: 1, doseUnit: 'tablet', withFood: 'any' }, asNeeded: false, startDate: null, endDate: null, needsCheck: true },
  ],
  ...d,
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
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me, mother])
  vi.spyOn(vitaApi, 'config').mockResolvedValue([])
  vi.spyOn(vitaApi, 'conditions').mockResolvedValue([])
  vi.spyOn(vitaApi, 'doctors').mockResolvedValue([])
  vi.spyOn(vitaApi, 'documents').mockResolvedValue([doc()])
  vi.spyOn(vitaApi, 'document').mockResolvedValue(doc())
  vi.spyOn(vitaApi, 'draft').mockResolvedValue({ document: doc(), draft: draft() })
})

describe('Documents list', () => {
  it('lists documents with their state in words and opens the review', async () => {
    renderTab('&view=documents')
    expect(await screen.findByText('Dr Rao prescription')).toBeInTheDocument()
    expect(screen.getByText(/Waiting for your review · 3 rows/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Review' }))
    expect(await screen.findByText('Review: Dr Rao prescription')).toBeInTheDocument()
  })

  it('uploads the chosen file, then asks Vita to read it', async () => {
    vi.spyOn(vitaApi, 'documents').mockResolvedValue([])
    const up = vi.spyOn(vitaApi, 'uploadDocument').mockResolvedValue(doc({ id: 'doc2', hasDraft: false, extractionStatus: 'none' }))
    const read = vi.spyOn(vitaApi, 'extractDocument').mockResolvedValue({ document: doc({ id: 'doc2' }), draft: draft() })
    renderTab('&view=documents')
    expect(await screen.findByText(/No documents yet/)).toBeInTheDocument()
    const file = new File(['x'], 'rx.jpg', { type: 'image/jpeg' })
    fireEvent.change(screen.getByLabelText('Choose a file'), { target: { files: [file] } })
    fireEvent.click(await screen.findByRole('button', { name: 'Upload and read' }))
    await waitFor(() => expect(up).toHaveBeenCalledWith('p-me', file, expect.anything()))
    await waitFor(() => expect(read).toHaveBeenCalledWith('doc2'))
    expect(await screen.findByText(/Review:/)).toBeInTheDocument()
  })

  it('filters by kind', async () => {
    vi.spyOn(vitaApi, 'documents').mockResolvedValue([doc(), doc({ id: 'doc3', kind: 'lab_report', title: 'Thyroid panel' })])
    renderTab('&view=documents')
    await screen.findByText('Thyroid panel')
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'lab_report' } })
    expect(screen.queryByText('Dr Rao prescription')).not.toBeInTheDocument()
    expect(screen.getByText('Thyroid panel')).toBeInTheDocument()
  })

  it('opens the upload from the Add menu', async () => {
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: /Add/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Document or report/ }))
    expect(await screen.findByRole('region', { name: 'Upload a document' })).toBeInTheDocument()
  })
})

describe('Extraction review', () => {
  it('outlines a name Vita could not read, leaves it empty and blocks confirm until it is typed or left out', async () => {
    renderTab('&view=document&doc=doc1')
    expect(await screen.findByText(/Vita could not read this name/)).toBeInTheDocument()
    const names = screen.getAllByLabelText('Name') as HTMLInputElement[]
    expect(names[1].value).toBe('')
    expect(names[1].closest('div.ring-2')).not.toBeNull()
    const confirm = screen.getByRole('button', { name: 'Confirm 3 rows' })
    expect(confirm).toBeDisabled()
    const leaveOut = screen.getAllByLabelText('Leave out')
    fireEvent.click(leaveOut[leaveOut.length - 1])
    expect(screen.getByRole('button', { name: 'Confirm 2 rows' })).toBeEnabled()
  })

  it('sends the edited draft without its own patient check as truth, then says what was saved', async () => {
    const confirm = vi.spyOn(vitaApi, 'confirmDraft').mockResolvedValue({ document: doc({ confirmed: true }), saved: { medicines: [{ id: 'm1' }], tests: 1 } } as never)
    renderTab('&view=document&doc=doc1')
    const names = await screen.findAllByLabelText('Name') as HTMLInputElement[]
    fireEvent.change(names[1], { target: { value: 'Atorvastatin' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm 3 rows' }))
    await waitFor(() => expect(confirm).toHaveBeenCalled())
    const [id, sent, opts] = confirm.mock.calls[0]
    expect(id).toBe('doc1')
    expect(sent.medicines[1].name).toBe('Atorvastatin')
    expect(opts).toEqual({ patientConfirmed: false, doctorId: null })
    expect(await screen.findByText(/1 medicine was added/)).toBeInTheDocument()
  })

  it('holds confirm on a patient mismatch until the person checks it', async () => {
    vi.spyOn(vitaApi, 'draft').mockResolvedValue({ document: doc(), draft: draft({ patientCheck: { status: 'mismatch', reasons: ['The age on it is 64; this record says 34.'] }, medicines: [] }) })
    const confirm = vi.spyOn(vitaApi, 'confirmDraft').mockResolvedValue({ document: doc({ confirmed: true }), saved: { medicines: [], tests: 1 } } as never)
    renderTab('&view=document&doc=doc1')
    const banner = await screen.findByRole('alert', { name: 'Patient does not match' })
    expect(within(banner).getByText(/this record says 34/)).toBeInTheDocument()
    const button = screen.getByRole('button', { name: 'Confirm 1 row' })
    expect(button).toBeDisabled()
    fireEvent.click(within(banner).getByLabelText('I checked: it belongs to this person'))
    fireEvent.click(button)
    await waitFor(() => expect(confirm).toHaveBeenCalledWith('doc1', expect.anything(), { patientConfirmed: true, doctorId: null }))
  })

  it('shows the server’s words when confirm is refused', async () => {
    vi.spyOn(vitaApi, 'confirmDraft').mockRejectedValue(new ApiError(409, { detail: 'This document may belong to someone else.' } as never))
    vi.spyOn(vitaApi, 'draft').mockResolvedValue({ document: doc(), draft: draft({ medicines: [] }) })
    renderTab('&view=document&doc=doc1')
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm 1 row' }))
    expect(await screen.findByText('This document may belong to someone else.')).toBeInTheDocument()
  })

  it('unlocks a locked PDF with a password and says when it is wrong', async () => {
    const locked = doc({ hasDraft: false, needsPassword: true, extractionStatus: 'failed', contentType: 'application/pdf', draftRows: 0 })
    vi.spyOn(vitaApi, 'document').mockResolvedValue(locked)
    const read = vi.spyOn(vitaApi, 'extractDocument')
      .mockResolvedValueOnce({ document: locked, draft: null, needsPassword: true })
      .mockResolvedValueOnce({ document: doc(), draft: draft() })
    renderTab('&view=document&doc=doc1')
    expect(await screen.findByText('This PDF is locked')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Unlock and read' }))
    expect(await screen.findByText('That password did not open it.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: '01011962' } })
    fireEvent.click(screen.getByRole('button', { name: 'Unlock and read' }))
    await waitFor(() => expect(read).toHaveBeenLastCalledWith('doc1', '01011962'))
    expect(await screen.findByText('Test results (1)')).toBeInTheDocument()
  })

  it('shows the lab flag as printed, apart from any target', async () => {
    renderTab('&view=document&doc=doc1')
    expect(await screen.findByText('Lab range: 4.0-5.6')).toBeInTheDocument()
    expect(screen.getByText('Flag printed: H')).toBeInTheDocument()
    expect((screen.getByLabelText('Lab flag') as HTMLSelectElement).value).toBe('high')
  })

  it('discards what Vita read and goes back to the list', async () => {
    const discard = vi.spyOn(vitaApi, 'discardDraft').mockResolvedValue(undefined as never)
    renderTab('&view=document&doc=doc1')
    fireEvent.click(await screen.findByRole('button', { name: 'Discard what Vita read' }))
    await waitFor(() => expect(discard).toHaveBeenCalledWith('doc1'))
    expect(await screen.findByText('Kept documents')).toBeInTheDocument()
  })

  it('counts only the rows kept', () => {
    const d = draft()
    expect(rowsToSave(d)).toBe(3)
    d.tests[0].skip = true
    expect(rowsToSave(d)).toBe(2)
  })
})
