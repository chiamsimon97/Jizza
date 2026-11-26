import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { FinAccount, FinCategory, HealthView, ImportBatch } from '@/types/finance'
import { SAMPLE_PROFILE } from '../sample'
import { Connect, mappingComplete } from './Connect'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

afterEach(() => vi.restoreAllMocks())

const people = SAMPLE_PROFILE.people
const self = people.find(p => p.kind === 'self')!
const ACCOUNT: FinAccount = {
  id: 'acc1', personId: self.id, kind: 'savings', provider: 'Example Bank', nickname: 'Main', last4: '1234',
  source: 'manual', isEmergency: false, status: 'active',
}
const CATEGORIES: FinCategory[] = [
  { id: 'c-rent', name: 'Rent', bucket: 'need', isEssential: true, systemKey: 'rent', hidden: false },
  { id: 'c-life', name: 'Lifestyle and other', bucket: 'want', isEssential: false, systemKey: 'lifestyle', hidden: false },
]
const HEALTH = { trend: [{ at: '2025-04-01T00:00:00Z', score: 60 }] } as unknown as HealthView

function batch(over: Partial<ImportBatch> = {}): ImportBatch {
  return {
    id: 'b1', personId: self.id, accountId: 'acc1', kind: 'bank_csv', status: 'parsed', mapping: {}, error: null,
    createdAt: '2025-04-10T10:00:00Z',
    stats: {
      fileName: 'april.csv',
      counts: { rows: 3, new: 2, duplicates: 1, needsLook: 1, unreadable: 1, holdings: 0 },
      rows: [
        { i: 0, line: 2, date: '2025-04-02', amount: 15000, direction: 'out', description: 'HOUSE RENT', kind: 'spend', categoryId: 'c-rent', needsLook: false, duplicate: false },
        { i: 1, line: 3, date: '2025-04-06', amount: 250, direction: 'out', description: 'CORNER BAKERY', kind: 'spend', needsLook: true, duplicate: false },
        { i: 2, line: 4, date: '2025-04-01', amount: 99, direction: 'out', description: 'OLD ROW', kind: 'spend', needsLook: false, duplicate: true },
      ],
      unreadable: [{ line: 5, reason: 'No amount' }],
      balance: { amount: 69750, date: '2025-04-06' },
    },
    ...over,
  }
}

function mockBasics(history: ImportBatch[] = []) {
  vi.spyOn(financeApi, 'imports').mockResolvedValue(history)
  vi.spyOn(financeApi, 'accounts').mockResolvedValue([ACCOUNT])
  vi.spyOn(financeApi, 'categories').mockResolvedValue(CATEGORIES)
  vi.spyOn(financeApi, 'health').mockResolvedValue(HEALTH)
}

describe('Connect and imports', () => {
  it('shows each source with what it can and cannot do', async () => {
    mockBasics()
    render(<Connect people={people} />, { wrapper: wrap() })
    expect(await screen.findByText('Bank statement')).toBeInTheDocument()
    expect(screen.getByText('Mutual funds (CAS)')).toBeInTheDocument()
    expect(screen.getByLabelText('What Bank statement can\'t do')).toHaveTextContent('Scanned (picture) PDFs')
    expect(screen.getByText('No imports yet.')).toBeInTheDocument()
    expect(screen.getByText(/Money Health check:/)).toBeInTheDocument()
  })

  it('reads a bank file, shows the review and saves with the chosen category remembered', async () => {
    mockBasics()
    const upload = vi.spyOn(financeApi, 'upload').mockResolvedValue(batch())
    const saved = batch({ status: 'committed', stats: { ...batch().stats, counts: { ...batch().stats.counts!, imported: 2 } } })
    const commit = vi.spyOn(financeApi, 'commitImport').mockResolvedValue(saved)
    vi.spyOn(financeApi, 'importBatch').mockImplementation(async () => (commit.mock.calls.length ? saved : batch()))
    render(<Connect people={people} />, { wrapper: wrap() })
    fireEvent.click((await screen.findAllByRole('button', { name: 'Import a file' }))[0])
    const read = screen.getByRole('button', { name: 'Read the file' })
    expect(read).toBeDisabled()
    const file = new File(['Date,Description,Debit,Credit\n'], 'april.csv', { type: 'text/csv' })
    fireEvent.change(screen.getByLabelText('File'), { target: { files: [file] } })
    fireEvent.click(read)
    await waitFor(() => expect(upload).toHaveBeenCalledWith({ kind: 'bank_csv', file, accountId: 'acc1' }))

    expect(await screen.findByText('2 new')).toBeInTheDocument()
    expect(screen.getByText('1 already imported')).toBeInTheDocument()
    expect(screen.getByText('1 need a look')).toBeInTheDocument()
    expect(screen.getByText('Already imported')).toBeInTheDocument()
    expect(screen.getByText(/couldn’t read/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Category for CORNER BAKERY'), { target: { value: 'c-life' } })
    fireEvent.click(screen.getByLabelText('Always for rows like this'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(commit).toHaveBeenCalledWith('b1', { categories: { 1: 'c-life' }, always: [1] }))
    expect(await screen.findByText('2 rows')).toBeInTheDocument()
  })

  it('asks for the CAS password and never sends it again after reading', async () => {
    mockBasics()
    const cas = batch({ kind: 'cas_pdf', accountId: null, stats: { fileName: 'cas.pdf', counts: { rows: 0, new: 0, duplicates: 0, needsLook: 0, unreadable: 0, holdings: 1 }, rows: [], holdings: [{ name: 'Example Fund', isin: null, assetClass: 'equity', subCategory: 'mutual_fund', units: 10, value: 1000, asOf: '2025-03-31', group: 'folio:1' }], groups: [{ key: 'folio:1', last4: '0001', provider: 'Example Mutual Fund', accountId: null }] } })
    const upload = vi.spyOn(financeApi, 'upload').mockRejectedValueOnce(new Error('That password did not open the PDF. Check it and try again.')).mockResolvedValue(cas)
    vi.spyOn(financeApi, 'importBatch').mockResolvedValue(cas)
    render(<Connect people={people} />, { wrapper: wrap() })
    fireEvent.click((await screen.findAllByRole('button', { name: 'Import a file' }))[1])
    const file = new File(['%PDF'], 'cas.pdf', { type: 'application/pdf' })
    fireEvent.change(screen.getByLabelText('File'), { target: { files: [file] } })
    fireEvent.change(screen.getByLabelText('PDF password'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Read the file' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('password did not open')
    fireEvent.change(screen.getByLabelText('PDF password'), { target: { value: 'RIGHT1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Read the file' }))
    await waitFor(() => expect(upload).toHaveBeenLastCalledWith({ kind: 'cas_pdf', file, personId: self.id, password: 'RIGHT1' }))
    expect(await screen.findByText('Example Fund')).toBeInTheDocument()
    expect(screen.getByText(/1 folio:/)).toHaveTextContent('Example Mutual Fund ••0001 (new)')
  })

  it('reads a bank PDF on the same card, with its password when it has one', async () => {
    mockBasics()
    const pdfBatch = batch({ kind: 'bank_pdf', stats: { ...batch().stats, fileName: 'april.pdf' } })
    const upload = vi.spyOn(financeApi, 'upload').mockResolvedValue(pdfBatch)
    vi.spyOn(financeApi, 'importBatch').mockResolvedValue(pdfBatch)
    render(<Connect people={people} />, { wrapper: wrap() })
    fireEvent.click((await screen.findAllByRole('button', { name: 'Import a file' }))[0])
    expect(screen.queryByLabelText('PDF password')).not.toBeInTheDocument()
    const file = new File(['%PDF'], 'april.pdf', { type: 'application/pdf' })
    fireEvent.change(screen.getByLabelText('File'), { target: { files: [file] } })
    fireEvent.change(screen.getByLabelText('PDF password'), { target: { value: 'CUST01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Read the file' }))
    await waitFor(() => expect(upload).toHaveBeenCalledWith({ kind: 'bank_pdf', file, accountId: 'acc1', password: 'CUST01' }))
    // A bank PDF is reviewed like a bank CSV: categories on each row.
    expect(await screen.findByLabelText('Category for CORNER BAKERY')).toBeInTheDocument()
  })

  it('lets the person choose the columns when Kai cannot tell', async () => {
    mockBasics()
    const unsure = batch({
      status: 'needs_review', error: 'Kai couldn\'t tell which column is which. Choose the columns to go on.',
      stats: { fileName: 'odd.csv', columns: { headerRow: 0, sure: false, headers: ['When', 'What', 'Out', 'In'], sample: [['When', 'What', 'Out', 'In'], ['01/04/2025', 'Something', '100', '']] } },
    })
    const upload = vi.spyOn(financeApi, 'upload').mockResolvedValueOnce(unsure).mockResolvedValue(batch())
    vi.spyOn(financeApi, 'importBatch').mockImplementation(async id => (id === 'b2' ? unsure : batch()))
    unsure.id = 'b2'
    render(<Connect people={people} />, { wrapper: wrap() })
    fireEvent.click((await screen.findAllByRole('button', { name: 'Import a file' }))[0])
    const file = new File(['x'], 'odd.csv', { type: 'text/csv' })
    fireEvent.change(screen.getByLabelText('File'), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: 'Read the file' }))
    expect(await screen.findByText('Which column is which?')).toBeInTheDocument()
    const go = screen.getByRole('button', { name: 'Read with these columns' })
    expect(go).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: 'When' } })
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'What' } })
    fireEvent.change(screen.getByLabelText('Money out (debit)'), { target: { value: 'Out' } })
    fireEvent.change(screen.getByLabelText('Money in (credit)'), { target: { value: 'In' } })
    fireEvent.click(go)
    await waitFor(() => expect(upload).toHaveBeenLastCalledWith({
      kind: 'bank_csv', file, accountId: 'acc1', mapping: { headerRow: 0, date: 'When', description: 'What', debit: 'Out', credit: 'In' },
    }))
    expect(await screen.findByText('2 new')).toBeInTheDocument()
  })

  it('undoes a saved import from the history and shows why when it cannot', async () => {
    mockBasics([batch({ status: 'committed', stats: { ...batch().stats, counts: { ...batch().stats.counts!, imported: 2 } } })])
    const undo = vi.spyOn(financeApi, 'undoImport').mockRejectedValue(new Error('A later import (may.csv) changed the same balances. Undo that one first.'))
    render(<Connect people={people} />, { wrapper: wrap() })
    const table = await screen.findByRole('table', { name: 'Import history' })
    expect(within(table).getByText('Saved')).toBeInTheDocument()
    expect(within(table).getByText('Example Bank · Main · ••1234')).toBeInTheDocument()
    fireEvent.click(within(table).getByRole('button', { name: 'Undo' }))
    await waitFor(() => expect(undo).toHaveBeenCalledWith('b1'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Undo that one first')
  })

  it('knows which column choices are enough', () => {
    expect(mappingComplete({ headerRow: 0, date: 'D', description: 'N', amount: 'A' })).toBe(true)
    expect(mappingComplete({ headerRow: 0, date: 'D', description: 'N', debit: 'Dr' })).toBe(false)
  })
})
