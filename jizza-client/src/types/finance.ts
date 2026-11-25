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
