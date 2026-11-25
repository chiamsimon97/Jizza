/**
 * React Query keys for the Portfolio (spec 5.16): ['finance', area, …]. Mutations invalidate by
 * area under ['finance']. Sample mode keeps its own keys, so sample figures never mix with real ones.
 */
export const financeKeys = {
  all: ['finance'] as const,
  profile: ['finance', 'profile'] as const,
  people: ['finance', 'people'] as const,
  consents: (personId: string) => ['finance', 'consents', personId] as const,
  config: ['finance', 'config'] as const,
  quickStart: ['finance', 'quick-start'] as const,
  health: (sample = false) => ['finance', 'health', sample ? 'sample' : 'mine'] as const,
  actions: (sample = false) => ['finance', 'actions', sample ? 'sample' : 'mine'] as const,
  cashFlow: (sample = false) => ['finance', 'cash-flow', sample ? 'sample' : 'mine'] as const,
  netWorth: (sample = false) => ['finance', 'net-worth', sample ? 'sample' : 'mine'] as const,
  assets: (sample = false) => ['finance', 'assets', sample ? 'sample' : 'mine'] as const,
  loans: (sample = false) => ['finance', 'loans', sample ? 'sample' : 'mine'] as const,
  loanPlan: (sample: boolean, method: string, extra: number) => ['finance', 'loans', 'plan', sample ? 'sample' : 'mine', method, extra] as const,
  loansGiven: (sample = false) => ['finance', 'loans-given', sample ? 'sample' : 'mine'] as const,
  policies: (sample = false) => ['finance', 'policies', sample ? 'sample' : 'mine'] as const,
  protection: (sample = false) => ['finance', 'protection', sample ? 'sample' : 'mine'] as const,
  goalLibrary: ['finance', 'goal-library'] as const,
  goals: ['finance', 'goals'] as const,
  goal: (id: string) => ['finance', 'goals', id] as const,
  builder: ['finance', 'builder'] as const,
  risk: (personId: string) => ['finance', 'risk', personId] as const,
  tax: (personId: string, year: string) => ['finance', 'tax', personId, year] as const,
  estate: (sample: boolean, personId: string) => ['finance', 'estate', sample ? 'sample' : 'mine', personId] as const,
}
