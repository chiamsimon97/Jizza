/** Kai Portfolio DTOs, as /api/finance returns them (spec 5.15). */

export type PersonKind = 'self' | 'spouse' | 'child' | 'parent' | 'huf' | 'other'
export type PersonType = 'individual' | 'minor' | 'huf'
export type PersonStatus = 'active' | 'archived' | 'deceased' | 'handed_over'

export interface FinPerson {
  id: string
  userId: string
  displayName: string
  kind: PersonKind
  type: PersonType
  dob: string | null
  earning: boolean
  dependent: boolean
  status: PersonStatus
  sortOrder: number
  handedOverToUserId: string | null
}

/** What the add and edit form sends. */
export interface FinPersonInput {
  displayName?: string
  kind?: PersonKind
  type?: PersonType
  dob?: string | null
  isEarning?: boolean
  isDependent?: boolean
}

export interface FinSettings {
  advanced: boolean
  hideAmounts: boolean
  /** Alert kinds Kai does not send (policy_renewal, loan_maturity, gold_ltv, …). */
  mutedAlerts?: string[]
  /** Setup guide steps marked done or skipped by hand. */
  setupMarks?: Record<string, 'done' | 'skipped'>
  /** The setup guide card is put away on Home. */
  setupGuideHidden?: boolean
}

export interface FinProfileView {
  profile: { id: string; baseCurrency: string; version: number }
  people: FinPerson[]
  settings: FinSettings
  setupProgress: { quickStart: 'not_started' | 'in_progress' | 'done'; peopleManaged: number }
}

export interface FinConsent {
  id: string
  personId: string
  scope: 'view' | 'manage'
  grantedAt: string
  withdrawnAt: string | null
  channel: string | null
  evidenceFileId: string | null
  purgeAfter: string | null
}

export type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue }

export interface FinConfigItem {
  key: string
  area: string
  value: JsonValue
  defaultValue: JsonValue
  source: 'kai_default' | 'you'
  isOverride: boolean
  effectiveFrom: string
  sourceUrl: string | null
  verifiedOn: string | null
  needsVerification: boolean
}

// ── Phase 1a: Money Health ────────────────────────────────────────────────────

export type QuickStartStep = 'people' | 'income' | 'spending' | 'loans' | 'insurance' | 'savings' | 'goals' | 'risk'

export interface QuickStartProgress {
  completed: QuickStartStep[]
  skipped: QuickStartStep[]
  next: QuickStartStep | null
  done: boolean
  total: number
}

/** What each step last saved, so answering again starts from it. */
export interface QuickStartAnswers {
  people?: { me?: { dob?: string | null; isEarning?: boolean }; people?: QuickStartPerson[] }
  income?: { incomes: { personId: string; takeHomeMonthly: number; grossMonthly?: number | null }[] }
  spending?: { essentialMonthly?: number | null; otherMonthly?: number | null; investingMonthly?: number | null }
  loans?: { loans: QuickStartLoan[] }
  insurance?: { policies: QuickStartPolicy[] }
  savings?: { totals: QuickStartSaving[] }
  goals?: { goals: QuickStartGoal[] }
  risk?: { answers: number[]; personId?: string }
}

export interface QuickStartGoal {
  type: GoalType
  name?: string
  targetAmountToday?: number | null
  targetDate?: string | null
  ownerPersonId?: string | null
}

export interface QuickStartPerson {
  id?: string
  displayName: string
  kind: PersonKind
  type: PersonType
  dob?: string | null
  isEarning?: boolean
  isDependent?: boolean
}

export interface QuickStartLoan {
  kind: LoanKind
  outstanding: number
  monthlyPayment?: number | null
  rateInput?: string
  lenderName?: string
  dueDate?: string | null
  grams?: number | null
  purityKarat?: number | null
  goldValue?: number | null
}

export interface QuickStartPolicy {
  kind: PolicyKind
  sumAssured: number
  premium?: number | null
  employerProvided?: boolean
  personId?: string
  coveredPersonIds?: string[]
}

export interface QuickStartSaving {
  kind: AssetKind
  value: number
  isEmergency?: boolean
  name?: string
  assetClass?: AssetClass
}

export interface QuickStartView {
  answers: QuickStartAnswers
  progress: QuickStartProgress
}

export type CheckKey = 'emergencyFund' | 'debt' | 'savingsRate' | 'goalFunding' | 'protection' | 'spendingControl'
export type CheckStatus = 'green' | 'amber' | 'red' | 'unknown'

export interface HealthCheck {
  key: CheckKey
  status: CheckStatus
  value: number | null
  /** The value as the tile shows it, rounded. */
  shown: number | null
  unit: 'months' | 'pctOfGrossIncome' | 'pctOfTakeHome' | 'pctFunded' | 'gaps' | 'needsPctOfTakeHome'
  target: number
  weight: number
  points: number
  facts: Record<string, JsonValue>
}

export type ActionLayer = 'survival' | 'important' | 'aspirational' | 'hygiene'
export type ActionStatus = 'open' | 'later' | 'snoozed' | 'done' | 'dismissed'

export interface FinAction {
  id: string
  personId: string | null
  actionKey: string
  layer: ActionLayer
  title: string
  reason: string
  impactPoints: number | null
  rank: number | null
  details: Record<string, JsonValue> | null
  status: ActionStatus
  snoozedUntil: string | null
  dismissReason: string | null
  sourceCheck: string | null
  dueDate: string | null
  createdAt: string
  updatedAt: string
}

export interface HealthView {
  asOf: string
  /** Out of 100; null until at least one check has data. */
  score: number | null
  rawScore: number | null
  checks: HealthCheck[]
  trend: { at: string; score: number }[]
  flags: { nomineeMissing: boolean; willMissing: boolean; needsVerification: boolean }
  surplus: number
  essentialMonthly: number
  topActions: FinAction[]
}

export interface CashFlowView {
  asOf: string
  month: string
  income: { personId: string; name: string; grossMonthly: number | null; takeHomeMonthly: number }[]
  spendByBucket: { need: number; want: number; save: number }
  takeHome: number
  essential: number
  loanPayments: number
  premiumsMonthly: number
  surplus: number
  surplusUse: { key: string; label: string; amount: number }[]
  needsWantsSavePct: { need: number; want: number; save: number }
}

export interface NetWorthSlice { key: string; assets: number; liabilities: number; netWorth: number }

export interface NetWorthView {
  asOf: string
  assets: number
  liabilities: number
  netWorth: number
  pledged: number
  byClass: NetWorthSlice[]
  byPerson: NetWorthSlice[]
  trend: { asOf: string; assets: number; liabilities: number; netWorth: number }[]
}

export type AssetKind = 'savings_balance' | 'fd' | 'rd' | 'epf' | 'ppf' | 'nps' | 'ssy' | 'post_office'
  | 'gold_physical' | 'gold_digital' | 'property' | 'policy_surrender' | 'chit' | 'cash' | 'vehicle' | 'other'
export type AssetClass = 'cash' | 'debt' | 'equity' | 'gold' | 'other'

export interface FinAsset {
  id: string
  personId: string | null
  accountId: string | null
  kind: AssetKind
  name: string | null
  assetClass: AssetClass | null
  subCategory: string | null
  value: number
  valueAsOf: string | null
  rate: number | null
  maturityDate: string | null
  grams: number | null
  purityKarat: number | null
  isEmergency: boolean
  isEstimate: boolean
  pledged: boolean
}

export type FinAssetInput = Partial<Omit<FinAsset, 'id' | 'pledged'>>

export type LoanKind = 'HOME' | 'VEHICLE' | 'EDUCATION' | 'PERSONAL' | 'CREDIT_CARD' | 'CONSUMER_EMI'
  | 'GOLD_LOAN' | 'LOAN_AGAINST_SECURITIES' | 'LOAN_AGAINST_FD' | 'LOAN_AGAINST_INSURANCE' | 'PPF_LOAN'
  | 'OVERDRAFT' | 'BUSINESS' | 'EMPLOYER_ADVANCE' | 'FAMILY_FRIEND' | 'MONEYLENDER' | 'CHIT_PRIZE' | 'OTHER'

export interface FinPledge {
  id?: string
  assetKind: 'gold' | 'silver' | 'securities' | 'fd' | 'policy' | 'property'
  assetId?: string | null
  grams?: number | null
  purityKarat?: number | null
  units?: number | null
  valuation?: number | null
  valuedOn?: string | null
}

export interface GoldLoanDetail {
  loanId: string
  principal: number
  termDays: number
  maturityAmount: number
  maturityInterest: number
  accruedDays: number
  accruedInterest: number
  pledgedGrams: number
  valuation: number
  ltvPct: number
  capPct: number
  warn: boolean
  maturityWithinWarning: boolean
  ltvBasis: string
}

export interface FinLoan {
  id: string
  personId: string | null
  kind: LoanKind
  lenderType: string | null
  lenderName: string | null
  isPrivate: boolean
  principalTaken: number | null
  outstanding: number
  /** What the user typed: "14%", "₹2 per ₹100 a month". */
  rateInput: string | null
  effectiveAnnualRate: number | null
  rateType: string | null
  repaymentMode: 'emi' | 'interest_only' | 'bullet' | 'revolving' | 'flexible' | null
  emiOrInterest: number | null
  startDate: string | null
  maturityOrPromisedDate: string | null
  status: 'active' | 'closed'
  pledges: FinPledge[]
  label: string
  /** What anyone the loan is shared with sees: "Family / friend" for a private lender. */
  sharedLabel: string
  ltv: { pct: number; capPct: number; warn: boolean; needsVerification: boolean; detail: GoldLoanDetail } | null
}

export interface FinLoanInput {
  kind?: LoanKind
  lenderName?: string | null
  isPrivate?: boolean
  outstanding?: number
  rateInput?: string | null
  emiOrInterest?: number | null
  repaymentMode?: FinLoan['repaymentMode']
  principalTaken?: number | null
  startDate?: string | null
  maturityOrPromisedDate?: string | null
  personId?: string
  pledges?: FinPledge[]
}

export interface PlanOrderItem {
  loanId: string
  label: string
  step: 'pay_in_full' | 'date_bound' | 'prepay' | 'split' | 'keep_emi'
  ratePct: number | null
  dueDate: string | null
}

export interface LoanPlanView {
  asOf: string
  monthlySurplus: number
  method: 'avalanche' | 'snowball'
  plan: {
    order: PlanOrderItem[]
    monthly: { month: string; payments: Record<string, number>; investable: number }[]
    loans: { loanId: string; debtFreeBy: string | null; interestPaid: number; monthlySetAside: number | null }[]
    goldLoans: GoldLoanDetail[]
    interestSaved: number
    debtFreeBy: string | null
    dateBoundFirst: boolean
  }
  labels: Record<string, string>
  figuresNeedVerification: boolean
}

export interface FinLoanGiven {
  id: string
  personId: string | null
  borrowerName: string
  isPrivate: boolean
  amount: number
  rate: number | null
  givenOn: string | null
  expectedBackOn: string | null
  status: 'on_track' | 'overdue' | 'doubtful' | 'repaid'
}

export type FinLoanGivenInput = Partial<Omit<FinLoanGiven, 'id'>>

export type PolicyKind = 'term' | 'health' | 'super_topup' | 'personal_accident' | 'critical_illness'
  | 'vehicle' | 'home' | 'endowment' | 'ulip'

export interface FinPolicy {
  id: string
  personId: string | null
  kind: PolicyKind
  providerName: string | null
  sumAssured: number
  deductible: number | null
  premium: number | null
  premiumFrequency: 'monthly' | 'quarterly' | 'yearly' | 'single' | null
  renewalDate: string | null
  policyEndDate: string | null
  employerProvided: boolean
  coveredPersonIds: string[]
}

export type FinPolicyInput = Partial<Omit<FinPolicy, 'id'>>

export interface PersonCover {
  personId: string
  name: string
  needsTerm: boolean
  method: string
  multiple: number | null
  termNeed: number
  termCover: number
  termGap: number
  personalHealthCover: number
  employerHealthCover: number
  superTopUp: number
}

export interface ProtectionView {
  asOf: string
  /** False while the insurance step is skipped and no policy is recorded. */
  known: boolean
  protection: {
    perPerson: PersonCover[]
    gaps: { kind: 'term' | 'health'; personIds: string[]; amount: number }[]
    premiumsYearly: number
    premiumsPctOfIncome: number
    affordabilityFlag: boolean
    healthFloor: number
  }
  names: Record<string, string>
}

export interface FinEstate {
  personId: string
  willStatus: 'none' | 'draft' | 'registered'
  willDate?: string | null
  executor?: string | null
  notes?: string | null
}

// ── Phase 1b: goals, plan builder, risk and tax ────────────────────────────────

export type GoalType = 'emergency' | 'retirement' | 'education' | 'home' | 'car' | 'travel' | 'wedding' | 'wealth' | 'custom'
export type GoalTier = 'survival' | 'important' | 'aspirational'
export type GoalStatus = 'draft' | 'active' | 'paused' | 'achieved' | 'archived'
export type RiskProfile = 'conservative' | 'moderate' | 'aggressive'
export type Bucket = 'short' | 'mid' | 'long'
export type InflationKey = 'cpi' | 'education' | 'car' | 'none'

/** A card of the goal library (7.3 step 1), with Kai's smart defaults where it can work them out. */
export interface GoalLibraryCard {
  type: GoalType
  name: string
  priority: GoalTier
  inflationKey: InflationKey
  inflationPct: number | null
  targetAmountToday: number | null
  targetDate: string | null
  bucket: Bucket | null
  ownerPersonId: string | null
  hint: string
  defaults: Record<string, JsonValue> | null
}

export interface FinGoal {
  id: string
  type: GoalType
  name: string
  ownerPersonId: string | null
  targetDate: string
  targetAmountToday: number
  inflationKey: InflationKey
  priorityTier: GoalTier
  riskProfile: RiskProfile | null
  status: GoalStatus
  currentValue: number
  monthlyContribution: number
  stepupPct: number
  colour: string | null
  createdVia: 'wizard' | 'quick_start' | 'system'
  achievedAt: string | null
  createdAt?: string
}

export interface GoalFunding {
  monthsToTarget: number
  bucket: Bucket
  inflationPct: number
  futureTarget: number
  projected: number
  fundedPct: number
  requiredSip: number
  blendedReturnNow: number
  mixNow: Record<string, number>
  monthsToNextBucket: number | null
  nextBucket: Bucket | null
}

export interface GoalView {
  goal: FinGoal
  ownerName: string
  funding: GoalFunding | null
  risk: RiskProfile
  riskSource: 'goal' | 'self' | 'person' | 'default'
  moveNote: string | null
  expectedReturnPct: number | null
}

export type GoalInput = Partial<Pick<FinGoal, 'type' | 'name' | 'ownerPersonId' | 'targetDate' | 'targetAmountToday' |
  'inflationKey' | 'priorityTier' | 'riskProfile' | 'status' | 'currentValue' | 'monthlyContribution' | 'stepupPct' | 'colour'>>

export interface BuilderEdit { key: string; monthly: number }

export interface BuilderInput {
  monthly: number
  lumpsum?: number | null
  frequency?: string | null
  stepUpPct?: number | null
  topupMonth?: number | null
  topupAmount?: number | null
  age?: number | null
  riskProfile?: RiskProfile | null
  edits?: BuilderEdit[]
}

export interface AllocationNode {
  key: string
  label: string
  level: 'root' | 'bucket' | 'asset_class' | 'sub_category'
  monthly: number
  lumpsum: number
  pct: number
  source: string
  sourceLabel: string
  edited: boolean
  children: AllocationNode[]
}

export interface AllocationLine {
  key: string
  bucket: Bucket
  assetClass: string
  subCategory: string
  label: string
  monthly: number
  lumpsum: number
  pct: number
  source: string
  sourceLabel: string
}

export interface OffTheTop { key: string; label: string; monthly: number; lumpsum: number; reason: string }
export interface GoalShare { goalId: string | null; name: string; bucket: Bucket; monthly: number; required: number }

export interface AllocationResult {
  monthlyIn: number
  lumpsumIn: number
  offTheTop: OffTheTop[]
  monthlyInvested: number
  lumpsumInvested: number
  age: number
  risk: RiskProfile
  tree: AllocationNode
  lines: AllocationLine[]
  goalLens: GoalShare[]
  timeline: {
    today: { key: string; label: string; amount: number }[]
    investedToday: number
    parked: number
    stpMonths: number
    stp: { month: number; amount: number }[]
  }
  notes: string[]
}

export interface BuilderView {
  input: BuilderInput | null
  age: number
  ageSource: string
  risk: RiskProfile
  riskSource: string
  result: AllocationResult | null
  savedAt: string | null
  savedLines: Record<string, JsonValue>[]
}

export interface RiskQuestion { key: string; text: string; answers: string[] }
export interface RiskFactScore { key: string; label: string; score: number; assumed: boolean; reading: string }

export interface RiskAssessment {
  id: string
  personId: string
  questionnaireVersion: number
  toleranceScore: number
  toleranceProfile: RiskProfile
  capacityScore: number
  capacityProfile: RiskProfile
  profile: RiskProfile
  conflictFlag: boolean
  answers: { tolerance: number[] }
  facts: { scores: RiskFactScore[]; decidedBy: string; explanation: string }
  createdAt?: string
}

export interface RiskView {
  personId: string
  questions: RiskQuestion[]
  latest: RiskAssessment | null
  capacityNow: RiskFactScore[]
  note: string
}

export type DeductionKey = 'section80C' | 'section80CCD1B' | 'section80DSelf' | 'section80DParents' | 'homeLoanInterest'

export interface TaxStep { label: string; amount: number }
export interface TaxRegimeResult { taxableIncome: number; slabTax: number; rebate: number; cess: number; total: number; steps: TaxStep[] }

export interface TaxComparison {
  status: string
  newRegime: TaxRegimeResult | null
  oldRegime: TaxRegimeResult | null
  oldStatus: string
  cheaper: 'new' | 'old' | 'same' | null
  gap: number | null
  needsVerification: boolean
  notes: string[]
}

export interface TaxProfile {
  id?: string
  personId: string
  taxYear: string
  regime?: 'new' | 'old' | null
  rentPaidMonthly?: number | null
  metro?: boolean | null
  deductions: Partial<Record<DeductionKey, number>> & { parentsSenior?: boolean }
}

export interface TaxView {
  personId: string
  personName: string
  year: string
  profile: TaxProfile
  grossAnnual: number | null
  comparison: TaxComparison
  unverified: string[]
  gainsNote: string
}

export interface TaxInput {
  year?: string
  regime?: 'new' | 'old' | null
  rentPaidMonthly?: number | null
  metro?: boolean | null
  deductions?: Partial<Record<DeductionKey, number | null>> & { parentsSenior?: boolean }
}

// ── Phase 1c: accounts, imports and transactions ──────────────────────────────

export type AccountKind = 'savings' | 'current' | 'credit_card' | 'demat' | 'mf_folio' | 'retirement' | 'deposit'
  | 'wallet' | 'cash' | 'us_broker' | 'other'

export interface FinAccount {
  id: string
  personId: string
  kind: AccountKind
  provider: string | null
  nickname: string | null
  last4: string | null
  source: 'manual' | 'statement' | 'api'
  isEmergency: boolean
  status: 'active' | 'closed'
}

export type FinAccountInput = Partial<Pick<FinAccount, 'personId' | 'kind' | 'provider' | 'nickname' | 'last4' | 'isEmergency'>>

export interface FinCategory {
  id: string
  name: string
  bucket: 'need' | 'want' | 'save' | 'income' | 'transfer'
  isEssential: boolean
  systemKey: string | null
  hidden: boolean
}

export type ImportKind = 'bank_csv' | 'bank_pdf' | 'cas_pdf' | 'broker_holdings' | 'broker_tradebook'
export type ImportStatus = 'parsed' | 'needs_review' | 'committed' | 'failed' | 'undone'
export type TxnKind = 'spend' | 'income' | 'transfer' | 'buy' | 'sell' | 'dividend' | 'interest' | 'fee' | 'emi'
  | 'sip' | 'swp' | 'stp_in' | 'stp_out'

/** One row of a file as Kai read it, before it is saved. */
export interface ImportRow {
  i: number
  line: number
  date: string
  amount: number
  direction: 'in' | 'out'
  description: string
  kind: TxnKind
  units?: number
  price?: number
  group?: string
  categoryId?: string
  /** Kai can't place it: no category (bank) or kind (funds). */
  needsLook: boolean
  /** Already brought in by an earlier import. */
  duplicate: boolean
}

export interface ImportHolding {
  name: string
  isin: string | null
  assetClass: AssetClass
  subCategory: string
  units: number
  value: number
  asOf: string | null
  group: string | null
}

/** The column roles of a bank CSV, each naming a header of the file. */
export interface ColumnMapping {
  headerRow: number
  date?: string
  description?: string
  debit?: string
  credit?: string
  amount?: string
  drCr?: string
  balance?: string
}

export interface ImportCounts {
  rows: number
  new: number
  duplicates: number
  needsLook: number
  unreadable: number
  holdings: number
  imported?: number
}

export interface ImportStats {
  fileName?: string
  counts?: ImportCounts
  rows?: ImportRow[]
  unreadable?: { line: number; reason: string }[]
  holdings?: ImportHolding[]
  groups?: { key: string; last4: string; provider: string | null; accountId: string | null }[]
  balance?: { amount: number; date: string }
  columns?: { headerRow: number; sure: boolean; headers: string[]; sample: string[][] }
}

export interface ImportBatch {
  id: string
  personId: string
  accountId: string | null
  kind: ImportKind
  status: ImportStatus
  mapping: Partial<ColumnMapping>
  stats: ImportStats
  error: string | null
  createdAt: string
}

export interface ImportInput {
  kind: ImportKind
  file: File
  personId?: string
  accountId?: string
  mapping?: ColumnMapping
  /** For a locked PDF; sent once and never kept. */
  password?: string
}

export interface FinTransaction {
  id: string
  accountId: string
  personId: string
  importBatchId: string | null
  txnDate: string
  amount: number
  direction: 'in' | 'out'
  description: string | null
  kind: TxnKind
  categoryId: string | null
  units: number | null
  price: number | null
  needsMapping: boolean
}

// ── Phase 2: holdings, earmarks, asset tree, rules, lists ─────────────────────

export type InstrumentType = 'stock' | 'etf' | 'mf' | 'bond' | 'sgb' | 'reit' | 'us_stock' | 'us_etf' | 'other'

export interface Instrument {
  id: string
  name: string
  type: InstrumentType
  assetClass: AssetClass
  subCategory: string | null
  capBucket: 'large' | 'mid' | 'small' | null
  isin: string | null
  symbol: string | null
  exchange: string | null
  /** Whether this user added it (and so may correct it). */
  yours?: boolean
}

export interface InstrumentInput {
  name: string
  type?: InstrumentType
  assetClass?: AssetClass
  isin?: string
  symbol?: string
  exchange?: string
}

export interface GoalSplit {
  goalId: string
  name: string
  unassigned: boolean
  units?: number
  amount?: number
  value?: number | null
  colour?: string
  shortGoal?: boolean
  yourChoice?: boolean
}

export interface HoldingAccount {
  accountId: string
  label: string
  personId: string
  personName: string | null
  units: number
  value: number | null
}

export interface HoldingRow {
  instrumentId: string
  name: string
  type: InstrumentType
  assetClass: AssetClass
  subCategory: string | null
  symbol: string | null
  isin: string | null
  units: number
  value: number | null
  avgCost?: number
  cost?: number
  pnl?: number
  pnlPct?: number
  price?: number
  priceDate?: string
  priceSource?: string
  valueSource: 'price' | 'cost' | 'none'
  holdingDays?: number
  pctOfTotal?: number
  accounts: HoldingAccount[]
  split: GoalSplit[]
  unassignedUnits: number
  goalUnits?: number
  goalValue?: number
  needsLook: string[]
}

export interface AssetHoldingRow {
  assetId: string
  name: string
  kind: string
  assetClass: AssetClass
  value: number
  valueAsOf?: string
  personId: string
  personName: string | null
  split: GoalSplit[]
  unassignedAmount: number
}

export interface HoldingsGoal {
  id: string
  name: string
  type: string
  unassigned: boolean
  value: number
  colour?: string
  ownerPersonId?: string
}

export interface HoldingsView {
  asOf: string
  priceAsOf?: string
  stalePrices?: boolean
  value: number
  cost: number | null
  pnl?: number
  rows: HoldingRow[]
  assets: AssetHoldingRow[]
  needsLook: { instrumentId: string; name: string; reason: string }[]
  goals: HoldingsGoal[]
}

export interface HoldingLot {
  id: string
  acquiredOn: string | null
  dateKnown: boolean
  qty: number
  remaining: number
  costPerUnit: number | null
  termToday: 'st' | 'lt' | null
  gainToday?: number
}

export interface HoldingDetail {
  accountId: string
  accountLabel: string
  personId: string
  instrumentId: string
  name: string
  type: InstrumentType
  assetClass: AssetClass
  subCategory: string | null
  symbol: string | null
  isin: string | null
  units: number
  value: number | null
  valueSource: 'price' | 'cost' | 'none'
  avgCost?: number
  price?: number
  priceDate?: string
  lots: HoldingLot[]
  saleNote?: string
  earmarks: GoalSplit[]
  goals: { id: string; name: string; unassigned: boolean; short?: boolean; yourChoice?: boolean; colour?: string }[]
  directShares: boolean
  history: { kind: string; goal: string; deltaUnits: number; at: string; note: string | null }[]
  needsLook: string[]
}

export interface EarmarkInput {
  accountId?: string
  instrumentId?: string
  assetId?: string
  split: { goalId: string; units?: number; amount?: number }[]
  keepShares?: boolean
}

export interface TradeInput {
  accountId: string
  instrumentId: string
  date: string
  units: number
  pricePerUnit?: number
  proceeds?: number
  goalId?: string | null
  keepShares?: boolean
}

export interface SaleResult {
  parts: { lotId: string; acquiredOn: string | null; qty: number; proceeds: number; cost: number | null; gain: number | null; term: 'st' | 'lt' | null }[]
  gain: number | null
  proceeds: number
  holding: HoldingDetail
}

export interface RealisedGains {
  taxYear: string
  shortTerm: number
  longTerm: number
  rows: { id: string; instrumentId: string; name: string; soldOn: string; qty: number; proceeds: number; cost: number | null; gain: number | null; term: 'st' | 'lt' | null; taxClass: string; personId: string; goal: string | null }[]
}

export type TreeNodeKind = 'asset_class' | 'sub_category' | 'instrument'

export interface TreeNode {
  id: string
  parentId: string | null
  kind: TreeNodeKind
  key?: string
  name: string
  targetPct: number | null
  goalPct?: number
  sortOrder?: number
  source?: string
  inRebalancePool?: boolean
  instrumentId?: string
  instrumentName?: string
  userChoiceAckAt?: string
  userChoiceNote?: string
}

export interface TreeView {
  goalId: string
  goalName: string
  goalType: string
  bucket: 'short' | 'mid' | 'long' | string
  saved: boolean
  neverRebalanced: boolean
  nodes: TreeNode[]
  problems: string[]
}

export type DriftStatus = 'inside' | 'above' | 'below' | 'no_target'

export interface AllocationRow {
  id: string
  parentId: string | null
  parentName: string | null
  kind: TreeNodeKind
  key: string
  name: string
  depth: number
  actualPct: number
  value: number
  status: DriftStatus
  targetPct?: number
  bandLow?: number
  bandHigh?: number
  bandMode?: string
  driftPts?: number
  yourChoice?: boolean
}

export interface AllocationView {
  goalId: string | null
  goalName: string
  total: number
  priceAsOf: string | null
  treeSaved: boolean
  rows: AllocationRow[]
  offTree: { instrumentId?: string; assetId?: string; name: string; value: number }[]
  outsideBand: number
  shortGoalShares: { instrumentId: string; name: string; units: number; confirmed: boolean }[]
}

export type RuleField = 'stop_loss' | 'profit_booking' | 'rebalance_band' | 'execution_mode' | 'concentration_cap' | 'accumulate' | 'exit'
export type RuleState = 'inherit' | 'set' | 'off'
export type RuleLevel = 'instrument' | 'sub_category' | 'asset_class' | 'goal' | 'user' | 'kai'

export interface RuleEffective {
  field: RuleField
  state: 'set' | 'off'
  value: Record<string, JsonValue> | null
  sourceLevel: RuleLevel
  sourceId: string | null
  sourceName: string
  locked: boolean
  lockedBy: string | null
}

export interface RuleCell {
  level: RuleLevel
  levelId: string | null
  state: RuleState
  locked: boolean
  params?: Record<string, JsonValue>
}

export interface RulesView {
  goalId: string | null
  goalName: string | null
  instrumentId: string | null
  instrumentName: string | null
  nodeId: string | null
  chain: { level: RuleLevel; id: string | null; name: string }[]
  fields: { field: RuleField; label: string; cells: RuleCell[]; effective: RuleEffective | null }[]
  hardConstraints: RuleEffective[]
  plans: { id: string; name: string }[]
}

export interface RuleInput {
  level: Exclude<RuleLevel, 'kai'>
  goalId?: string | null
  nodeId?: string | null
  instrumentId?: string | null
  field: RuleField
  state: RuleState
  locked?: boolean
  params?: Record<string, JsonValue>
}

export interface RuleTemplate {
  id: string
  name: string
  rules: { field: RuleField; state: RuleState; locked: boolean; params: Record<string, JsonValue> }[]
}

export type ListType = 'watch' | 'next'

export interface ListItem {
  id: string
  listType: ListType
  instrumentId: string
  name?: string
  type?: InstrumentType
  symbol?: string | null
  subCategory?: string | null
  personId?: string | null
  planId?: string | null
  goalName?: string
  nodeId?: string | null
  nodeName?: string
  notes?: string | null
  priority?: number
  targetEntry?: number | null
  plannedAmount?: number | null
  conviction?: number | null
  price?: number
  priceDate?: string
  changePct?: number
}

export type ListItemInput = Partial<Pick<ListItem, 'listType' | 'instrumentId' | 'personId' | 'planId' | 'nodeId' | 'notes' | 'priority' | 'targetEntry' | 'plannedAmount' | 'conviction'>>
