import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { GoalCheck,
  AllocationNode, BuilderView, FinProfileView, GoalLibraryCard, GoalView, QuickStartView, RiskView, TaxView,
} from '@/types/finance'
import { SAMPLE_CASH_FLOW, SAMPLE_PROFILE } from '../sample'
import { financialYear } from './Tax'
import { GoalDetail, Goals } from './Goals'
import { GoalWizard } from './GoalWizard'
import { PlanBuilder } from './PlanBuilder'
import { QuickStart } from './QuickStart'
import { Tax } from './Tax'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  )
}

afterEach(() => vi.restoreAllMocks())

const people = SAMPLE_PROFILE.people
const self = people.find(p => p.kind === 'self')!
const done: FinProfileView = { ...SAMPLE_PROFILE, setupProgress: { quickStart: 'in_progress', peopleManaged: 2 } }
const noop = () => {}

const LIBRARY: GoalLibraryCard[] = [
  { type: 'retirement', name: 'Retirement', priority: 'important', inflationKey: 'cpi', inflationPct: 6, targetAmountToday: 16_800_000, targetDate: '2052-06-01', bucket: 'long', ownerPersonId: self.id, hint: '20 × yearly expenses', defaults: null },
  { type: 'home', name: 'Home down payment', priority: 'important', inflationKey: 'cpi', inflationPct: 6, targetAmountToday: null, targetDate: '2031-11-01', bucket: 'mid', ownerPersonId: self.id, hint: '20% of the price plus registration', defaults: null },
  { type: 'travel', name: 'Travel', priority: 'aspirational', inflationKey: 'cpi', inflationPct: 6, targetAmountToday: null, targetDate: '2027-11-01', bucket: 'short', ownerPersonId: self.id, hint: 'Your amount', defaults: null },
]

function goalView(over: Partial<GoalView['goal']> = {}, funded = 82.5): GoalView {
  return {
    goal: {
      id: 'g1', type: 'home', name: 'Home down payment', ownerPersonId: self.id, targetDate: '2031-06-01', targetAmountToday: 2_000_000,
      inflationKey: 'cpi', priorityTier: 'important', riskProfile: null, status: 'active', currentValue: 100_000, monthlyContribution: 5_000,
      stepupPct: 0, colour: null, createdVia: 'wizard', achievedAt: null, ...over,
    },
    ownerName: 'Me',
    funding: {
      monthsToTarget: 67, bucket: 'mid', inflationPct: 6, futureTarget: 2_771_000, projected: 2_286_000, fundedPct: funded, requiredSip: 12_200,
      blendedReturnNow: 8.5, mixNow: { equity: 50, debt: 40, gold: 10 }, monthsToNextBucket: 31, nextBucket: 'short',
    },
    risk: 'moderate', riskSource: 'default', moveNote: 'Moves to Short in 31 months', expectedReturnPct: 8.5,
  }
}

describe('Quick start, steps 7 and 8', () => {
  const qs = (next: 'goals' | 'risk'): QuickStartView => ({
    answers: {}, progress: { completed: ['people', 'income', 'spending', 'loans', 'insurance', 'savings'], skipped: [], next, done: false, total: 8 },
  })

  it('saves up to three goals with what they cost today', async () => {
    vi.spyOn(financeApi, 'quickStart').mockResolvedValue(qs('goals'))
    vi.spyOn(financeApi, 'goalLibrary').mockResolvedValue(LIBRARY)
    vi.spyOn(financeApi, 'risk').mockResolvedValue({ personId: self.id, questions: [], latest: null, capacityNow: [], note: '' })
    const save = vi.spyOn(financeApi, 'saveQuickStart').mockResolvedValue({ progress: qs('risk').progress })
    render(<QuickStart profile={done} onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('Question 7 of 8')).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: 'Retirement' }))
    fireEvent.click(screen.getByRole('button', { name: 'Home down payment' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('home down payment costs today')
    fireEvent.change(screen.getAllByLabelText('What it costs in today’s rupees')[1], { target: { value: '2000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('goals', {
      goals: [{ type: 'retirement', targetAmountToday: 16_800_000 }, { type: 'home', targetAmountToday: 2_000_000 }],
    }))
    expect(await screen.findByText('Question 8 of 8')).toBeInTheDocument()
  })

  it('asks the six risk questions and saves the answers as 1, 2 or 3', async () => {
    const risk: RiskView = {
      personId: self.id, latest: null, capacityNow: [], note: 'Indicative only.',
      questions: [1, 2, 3, 4, 5, 6].map(i => ({ key: `q${i}`, text: `Question text ${i}`, answers: ['Low', 'Middle', 'High'] })),
    }
    vi.spyOn(financeApi, 'quickStart').mockResolvedValue(qs('risk'))
    vi.spyOn(financeApi, 'risk').mockResolvedValue(risk)
    const save = vi.spyOn(financeApi, 'saveQuickStart').mockResolvedValue({ progress: { ...qs('risk').progress, next: null, done: true } })
    vi.spyOn(financeApi, 'health').mockReturnValue(new Promise(() => {}))
    vi.spyOn(financeApi, 'netWorth').mockReturnValue(new Promise(() => {}))
    render(<QuickStart profile={done} onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('1. Question text 1')).toBeInTheDocument()
    for (let i = 1; i <= 5; i++) {
      fireEvent.click(within(screen.getByRole('group', { name: `${i}. Question text ${i}` })).getByRole('button', { name: 'Middle' }))
    }
    fireEvent.click(screen.getByRole('button', { name: 'See my plan' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Answer question 6')
    fireEvent.click(within(screen.getByRole('group', { name: '6. Question text 6' })).getByRole('button', { name: 'High' }))
    fireEvent.click(screen.getByRole('button', { name: 'See my plan' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('risk', { answers: [2, 2, 2, 2, 2, 3] }))
  })
})

function goalCheck(over: Partial<GoalCheck> = {}): GoalCheck {
  return {
    goalId: 'g1', name: 'Home down payment', priority: 'important', targetDate: '2031-06-01', verdict: 'at_risk', successPct: 64,
    requiredPct: 75, fundedPct: 82.5, currentValue: 100_000, monthly: 5_000, futureTarget: 2_771_000, projected: 2_286_000,
    p10: 1_900_000, p50: 2_300_000, shortfall: 485_000, extraMonthly: 7_200, requiredMonthly: 12_200, partsOutsideBand: 0,
    shortGoalShares: 0, earmarked: false, headline: 'Home down payment: At risk.',
    fix: 'Short by about ₹4,85,000; ₹7,200 more a month closes it.', previousVerdict: 'on_track', ...over,
  }
}

describe('Goal checks', () => {
  it('puts a verdict and the chance on each goal card, with a count of goals meeting their target', async () => {
    vi.spyOn(financeApi, 'goals').mockResolvedValue([goalView(), goalView({ id: 'g2', name: 'Car' })])
    vi.spyOn(financeApi, 'goalChecks').mockResolvedValue([goalCheck(), goalCheck({ goalId: 'g2', name: 'Car', verdict: 'on_track', successPct: 91, fix: 'Keep going as you are.' })])
    render(<Goals person="all" onOpenGoal={noop} onNew={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('1 of 2 goals meet their target.')).toBeInTheDocument()
    expect(screen.getByText('64% · bar 75%')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '91% chance; the bar is 75%' })).toBeInTheDocument()
    expect(screen.getByText(/₹7,200 more a month closes it/)).toBeInTheDocument()
    // An on-track goal does not repeat its fix.
    expect(screen.queryByText('Keep going as you are.')).not.toBeInTheDocument()
  })

  it('runs the checks again on request', async () => {
    vi.spyOn(financeApi, 'goals').mockResolvedValue([goalView()])
    vi.spyOn(financeApi, 'goalChecks').mockResolvedValue([goalCheck()])
    const run = vi.spyOn(financeApi, 'runGoalChecks').mockResolvedValue([goalCheck({ verdict: 'on_track', successPct: 80 })])
    render(<Goals person="all" onOpenGoal={noop} onNew={noop} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: /Check again/ }))
    await waitFor(() => expect(run).toHaveBeenCalled())
    expect(await screen.findByText('Every goal meets its target.')).toBeInTheDocument()
  })

  it('shows the range and what changed on the goal page', async () => {
    vi.spyOn(financeApi, 'goal').mockResolvedValue(goalView())
    vi.spyOn(financeApi, 'goalChecks').mockResolvedValue([goalCheck()])
    render(<GoalDetail id="g1" people={people} onBack={noop} onEdit={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('Does it meet the target?')).toBeInTheDocument()
    expect(screen.getByText('In a poor market (1 in 10)')).toBeInTheDocument()
    expect(screen.getByText('More a month to close the gap')).toBeInTheDocument()
    expect(screen.getByText('Was on track at the last nightly check.')).toBeInTheDocument()
  })
})

describe('Goals', () => {
  it('lists each live goal with its funded % and required SIP', async () => {
    vi.spyOn(financeApi, 'goals').mockResolvedValue([goalView(), goalView({ id: 'g2', name: 'Old car', status: 'achieved', achievedAt: '2026-10-01' })])
    render(<Goals person="all" onOpenGoal={noop} onNew={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('83% funded')).toBeInTheDocument()
    expect(screen.getByText('₹12,200')).toBeInTheDocument()
    expect(screen.getByText('Moves to Short in 31 months')).toBeInTheDocument()
    expect(screen.queryByText('Old car')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Show achieved and archived/ }))
    expect(screen.getByText('Old car')).toBeInTheDocument()
  })

  it('offers the library when there are no goals', async () => {
    vi.spyOn(financeApi, 'goals').mockResolvedValue([])
    const onNew = vi.fn()
    render(<Goals person="all" onOpenGoal={noop} onNew={onNew} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: 'Add a goal' }))
    expect(onNew).toHaveBeenCalled()
  })

  it('shows the funding sum on the goal detail and marks a goal achieved', async () => {
    vi.spyOn(financeApi, 'goal').mockResolvedValue(goalView())
    const achieve = vi.spyOn(financeApi, 'achieveGoal').mockResolvedValue(goalView({ status: 'achieved' }))
    render(<GoalDetail id="g1" people={people} onBack={noop} onEdit={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('83%')).toBeInTheDocument()
    expect(screen.getByText('Needed each month for 100%')).toBeInTheDocument()
    expect(screen.getByText(/Assumes 8.5% a year/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Mark achieved/ }))
    await waitFor(() => expect(achieve).toHaveBeenCalledWith('g1'))
  })
})

describe('Goal wizard', () => {
  it('walks the eight steps and saves the goal from the card', async () => {
    vi.spyOn(financeApi, 'goalLibrary').mockResolvedValue(LIBRARY)
    const add = vi.spyOn(financeApi, 'addGoal').mockResolvedValue(goalView())
    const onDone = vi.fn()
    render(<GoalWizard people={people} onDone={onDone} onCancel={noop} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: /Home down payment/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Step 2 of 8')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByRole('alert')).toHaveTextContent('today’s rupees')
    fireEvent.change(screen.getByLabelText('Target in today’s rupees'), { target: { value: '2000000' } })
    expect(screen.getByText(/At 6% inflation this is about/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Mid · 3–7 years')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText(/Once the goal is saved/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.change(screen.getByLabelText('Going in each month'), { target: { value: '5000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save goal' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith(expect.objectContaining({
      type: 'home', name: 'Home down payment', targetAmountToday: 2_000_000, targetDate: '2031-11-01', monthlyContribution: 5_000,
      priorityTier: 'important', riskProfile: null,
    })))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('g1'))
  })
})

function node(key: string, label: string, monthly: number, children: AllocationNode[] = [], source = 'system', sourceLabel = 'Moderate'): AllocationNode {
  const level = key === 'all' ? 'root' : key.split('.').length === 1 ? 'bucket' : key.split('.').length === 2 ? 'asset_class' : 'sub_category'
  return { key, label, level, monthly, lumpsum: 0, pct: 0, source, sourceLabel, edited: source === 'user', children }
}

/** Fixture E's Long bucket, as the server answers it, with mid cap set by hand. */
function builderView(edited: boolean): BuilderView {
  const eq = node('long.equity', 'Equity', 17_500, [
    node('long.equity.large_cap_index', 'Large cap or Nifty 50 index', edited ? 8_100 : 8_700),
    node('long.equity.flexi_cap', 'Flexi cap', edited ? 5_600 : 6_100),
    node('long.equity.mid_cap', 'Mid cap', edited ? 3_000 : 1_800, [], edited ? 'user' : 'system', edited ? 'You' : 'Moderate'),
    node('long.equity.small_cap', 'Small cap', edited ? 800 : 900),
  ])
  const long = node('long', 'Long (7 years and more)', 25_000, [eq, node('long.debt', 'Debt', 5_000), node('long.gold', 'Gold', 2_500)], 'system', 'Age 30–39')
  return {
    input: { monthly: 50_000, lumpsum: 300_000, stepUpPct: 0, riskProfile: null, age: null, edits: edited ? [{ key: 'long.equity.mid_cap', monthly: 3_000 }] : [] },
    age: 34, ageSource: 'dob', risk: 'moderate', riskSource: 'default', savedAt: null, savedLines: [],
    result: {
      monthlyIn: 50_000, lumpsumIn: 300_000, offTheTop: [], monthlyInvested: 50_000, lumpsumInvested: 300_000, age: 34, risk: 'moderate',
      tree: node('all', 'Money in', 50_000, [node('short', 'Short (under 3 years)', 10_000, [], 'system', 'Age 30–39'), node('mid', 'Mid (3–7 years)', 15_000, [], 'system', 'Age 30–39'), long]),
      lines: [], goalLens: [], notes: [],
      timeline: { today: [], investedToday: 195_000, parked: 105_000, stpMonths: 6, stp: Array.from({ length: 6 }, (_, i) => ({ month: i + 1, amount: 17_500 })) },
    },
  }
}

describe('Plan builder', () => {
  it("shows Fixture E's split and marks a changed line as yours", async () => {
    vi.spyOn(financeApi, 'builder').mockResolvedValue(builderView(false))
    vi.spyOn(financeApi, 'cashFlow').mockResolvedValue(SAMPLE_CASH_FLOW)
    const preview = vi.spyOn(financeApi, 'previewBuilder').mockImplementation(async body => builderView(!!body.edits?.length))
    render(<PlanBuilder onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText(/Off the top: ₹0/)).toBeInTheDocument()
    expect(screen.getByText('₹8,700')).toBeInTheDocument()
    expect(screen.getByText('₹1,05,000')).toBeInTheDocument()
    expect(screen.getAllByText('₹17,500')).toHaveLength(2) // Long equity, and each STP month

    fireEvent.click(screen.getByText('₹1,800').closest('button')!)
    const box = screen.getByLabelText('Monthly amount for Mid cap')
    fireEvent.change(box, { target: { value: '3000' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(preview).toHaveBeenLastCalledWith(expect.objectContaining({ edits: [{ key: 'long.equity.mid_cap', monthly: 3_000 }] })))
    expect(await screen.findByText('₹8,100')).toBeInTheDocument()
    expect(screen.getAllByText('You').length).toBeGreaterThan(0)
  })

  it('saves the plan', async () => {
    vi.spyOn(financeApi, 'builder').mockResolvedValue(builderView(false))
    vi.spyOn(financeApi, 'cashFlow').mockResolvedValue(SAMPLE_CASH_FLOW)
    vi.spyOn(financeApi, 'previewBuilder').mockResolvedValue(builderView(false))
    const save = vi.spyOn(financeApi, 'saveBuilder').mockResolvedValue({ ...builderView(false), savedAt: '2026-11-25T10:00:00Z' })
    render(<PlanBuilder onOpen={noop} />, { wrapper: wrap() })
    fireEvent.click(await screen.findByRole('button', { name: 'Save plan' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ monthly: 50_000, lumpsum: 300_000 })))
  })
})

describe('Tax', () => {
  const tax = (old: boolean): TaxView => ({
    personId: self.id, personName: 'Me', year: financialYear(), grossAnnual: 1_920_000, unverified: ['tax.old_regime', 'tax.cess'], gainsNote: 'Gains come with holdings.',
    profile: { personId: self.id, taxYear: financialYear(), deductions: old ? { section80C: 150_000, section80DSelf: 25_000 } : {} },
    comparison: {
      status: 'ok', needsVerification: true, notes: [],
      newRegime: { taxableIncome: 1_845_000, slabTax: 169_000, rebate: 0, cess: 6_760, total: 175_760, steps: [{ label: 'Standard deduction', amount: -75_000 }] },
      oldRegime: old ? { taxableIncome: 1_695_000, slabTax: 321_000, rebate: 0, cess: 12_840, total: 333_840, steps: [] } : null,
      oldStatus: old ? 'ok' : 'not_enough_data', cheaper: old ? 'new' : null, gap: old ? 158_080 : null,
    },
  })

  it('warns that the figures are unverified and compares the regimes once deductions are in', async () => {
    vi.spyOn(financeApi, 'tax').mockResolvedValue(tax(false))
    const save = vi.spyOn(financeApi, 'saveTax').mockResolvedValue(tax(true))
    render(<Tax people={people} person="all" onOpen={noop} />, { wrapper: wrap() })
    expect(await screen.findByText('These tax figures have not been verified.')).toBeInTheDocument()
    expect(screen.getByText('₹1,75,760')).toBeInTheDocument()
    expect(screen.getByText(/Not enough data/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('80C'), { target: { value: '150000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Compare again' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(self.id, expect.objectContaining({ deductions: { section80C: 150_000 } })))
    expect(await screen.findByText('Cheaper by ₹1,58,080')).toBeInTheDocument()
    expect(screen.getByText('₹3,33,840')).toBeInTheDocument()
  })

  it('names the financial year from April', () => {
    expect(financialYear(new Date(2026, 2, 31))).toBe('2025-26')
    expect(financialYear(new Date(2026, 3, 1))).toBe('2026-27')
  })
})
