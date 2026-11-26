import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { FinAccount, MailSource, MailSourcesView } from '@/types/finance'
import { SAMPLE_PROFILE } from '../sample'
import { MailSources } from './MailSources'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  )
}

afterEach(() => vi.restoreAllMocks())

const people = SAMPLE_PROFILE.people
const self = people.find(p => p.kind === 'self')!
const savings = { id: 'a1', kind: 'savings', provider: 'Example Bank', nickname: null, last4: null, status: 'active' } as unknown as FinAccount
const gmail = { serverId: 's1', name: 'Gmail', signedIn: true, enabled: true }
const SOURCE: MailSource = {
  id: 'm1', serverId: 's1', serverName: 'Gmail', kind: 'bank', personId: null, accountId: 'a1', accountName: 'Example Bank',
  sender: 'statements@examplebank.test', subject: null, hasPassword: false, enabled: true, signedIn: true,
  lastStatus: null, lastCheckedAt: null, lastBatchId: null,
}

describe('Statements from your email', () => {
  it('points to Connectors when no mail connector is added', async () => {
    vi.spyOn(financeApi, 'mailSources').mockResolvedValue({ sources: [], servers: [], casSenders: 'camsonline.com kfintech.com' })
    render(<MailSources accounts={[savings]} people={people} onReview={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByRole('link', { name: 'Connectors' })).toHaveAttribute('href', '/connectors')
  })

  it('adds a CAS source for me with the PAN password, and a bank one needs its sender', async () => {
    vi.spyOn(financeApi, 'mailSources').mockResolvedValue({ sources: [], servers: [gmail], casSenders: 'camsonline.com kfintech.com' })
    const link = vi.spyOn(financeApi, 'linkMailSource').mockResolvedValue(SOURCE)
    render(<MailSources accounts={[savings]} people={people} onReview={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByPlaceholderText('camsonline.com, kfintech.com')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('PDF password'), { target: { value: 'ABCDE1234F' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(link).toHaveBeenCalledWith({
      serverId: 's1', kind: 'cas', personId: self.id, accountId: undefined, sender: undefined, subject: undefined, password: 'ABCDE1234F',
    }))

    fireEvent.change(screen.getByLabelText('What to look for'), { target: { value: 'bank' } })
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('From'), { target: { value: 'statements@examplebank.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(link).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'bank', accountId: 'a1', sender: 'statements@examplebank.test', personId: undefined,
    })))
  })

  it('checks now, asks for the password, then opens the review', async () => {
    const view: MailSourcesView = { sources: [SOURCE], servers: [gmail], casSenders: 'camsonline.com kfintech.com' }
    vi.spyOn(financeApi, 'mailSources').mockResolvedValue(view)
    vi.spyOn(financeApi, 'checkMailSource')
      .mockResolvedValueOnce({ status: 'needs_password', batchId: null, found: 0, message: 'The statement is locked. Save its PDF password on this source, then check again.' })
      .mockResolvedValueOnce({ status: 'to_review', batchId: 'b9', found: 1, message: 'A new statement is ready to review.' })
    const change = vi.spyOn(financeApi, 'changeMailSource').mockResolvedValue({ ...SOURCE, hasPassword: true })
    const onReview = vi.fn()
    render(<MailSources accounts={[savings]} people={people} onReview={onReview} />, { wrapper: wrap() })
    expect(await screen.findByText('No password saved')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Check now/ }))
    expect(await screen.findByText(/statement is locked/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Add password/ }))
    fireEvent.change(screen.getAllByLabelText('PDF password')[0], { target: { value: 'CUST01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }))
    await waitFor(() => expect(change).toHaveBeenCalledWith('m1', { password: 'CUST01', enabled: undefined }))
    fireEvent.click(screen.getByRole('button', { name: /Check now/ }))
    await waitFor(() => expect(onReview).toHaveBeenCalledWith('b9', 'bank'))
  })
})
