/**
 * The Portfolio's views and their labels (spec 7.1), in sub-navigation order. `phase` is the build
 * phase that brings a view; a view from a later phase shows what it will hold until then.
 */
export interface PortfolioView {
  id: string
  label: string
  phase: string
  /** One line for the placeholder of a view that is not built yet. */
  about: string
  /** Shown only with Advanced on. */
  advanced?: boolean
  /** Grouped under Investments. */
  group?: 'investments'
  /** Reached from a screen, not listed in the sub-navigation. */
  hidden?: boolean
  /** What "Ask Kai" starts the question with on this view. */
  ask?: string
}

export const PORTFOLIO_VIEWS: PortfolioView[] = [
  { id: 'home', label: 'Home', phase: '0', about: 'Your Money Health, the next actions and what changed.', ask: 'About my Money Health score: ' },
  { id: 'setup', label: 'Setup guide', phase: '2', hidden: true, about: 'Every step to set Kai up, in order, with what each one needs.', ask: 'Helping me set up my Portfolio: ' },
  { id: 'quick-start', label: 'Quick start', phase: '1a', hidden: true, about: 'Eight questions that give your first Money Health score.', ask: 'Helping me through Quick start: ' },
  { id: 'actions', label: 'Actions', phase: '1a', about: 'The action plan, in the order Kai suggests funding it.', ask: 'About my action plan: ' },
  { id: 'goals', label: 'Goals', phase: '1b', about: 'Each goal with its target, date, funding and required SIP.', ask: 'About my goals: ' },
  { id: 'goal', label: 'Goal', phase: '1b', hidden: true, about: 'One goal: its funding, starting mix and details.', ask: 'About this goal: ' },
  { id: 'goal-new', label: 'New goal', phase: '1b', hidden: true, about: 'Pick a goal and correct what Kai fills in.' },
  { id: 'plan-builder', label: 'Plan builder', phase: '1b', about: 'How much goes in, split across short, mid and long term down to each sleeve.', ask: 'About my plan builder split: ' },
  { id: 'holdings', label: 'Holdings', phase: '2', group: 'investments', about: 'What you hold, in which account, and which goal each unit is for.', ask: 'About my holdings: ' },
  { id: 'allocation', label: 'Allocation', phase: '2', group: 'investments', about: 'Each goal’s mix against its target and rebalance band.', ask: 'About this goal’s allocation: ' },
  { id: 'asset-tree', label: 'Asset tree', phase: '2', group: 'investments', about: 'Goal, asset class, sub-category and instrument targets in one tree.' },
  { id: 'proposals', label: 'Proposals', phase: '4', group: 'investments', about: 'Rebalance proposals with options and their tax effect, for you to approve.' },
  { id: 'lists', label: 'Lists', phase: '2', group: 'investments', about: 'Watchlists and shortlists you can promote into a goal.' },
  { id: 'rules', label: 'Rules', phase: '2', group: 'investments', advanced: true, about: 'Stop-loss, target and drift rules, inherited level by level.' },
  { id: 'cash-flow', label: 'Cash flow', phase: '1a', about: 'Income, spending by need, want and save, and what is left each month.', ask: 'About my cash flow this month: ' },
  { id: 'loans', label: 'Loans', phase: '1a', about: 'Every loan and borrowing, its real rate, and a repayment plan.', ask: 'About my loans and the repayment plan: ' },
  { id: 'protection', label: 'Protection', phase: '1a', about: 'Term and health cover against what each person needs.', ask: 'About my term and health cover: ' },
  { id: 'tax', label: 'Tax', phase: '1b', about: 'Old against new regime, deductions and capital gains for the year.', ask: 'About my tax this year: ' },
  { id: 'net-worth', label: 'Net worth', phase: '1a', about: 'What you own and owe, by class and by person, month by month.', ask: 'About my net worth: ' },
  { id: 'schedules', label: 'Schedules', phase: '3', about: 'SIPs, STPs and SWPs with each instalment matched or missed.' },
  { id: 'people', label: 'People', phase: '0', about: 'People you manage who have no login of their own.' },
  { id: 'sharing', label: 'Sharing', phase: '5', about: 'What you share into a Family, with View or Edit.' },
  { id: 'connect', label: 'Connect', phase: '1c', about: 'Statements and files to import, and connected accounts.' },
  { id: 'settings', label: 'Settings', phase: '0', about: 'Kai’s defaults and your own values.' },
]

export const DEFAULT_VIEW = 'home'

export function findView(id: string | null | undefined): PortfolioView {
  return PORTFOLIO_VIEWS.find(v => v.id === id) ?? PORTFOLIO_VIEWS[0]
}

/** Views built in this release; the rest show a placeholder. */
export const BUILT_VIEWS = new Set(['home', 'setup', 'quick-start', 'actions', 'goals', 'goal', 'goal-new', 'plan-builder', 'cash-flow', 'loans', 'protection',
  'tax', 'net-worth', 'people', 'connect', 'settings', 'holdings', 'allocation', 'asset-tree', 'lists', 'rules'])
