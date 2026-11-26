import { api } from '@/lib/api'
import type {
  ActionStatus, CashFlowView, FinAction, FinAsset, FinAssetInput, FinConfigItem, FinConsent, FinEstate, FinLoan,
  FinLoanGiven, FinLoanGivenInput, FinLoanInput, FinPerson, FinPersonInput, FinPolicy, FinPolicyInput,
  FinProfileView, FinSettings, HealthView, JsonValue, LoanPlanView, NetWorthView, ProtectionView,
  QuickStartAnswers, QuickStartProgress, QuickStartStep, QuickStartView,
  BuilderInput, BuilderView, MailCheckResult, MailSource, MailSourceInput, MailSourcesView, GoalCheck, GoalInput, OrderInput, OrderPlaced, OrderPreview, SourcesView, SyncResult, SyncSource, GoalLibraryCard, GoalView, RiskAssessment, RiskView, TaxInput, TaxView,
  FinAccount, FinAccountInput, FinCategory, FinTransaction, ImportBatch, ImportInput,
  AllocationView, EarmarkInput, HoldingDetail, HoldingsView, Instrument, InstrumentInput, ListItem, ListItemInput,
  ListType, RealisedGains, RuleInput, RulesView, RuleTemplate, SaleResult, TradeInput, TreeNode, TreeView,
} from '@/types/finance'

function query(params: Record<string, string | null | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v) q.set(k, v)
  const s = q.toString()
  return s ? `?${s}` : ''
}

/** Kai's Portfolio (spec 5.15). Every path answers 404 while the server's finance switch is off. */
export const financeApi = {
  status: () => api.get<{ enabled: boolean }>('/api/finance/status'),

  profile: () => api.get<FinProfileView>('/api/finance/profile'),
  updateSettings: (patch: Omit<Partial<FinSettings>, 'setupMarks'> & { setupMarks?: Record<string, 'done' | 'skipped' | ''> }) => api.patch<FinSettings>('/api/finance/profile/settings', patch),

  people: () => api.get<FinPerson[]>('/api/finance/people'),
  addPerson: (body: FinPersonInput) => api.post<FinPerson>('/api/finance/people', body),
  updatePerson: (id: string, body: FinPersonInput) => api.patch<FinPerson>(`/api/finance/people/${id}`, body),
  /** Archives: the records stay and the person leaves the switchers. */
  archivePerson: (id: string) => api.delete<FinPerson>(`/api/finance/people/${id}`),

  consents: (personId: string) => api.get<FinConsent[]>(`/api/finance/people/${personId}/consents`),
  recordConsent: (personId: string, body: { scope: 'view' | 'manage'; channel?: string; evidenceFileId?: string }) =>
    api.post<FinConsent>(`/api/finance/people/${personId}/consents`, body),
  /** Needs a fresh password; the app asks for it and retries. */
  revokeConsent: (id: string) => api.post<FinConsent>(`/api/finance/consents/${id}/revoke`),

  config: () => api.get<FinConfigItem[]>('/api/finance/config'),
  setConfig: (key: string, value: JsonValue) => api.put<FinConfigItem>(`/api/finance/config/${encodeURIComponent(key)}`, { value }),
  resetConfig: (key: string) => api.delete<FinConfigItem>(`/api/finance/config/${encodeURIComponent(key)}`),

  // ── Phase 1a: Money Health ──────────────────────────────────────────────────

  quickStart: () => api.get<QuickStartView>('/api/finance/quick-start'),
  /** Saves one step; answering a step again replaces what it wrote. */
  saveQuickStart: <S extends QuickStartStep>(step: S, answers: QuickStartAnswers[S]) =>
    api.post<{ progress: QuickStartProgress }>('/api/finance/quick-start', { step, answers }),
  skipQuickStart: (step: QuickStartStep) =>
    api.post<{ progress: QuickStartProgress }>('/api/finance/quick-start', { step, skip: true }),

  health: () => api.get<HealthView>('/api/finance/health'),
  runHealth: () => api.post<HealthView>('/api/finance/health/run'),
  actions: () => api.get<FinAction[]>('/api/finance/actions'),
  updateAction: (id: string, body: { status: ActionStatus; snoozedUntil?: string; reason?: string }) =>
    api.patch<FinAction>(`/api/finance/actions/${id}`, body),

  cashFlow: () => api.get<CashFlowView>('/api/finance/cash-flow'),
  netWorth: () => api.get<NetWorthView>('/api/finance/net-worth'),

  assets: () => api.get<FinAsset[]>('/api/finance/assets'),
  addAsset: (body: FinAssetInput) => api.post<FinAsset>('/api/finance/assets', body),
  updateAsset: (id: string, body: FinAssetInput) => api.patch<FinAsset>(`/api/finance/assets/${id}`, body),
  deleteAsset: (id: string) => api.delete<void>(`/api/finance/assets/${id}`),

  loans: () => api.get<FinLoan[]>('/api/finance/loans'),
  addLoan: (body: FinLoanInput) => api.post<FinLoan>('/api/finance/loans', body),
  updateLoan: (id: string, body: FinLoanInput) => api.patch<FinLoan>(`/api/finance/loans/${id}`, body),
  deleteLoan: (id: string) => api.delete<void>(`/api/finance/loans/${id}`),
  loanPlan: (method: 'avalanche' | 'snowball', extra = 0) =>
    api.get<LoanPlanView>(`/api/finance/loans/plan?method=${method}${extra > 0 ? `&extra=${Math.round(extra)}` : ''}`),

  loansGiven: () => api.get<FinLoanGiven[]>('/api/finance/loans-given'),
  addLoanGiven: (body: FinLoanGivenInput) => api.post<FinLoanGiven>('/api/finance/loans-given', body),
  updateLoanGiven: (id: string, body: FinLoanGivenInput) => api.patch<FinLoanGiven>(`/api/finance/loans-given/${id}`, body),
  deleteLoanGiven: (id: string) => api.delete<void>(`/api/finance/loans-given/${id}`),

  policies: () => api.get<FinPolicy[]>('/api/finance/policies'),
  addPolicy: (body: FinPolicyInput) => api.post<FinPolicy>('/api/finance/policies', body),
  updatePolicy: (id: string, body: FinPolicyInput) => api.patch<FinPolicy>(`/api/finance/policies/${id}`, body),
  deletePolicy: (id: string) => api.delete<void>(`/api/finance/policies/${id}`),
  protection: () => api.get<ProtectionView>('/api/finance/protection'),

  estate: (personId: string) => api.get<FinEstate>(`/api/finance/estate/${personId}`),
  saveEstate: (personId: string, body: Partial<FinEstate>) => api.put<FinEstate>(`/api/finance/estate/${personId}`, body),

  // ── Phase 1b: goals, plan builder, risk and tax ─────────────────────────────

  goalLibrary: () => api.get<GoalLibraryCard[]>('/api/finance/goal-library'),
  goals: () => api.get<GoalView[]>('/api/finance/goals'),
  goal: (id: string) => api.get<GoalView>(`/api/finance/goals/${id}`),
  addGoal: (body: GoalInput) => api.post<GoalView>('/api/finance/goals', body),
  updateGoal: (id: string, body: GoalInput) => api.patch<GoalView>(`/api/finance/goals/${id}`, body),
  // ── Connected sources (a broker's MCP server on Connectors) ─────────────────
  sources: () => api.get<SourcesView>('/api/finance/sources'),
  linkSource: (serverId: string, accountId: string) => api.post<SyncSource>('/api/finance/sources', { serverId, accountId }),
  setSourceEnabled: (id: string, enabled: boolean) => api.patch<SyncSource>(`/api/finance/sources/${id}`, { enabled }),
  unlinkSource: (id: string) => api.delete<void>(`/api/finance/sources/${id}`),
  /** Reads the holdings now; a change comes back as a batch to review, never saved on its own. */
  syncSource: (id: string) => api.post<SyncResult>(`/api/finance/sources/${id}/sync`),
  // ── Statements from email (part B) ──────────────────────────────────────────
  mailSources: () => api.get<MailSourcesView>('/api/finance/mail-sources'),
  linkMailSource: (body: MailSourceInput) => api.post<MailSource>('/api/finance/mail-sources', body),
  /** A blank password keeps the saved one; the saved one is never sent back. */
  changeMailSource: (id: string, body: { sender?: string; subject?: string; password?: string; enabled?: boolean }) =>
    api.patch<MailSource>(`/api/finance/mail-sources/${id}`, body),
  unlinkMailSource: (id: string) => api.delete<void>(`/api/finance/mail-sources/${id}`),
  /** Looks for new statement mails now; each one found is a batch to review. */
  checkMailSource: (id: string) => api.post<MailCheckResult>(`/api/finance/mail-sources/${id}/check`),
  // ── Orders the person places (part D) ───────────────────────────────────────
  prepareOrder: (body: OrderInput) => api.post<OrderPreview>('/api/finance/orders/prepare', body),
  /** Asks for the password every time; Kai never places an order on its own. */
  placeOrder: (body: OrderInput & { password: string }) => api.post<OrderPlaced>('/api/finance/orders/place', body),
  achieveGoal: (id: string) => api.post<GoalView>(`/api/finance/goals/${id}/achieve`),
  /** Does each goal meet its target: worked out now, stores nothing. */
  goalChecks: () => api.get<GoalCheck[]>('/api/finance/goal-checks'),
  /** Runs the goal checks and keeps the result, as the nightly job does. */
  runGoalChecks: () => api.post<GoalCheck[]>('/api/finance/goal-checks/run'),

  builder: () => api.get<BuilderView>('/api/finance/builder'),
  /** Keeps the inputs as a draft without writing the plan lines. */
  putBuilder: (body: BuilderInput) => api.put<BuilderView>('/api/finance/builder', body),
  previewBuilder: (body: BuilderInput) => api.post<BuilderView>('/api/finance/builder/preview', body),
  saveBuilder: (body: BuilderInput) => api.post<BuilderView>('/api/finance/builder/save', body),

  risk: (personId: string) => api.get<RiskView>(`/api/finance/risk/${personId}`),
  assessRisk: (personId: string, answers: number[]) => api.post<RiskAssessment>(`/api/finance/risk/${personId}`, { answers }),

  tax: (personId: string, year?: string) => api.get<TaxView>(`/api/finance/tax/${personId}${year ? `?year=${encodeURIComponent(year)}` : ''}`),
  saveTax: (personId: string, body: TaxInput) => api.put<TaxView>(`/api/finance/tax/${personId}`, body),

  // ── Phase 1c: accounts, imports and transactions ────────────────────────────

  accounts: () => api.get<FinAccount[]>('/api/finance/accounts'),
  addAccount: (body: FinAccountInput) => api.post<FinAccount>('/api/finance/accounts', body),
  categories: () => api.get<FinCategory[]>('/api/finance/categories'),

  imports: () => api.get<ImportBatch[]>('/api/finance/imports'),
  importBatch: (id: string) => api.get<ImportBatch>(`/api/finance/imports/${id}`),
  /** Reads the file into a batch to review; nothing is saved until commit. */
  upload: (input: ImportInput) => {
    const form = new FormData()
    form.append('file', input.file)
    form.append('kind', input.kind)
    if (input.personId) form.append('personId', input.personId)
    if (input.accountId) form.append('accountId', input.accountId)
    if (input.mapping) form.append('mapping', JSON.stringify(input.mapping))
    if (input.password) form.append('password', input.password)
    return api.upload<ImportBatch>('/api/finance/imports', form)
  },
  /** {@code categories} maps a row number to a category; rows in {@code always} become rules. */
  commitImport: (id: string, body: { categories?: Record<string, string>; always?: number[] } = {}) =>
    api.post<ImportBatch>(`/api/finance/imports/${id}/commit`, body),
  /** Undoes a saved import, or discards one not saved yet. */
  undoImport: (id: string) => api.post<ImportBatch | { id: string; status: 'discarded' }>(`/api/finance/imports/${id}/undo`),

  transactions: (accountId?: string, month?: string) => {
    const q = new URLSearchParams()
    if (accountId) q.set('account', accountId)
    if (month) q.set('month', month)
    const s = q.toString()
    return api.get<FinTransaction[]>(`/api/finance/transactions${s ? `?${s}` : ''}`)
  },
  recategorise: (id: string, categoryId: string, always = false) =>
    api.patch<{ transaction: FinTransaction; alsoChanged: number }>(`/api/finance/transactions/${id}`, { categoryId, always }),

  // ── Phase 2: holdings, earmarks, asset tree, rules, lists ────────────────────

  searchInstruments: (q: string) => api.get<Instrument[]>(`/api/finance/instruments${query({ q })}`),
  addInstrument: (body: InstrumentInput) => api.post<Instrument>('/api/finance/instruments', body),
  /** Refetches today's fund NAVs; refused within 15 minutes of the last refresh. */
  refreshPrices: () => api.post<{ fetched: number; matched: number; saved: number; navDate: string | null }>('/api/finance/prices/refresh'),
  /** A price you type in; it values only your own holdings. */
  putPrice: (body: { instrumentId: string; date?: string; price: number }) => api.post('/api/finance/prices', body),

  holdings: (person?: string | null, goal?: string | null) => api.get<HoldingsView>(`/api/finance/holdings${query({ person, goal })}`),
  holding: (accountId: string, instrumentId: string) => api.get<HoldingDetail>(`/api/finance/holdings/${accountId}/${instrumentId}`),
  /** Splits units (or an asset's amount) between goals; 409 "finance-short-goal-shares" asks first. */
  putEarmarks: (body: EarmarkInput) => api.put<HoldingDetail>('/api/finance/earmarks', body),
  recordPurchase: (body: TradeInput) => api.post<HoldingDetail>('/api/finance/holdings/purchases', body),
  recordSale: (body: TradeInput) => api.post<SaleResult>('/api/finance/holdings/sales', body),
  realisedGains: (taxYear?: string) => api.get<RealisedGains>(`/api/finance/realized-gains${query({ taxYear })}`),

  tree: (goalId: string) => api.get<TreeView>(`/api/finance/tree?goal=${goalId}`),
  saveTree: (goalId: string, nodes: TreeNode[], keepShares = false) =>
    api.put<TreeView>(`/api/finance/tree/${goalId}`, { nodes, keepShares }),
  allocation: (goalId?: string | null) => api.get<AllocationView>(`/api/finance/allocation${query({ goal: goalId })}`),

  rules: (goalId?: string | null, instrumentId?: string | null, nodeId?: string | null) =>
    api.get<RulesView>(`/api/finance/rules/effective${query({ goal: goalId, instrument: instrumentId, node: nodeId })}`),
  previewRule: (body: RuleInput) => api.post<{ affectedEarmarks: number }>('/api/finance/rules/preview', body),
  saveRule: (body: RuleInput) => api.put<{ rule: unknown; affectedEarmarks: number }>('/api/finance/rules', body),
  ruleTemplates: () => api.get<RuleTemplate[]>('/api/finance/rule-templates'),

  lists: (type?: ListType) => api.get<ListItem[]>(`/api/finance/lists${query({ type })}`),
  addListItem: (body: ListItemInput) => api.post<ListItem>('/api/finance/lists', body),
  updateListItem: (id: string, body: ListItemInput) => api.patch<ListItem>(`/api/finance/lists/${id}`, body),
  deleteListItem: (id: string) => api.delete<void>(`/api/finance/lists/${id}`),
  /** Watch → Next; Next → a holding, with the purchase you made. */
  promoteListItem: (id: string, purchase?: Omit<TradeInput, 'instrumentId'>) =>
    api.post<{ to: 'next' | 'holding' }>(`/api/finance/lists/${id}/promote`, purchase ?? {}),
}
