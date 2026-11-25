import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { FinProfileView, HealthView, QuickStartView } from '@/types/finance'
import { SampleContext } from '../data'
import {
  SAMPLE_ACTIONS, SAMPLE_CASH_FLOW, SAMPLE_HEALTH, SAMPLE_LOANS, SAMPLE_NET_WORTH, SAMPLE_PLAN, SAMPLE_POLICIES,
  SAMPLE_PROFILE, SAMPLE_PROTECTION,
} from '../sample'
import { ActionPlan } from './ActionPlan'
import { CashFlow } from './CashFlow'
import { FIXTURE_F_PLAN } from './fixtureF.testdata'
import { Home, scoreSummary } from './Home'
import { Loans, describeRuns, paymentsFor, runs } from './Loans'
import { NetWorth } from './NetWorth'
import { Protection } from './Protection'
import { QuickStart } from './QuickStart'

function wrap(sample = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter><SampleContext.Provider value={sample}>{children}</SampleContext.Provider></MemoryRouter>
    </QueryClientProvider>
  )
}

const done: FinProfileView = { ...SAMPLE_PROFILE, setupProgress: { quickStart: 'done', peopleManaged: 2 } }
const noop = () => {}

function mockReads(health: HealthView = SAMPLE_HEALTH) {
  vi.spyOn(financeApi, 'health').mockResolvedValue(health)
  vi.spyOn(financeApi, 'netWorth').mockResolvedValue(SAMPLE_NET_WORTH)
  vi.spyOn(financeApi, 'cashFlow').mockResolvedValue(SAMPLE_CASH_FLOW)
  vi.spyOn(financeApi, 'policies').mockResolvedValue(SAMPLE_POLICIES)
  vi.spyOn(financeApi, 'quickStart').mockResolvedValue({ answers: {}, progress: { completed: [], skipped: [], next: null, done: true, total: 8 } })
}

afterEach(() => vi.restoreAllMocks())

describe('Home', () => {
  it("renders Fixture D's score of 38 and its three actions from the API", async () => {
    mockReads()
    render(<Home profile={done} onOpen={noop} onAnswer={noop} onSample={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('Score 38')).toBeInTheDocument()
    const actions = screen.getAllByTestId('action')
    expect(actions).toHaveLength(3)
    expect(within(actions[0]).getByText(/Repay the ₹1.5 lakh personal loan at 14%/)).toBeInTheDocument()
    expect(within(actions[0]).getByText('+20 points')).toBeInTheDocument()
    expect(within(actions[1]).getByText('+15 points')).toBeInTheDocument()
    expect(within(actions[2]).getByText('+10 points')).toBeInTheDocument()
    expect(screen.getByText('No will recorded')).toBeInTheDocument()
  })

  it('shows a skipped step as "Not enough data", not as a red tile', async () => {
    const skipped: HealthView = {
      ...SAMPLE_HEALTH, score: 44, rawScore: 44.1,
      checks: SAMPLE_HEALTH.checks.map(c => (c.key === 'protection' ? { ...c, status: 'unknown', shown: null, value: null, points: 0 } : c)),
    }
    mockReads(skipped)
    render(<Home profile={done} onOpen={noop} onAnswer={noop} onSample={noop} />, { wrapper: wrap() })
    const tile = await screen.findByTestId('check-protection')
    expect(within(tile).getByText('Not enough data')).toBeInTheDocument()
    expect(within(tile).queryByText('Fix first')).not.toBeInTheDocument()
    expect(screen.getByText('Score 44')).toBeInTheDocument()
  })

  it('offers Quick start and the sample household before anything is set up', async () => {
    const onSample = vi.fn()
    render(<Home profile={{ ...done, setupProgress: { quickStart: 'not_started', peopleManaged: 0 } }} onOpen={noop} onAnswer={noop} onSample={onSample} />, { wrapper: wrap() })
    expect(screen.getByRole('button', { name: 'Start Quick start' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Look around with sample data/ }))
    expect(onSample).toHaveBeenCalled()
  })

  it('shows the sample household without calling the API', async () => {
    const health = vi.spyOn(financeApi, 'health')
    render(<Home profile={SAMPLE_PROFILE} onOpen={noop} onAnswer={noop} onSample={noop} />, { wrapper: wrap(true) })
    expect(await screen.findByText('Score 38')).toBeInTheDocument()
    expect(health).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Done' })).not.toBeInTheDocument()
  })

  it('sums the score up in a sentence', () => {
    expect(scoreSummary(SAMPLE_HEALTH)).toMatch(/2 checks are red/)
    expect(scoreSummary({ ...SAMPLE_HEALTH, score: null })).toMatch(/Answer a Quick start question/)
  })
})

describe('Quick start', () => {
  const view = (over: Partial<QuickStartView['progress']> = {}): QuickStartView => ({
    answers: {}, progress: { completed: [], skipped: [], next: 'people', done: false, total: 8, ...over },
  })

  it('asks one question at a time and saves each answer', async () => {
    vi.spyOn(financeApi, 'quickStart').mockResolvedValue(view({ completed: ['people'], next: 'income' }))
    const save = vi.spyOn(financeApi, 'saveQuickStart').mockResolvedValue({ progress: view({ completed: ['people', 'income'], next: 'spending' }).progress })
    render(<QuickStart profile={done} onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('Question 2 of 8')).toBeInTheDocument()
    fireEvent.change(screen.getAllByLabelText('Take-home a month')[0], { target: { value: '120000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('income', { incomes: [{ personId: 'sample-01', takeHomeMonthly: 120000 }] }))
    expect(await screen.findByText('Question 3 of 8')).toBeInTheDocument()
  })

  it('says what is missing instead of saving half an answer', async () => {
    vi.spyOn(financeApi, 'quickStart').mockResolvedValue(view({ completed: ['people'], next: 'income' }))
    const save = vi.spyOn(financeApi, 'saveQuickStart')
    render(<QuickStart profile={done} onOpen={noop} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter at least one take-home amount')
    expect(save).not.toHaveBeenCalled()
  })

  it('skips a question and moves on', async () => {
    vi.spyOn(financeApi, 'quickStart').mockResolvedValue(view({ completed: ['people', 'income', 'spending', 'loans'], next: 'insurance' }))
    const skip = vi.spyOn(financeApi, 'skipQuickStart').mockResolvedValue({ progress: view({ next: 'savings' }).progress })
    render(<QuickStart profile={done} onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('What cover do you have?')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Skip for now' }))
    await waitFor(() => expect(skip).toHaveBeenCalledWith('insurance'))
    expect(await screen.findByText('What have you saved so far?')).toBeInTheDocument()
  })

  it('ends on the result: score, net worth, surplus and the top three actions', async () => {
    vi.spyOn(financeApi, 'quickStart').mockResolvedValue(view({ next: null, done: true }))
    mockReads()
    render(<QuickStart profile={done} onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('Your result')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Money Health score 38 out of 100' })).toBeInTheDocument()
    expect(screen.getAllByTestId('action')).toHaveLength(3)
  })
})

describe('Loans', () => {
  it("describes Fixture F's plan month by month", () => {
    const p = FIXTURE_F_PLAN.plan
    expect(paymentsFor(p, 'f-00')).toBe('₹60,000 in Dec 2026')
    expect(paymentsFor(p, 'f-01')).toBe('₹10,000 in Dec 2026; ₹30,000 Jan 2027 to Mar 2027')
    expect(paymentsFor(p, 'f-02')).toBe('₹40,000 Jan 2027 to Mar 2027; ₹49,474 Apr 2027 to May 2027')
    const investable = describeRuns(runs(p.monthly.map(m => ({ month: m.month, amount: m.investable }))), true)
    expect(investable).toBe('₹0 Dec 2026 to Mar 2027; ₹20,526 Apr 2027 to May 2027; ₹70,000 from Jun 2027')
  })

  it('lists each loan and the repayment plan', async () => {
    render(<Loans />, { wrapper: wrap(true) })
    expect(await screen.findAllByTestId('loan-card')).toHaveLength(SAMPLE_LOANS.length)
    expect(await screen.findByRole('button', { name: 'Highest rate first' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText(SAMPLE_PLAN.plan.order[0].label, { selector: 'td' })).toBeInTheDocument()
  })
})

describe('Other Money Health screens on the sample household', () => {
  it('Action plan groups by layer', async () => {
    render(<ActionPlan onOpen={noop} />, { wrapper: wrap(true) })
    expect(await screen.findByRole('heading', { name: 'Survival' })).toBeInTheDocument()
    expect(screen.getAllByTestId('action')).toHaveLength(SAMPLE_ACTIONS.filter(a => a.status === 'open').length)
  })

  it('Cash flow shows needs, wants and saved', async () => {
    render(<CashFlow onOpen={noop} onAnswer={noop} />, { wrapper: wrap(true) })
    expect(await screen.findByText('Needs, wants and saved')).toBeInTheDocument()
    expect(screen.getByText('43.3% needs, 27.8% wants, 16.7% saved of take-home.')).toBeInTheDocument()
  })

  it('Protection shows the term gap with its method', async () => {
    render(<Protection people={SAMPLE_PROFILE.people} onOpen={noop} onAnswer={noop} />, { wrapper: wrap(true) })
    expect(await screen.findAllByText(/× yearly income/)).not.toHaveLength(0)
    expect(screen.getByText('₹2.72 Cr')).toBeInTheDocument()
    expect(SAMPLE_PROTECTION.protection.gaps).toHaveLength(3)
  })

  it('Net worth lists assets with their valued-on date', async () => {
    render(<NetWorth people={SAMPLE_PROFILE.people} onOpen={noop} />, { wrapper: wrap(true) })
    expect(await screen.findByText('Net worth and assets')).toBeInTheDocument()
    expect(await screen.findByText('Savings balance')).toBeInTheDocument()
    expect(screen.getByText('your estimate')).toBeInTheDocument()
  })
})
