import type { VitaPerson } from '@/types/vita'

/** People whose record is open, "You" first; closed records go under "Past records". */
export function splitPeople(people: VitaPerson[]) {
  const ordered = [...people].filter(p => p.kind !== 'huf').sort((a, b) => (a.kind === 'self' ? -1 : b.kind === 'self' ? 1 : (a.sortOrder ?? 0) - (b.sortOrder ?? 0)))
  return { open: ordered.filter(p => p.status === 'active'), past: ordered.filter(p => p.status !== 'active') }
}
