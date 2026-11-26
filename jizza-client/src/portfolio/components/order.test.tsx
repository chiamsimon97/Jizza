import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { OrderPreview } from '@/types/finance'
import { OrderDialog } from './OrderDialog'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  )
}

afterEach(() => vi.restoreAllMocks())

function preview(over: Partial<OrderPreview> = {}): OrderPreview {
  return {
    accountId: 'a1', instrumentId: 'i1', instrumentName: 'EXAMPLECO', symbol: 'EXAMPLECO', exchange: 'NSE', side: 'sell',
    quantity: 5, orderType: 'market', limitPrice: null, price: 1600, priceDate: '2026-11-26', estimate: 8000, held: 12,
    lots: [{ acquiredOn: '2024-01-10', quantity: 5, cost: 7500, gain: 500, term: 'lt' }],
    shortTermGain: null, longTermGain: 500, unknownTermGain: null, canPlace: true, placeVia: 'Kite', whyNot: null,
    brokerUrl: 'https://kite.zerodha.com/', ...over,
  }
}

describe('Prepare order', () => {
  it('shows the lots a sale uses and places it only after the password', async () => {
    vi.spyOn(financeApi, 'prepareOrder').mockResolvedValue(preview())
    const place = vi.spyOn(financeApi, 'placeOrder').mockResolvedValue({ orderId: 'o1', status: 'sent', brokerOrderId: '1512', message: 'Kite took the order (1512).' })
    render(<OrderDialog accountId="a1" instrumentId="i1" name="EXAMPLECO" side="sell" quantity={5} onClose={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByLabelText('Lots this sale uses')).toBeInTheDocument()
    expect(screen.getByText(/Long term/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Place with Kite/ }))
    const submit = screen.getByRole('button', { name: 'Place order' })
    expect(submit).toBeDisabled()
    expect(place).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
    fireEvent.click(submit)
    await waitFor(() => expect(place).toHaveBeenCalledWith(expect.objectContaining({ side: 'sell', quantity: 5, password: 'secret' })))
    expect(await screen.findByText('Kite took the order (1512).')).toBeInTheDocument()
  })

  it('offers the broker site and I bought it when no connected broker can take it', async () => {
    vi.spyOn(financeApi, 'prepareOrder').mockResolvedValue(preview({ side: 'buy', lots: [], canPlace: false, placeVia: null,
      whyNot: "Kite doesn't offer order placing here." }))
    const bought = vi.fn()
    render(<OrderDialog accountId="a1" instrumentId="i1" name="EXAMPLECO" onClose={() => {}} onBought={bought} />, { wrapper: wrap() })
    expect(await screen.findByText("Kite doesn't offer order placing here.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open in my broker/ })).toHaveAttribute('href', 'https://kite.zerodha.com/')
    expect(screen.queryByRole('button', { name: /Place with/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'I bought it' }))
    expect(bought).toHaveBeenCalled()
  })
})
