import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { AgentDto } from '@/types'
import type { FinConfigItem, FinPerson, FinProfileView } from '@/types/finance'
import { PortfolioTab } from './PortfolioTab'
import { HIDDEN_AMOUNT, HideAmountsContext, Money } from './components/Money'
import { ageOn, isManagedAdult } from './screens/People'
import { labelFor, summarise } from './screens/Settings'

const me: FinPerson = {
  id: 'p-me', userId: 'u', displayName: 'Me', kind: 'self', type: 'individual', dob: null,
  earning: true, dependent: false, status: 'active', sortOrder: 0, handedOverToUserId: null,
}
const riya: FinPerson = { ...me, id: 'p-riya', displayName: 'Riya', kind: 'child', type: 'minor', dob: '2018-06-01', earning: false, dependent: true }

function profile(overrides: Partial<FinProfileView['settings']> = {}): FinProfileView {
  return {
    profile: { id: 'prof', baseCurrency: 'INR', version: 0 },
    people: [me, riya],
    settings: { advanced: false, hideAmounts: false, ...overrides },
    setupProgress: { quickStart: 'not_started', peopleManaged: 1 },
  }
}

const kai = { id: 'a1', key: 'finance', name: 'Kai' } as AgentDto

function Where() {
  const loc = useLocation()
  return <output data-testid="where">{loc.search}</output>
}

function renderTab(path = '/agents/a1?tab=portfolio') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrap = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/agents/:agentId" element={<>{children}<Where /></>} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
  return render(<PortfolioTab agent={kai} />, { wrapper: wrap })
}

afterEach(() => vi.restoreAllMocks())

describe('PortfolioTab', () => {
  it('opens on Home with the Quick start empty state and the disclaimer', async () => {
    vi.spyOn(financeApi, 'profile').mockResolvedValue(profile())
    renderTab()
    expect(await screen.findByText('Start Quick start')).toBeInTheDocument()
    expect(screen.getByText(/not advice from a SEBI-registered adviser/)).toBeInTheDocument()
    expect(screen.getByText('Observe only')).toBeInTheDocument()
  })

  it('puts the view and person in the URL', async () => {
    vi.spyOn(financeApi, 'profile').mockResolvedValue(profile())
    vi.spyOn(financeApi, 'people').mockResolvedValue([me, riya])
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'People' }))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toContain('view=people'))
    fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'p-riya' } })
    await waitFor(() => expect(screen.getByTestId('where').textContent).toContain('person=p-riya'))
    expect(screen.getByTestId('where').textContent).toContain('tab=portfolio')
  })

  it('shows what a later view will hold', async () => {
    vi.spyOn(financeApi, 'profile').mockResolvedValue(profile())
    renderTab('/agents/a1?tab=portfolio&view=loans')
    expect(await screen.findByText('Loans comes later')).toBeInTheDocument()
  })

  it('shows Rules under Investments only with Advanced on', async () => {
    vi.spyOn(financeApi, 'profile').mockResolvedValue(profile({ advanced: true }))
    renderTab('/agents/a1?tab=portfolio&view=holdings')
    expect(await screen.findByRole('button', { name: 'Rules' })).toBeInTheDocument()
  })

  it('lists Kai defaults with their badges in Settings', async () => {
    vi.spyOn(financeApi, 'profile').mockResolvedValue(profile())
    const items: FinConfigItem[] = [
      { key: 'returns.expected', area: 'returns', value: { equity: 9 }, defaultValue: { equity: 10 }, source: 'you', isOverride: true, effectiveFrom: '2000-01-01', sourceUrl: null, verifiedOn: null, needsVerification: false },
      { key: 'tax.new_regime', area: 'tax', value: { standardDeduction: 75000 }, defaultValue: { standardDeduction: 75000 }, source: 'kai_default', isOverride: false, effectiveFrom: '2026-04-01', sourceUrl: null, verifiedOn: null, needsVerification: true },
    ]
    vi.spyOn(financeApi, 'config').mockResolvedValue(items)
    renderTab('/agents/a1?tab=portfolio&view=settings')
    expect(await screen.findByText('Expected returns (% a year)')).toBeInTheDocument()
    expect(screen.getByText('Needs verification')).toBeInTheDocument()
    expect(screen.getByText('You')).toBeInTheDocument()
    expect(screen.getByText('Kai default')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reset/ })).toBeInTheDocument()
  })

  it('turns Hide amounts on from the bar', async () => {
    vi.spyOn(financeApi, 'profile').mockResolvedValue(profile())
    const update = vi.spyOn(financeApi, 'updateSettings').mockResolvedValue({ advanced: false, hideAmounts: true })
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: /Hide amounts/ }))
    await waitFor(() => expect(update).toHaveBeenCalledWith({ hideAmounts: true }))
  })
})

describe('Portfolio helpers', () => {
  it('masks amounts while Hide amounts is on', () => {
    const { rerender } = render(<Money amount={1234567} />)
    expect(screen.getByText('₹12,34,567')).toBeInTheDocument()
    rerender(<HideAmountsContext.Provider value><Money amount={1234567} /></HideAmountsContext.Provider>)
    expect(screen.getByText(HIDDEN_AMOUNT)).toBeInTheDocument()
  })

  it('works out ages and who needs a consent note', () => {
    expect(ageOn('2018-06-01', new Date(2026, 10, 25))).toBe(8)
    expect(ageOn('2008-11-26', new Date(2026, 10, 25))).toBe(17)
    expect(ageOn(null)).toBeNull()
    expect(isManagedAdult(riya)).toBe(false)
    expect(isManagedAdult({ ...riya, kind: 'parent', type: 'individual', dob: '1958-01-01' })).toBe(true)
    expect(isManagedAdult(me)).toBe(false)
  })

  it('summarises a setting in a line', () => {
    expect(summarise({ equity: 10, debt: 6.5 })).toBe('equity 10 · debt 6.5')
    expect(summarise([1, 2, 3])).toBe('3 rows')
    expect(labelFor('unknown.key')).toBe('unknown.key')
  })
})
