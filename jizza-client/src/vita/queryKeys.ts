/**
 * React Query keys for the Health tab (spec 5.14): ['vita', personId, area, …]. Household-wide
 * reads (status, people, config) sit under ['vita', '_', …]; every mutation invalidates by person
 * or, when it changes more than one person's view, everything under ['vita'].
 */
export const vitaKeys = {
  all: ['vita'] as const,
  status: ['vita', '_', 'status'] as const,
  people: ['vita', '_', 'people'] as const,
  config: ['vita', '_', 'config'] as const,
  person: (personId: string) => ['vita', personId] as const,
  quickStart: (personId: string) => ['vita', personId, 'quick-start'] as const,
  profile: (personId: string) => ['vita', personId, 'profile'] as const,
  card: (personId: string) => ['vita', personId, 'emergency-card'] as const,
  contacts: (personId: string) => ['vita', personId, 'emergency-contacts'] as const,
  conditions: (personId: string) => ['vita', personId, 'conditions'] as const,
  allergies: (personId: string) => ['vita', personId, 'allergies'] as const,
  measurements: (personId: string, kind: string) => ['vita', personId, 'measurements', kind] as const,
}
