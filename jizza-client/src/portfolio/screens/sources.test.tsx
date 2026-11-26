import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { FinAccount, SourcesView } from '@/types/finance'
import { ConnectedSources } from './ConnectedSources'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  )
}

afterEach(() => vi.restoreAllMocks())

const demat = { id: 'a1', kind: 'demat', provider: 'Zerodha', nickname: null, last4: null } as unknown as FinAccount
const kite = { serverId: 's1', name: 'Kite', signedIn: true, enabled: true, funds: true, placesOrders: false }

describe('Connected brokers', () => {
  it('points to Connectors when no broker is added', async () => {
    vi.spyOn(financeApi, 'sources').mockResolvedValue({ sources: [], servers: [] })
    render(<ConnectedSources accounts={[demat]} onReview={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByRole('link', { name: 'Connectors' })).toHaveAttribute('href', '/connectors')
  })

  it('links a broker to a demat account', async () => {
    vi.spyOn(financeApi, 'sources').mockResolvedValue({ sources: [], servers: [kite] })
    const link = vi.spyOn(financeApi, 'linkSource').mockResolvedValue({} as never)
    render(<ConnectedSources accounts={[demat]} onReview={() => {}} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: 'Connect' }))
    await waitFor(() => expect(link).toHaveBeenCalledWith('s1', 'a1'))
  })

  it('opens the review after a sync with changes, and asks to sign in when needed', async () => {
    const view: SourcesView = {
      servers: [kite],
      sources: [{ id: 'x1', serverId: 's1', serverName: 'Kite', accountId: 'a1', accountName: 'Zerodha', enabled: true,
        signedIn: true, lastStatus: null, lastSyncedAt: null, lastBatchId: null }],
    }
    vi.spyOn(financeApi, 'sources').mockResolvedValue(view)
    const sync = vi.spyOn(financeApi, 'syncSource')
      .mockResolvedValueOnce({ status: 'to_review', batchId: 'b1', holdings: 3, message: 'Review the holdings, then save them.' })
      .mockResolvedValueOnce({ status: 'needs_sign_in', batchId: null, holdings: 0, message: 'Sign in to Kite on Connectors, then sync again.' })
    const onReview = vi.fn()
    render(<ConnectedSources accounts={[demat]} onReview={onReview} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: /Sync now/ }))
    await waitFor(() => expect(onReview).toHaveBeenCalledWith('b1'))
    fireEvent.click(screen.getByRole('button', { name: /Sync now/ }))
    await waitFor(() => expect(sync).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('link', { name: 'Open Kite' })).toHaveAttribute('href', '/connectors/mcp/s1')
  })
})
