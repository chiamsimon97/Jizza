/** React Query keys for the Portfolio (spec 5.16). Mutations invalidate by area under ['finance']. */
export const financeKeys = {
  all: ['finance'] as const,
  profile: ['finance', 'profile'] as const,
  people: ['finance', 'people'] as const,
  consents: (personId: string) => ['finance', 'consents', personId] as const,
  config: ['finance', 'config'] as const,
}
