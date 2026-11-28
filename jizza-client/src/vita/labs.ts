import type { CheckKind, ConditionCheck, LabResult } from '@/types/vita'

/** The groups a report's tests are shown in (7.8), in this order; anything else falls under Other. */
export const LAB_GROUPS = ['Diabetes', 'Kidney', 'Lipids', 'Liver', 'Thyroid', 'Blood count', 'Vitamins', 'Other'] as const

/** The group of a result: the known test's group, else "Other". Case-insensitive on the server's word. */
export function groupOfResult(r: Pick<LabResult, 'test'>): string {
  const g = r.test?.group?.trim()
  if (!g) return 'Other'
  const match = LAB_GROUPS.find(x => x.toLowerCase() === g.toLowerCase())
  return match ?? g.charAt(0).toUpperCase() + g.slice(1)
}

/** Results grouped in the order of LAB_GROUPS, then any other group by name, keeping the report's order inside each. */
export function groupResults<T extends Pick<LabResult, 'test' | 'sortOrder'>>(rows: T[]): [string, T[]][] {
  const map = new Map<string, T[]>()
  for (const r of [...rows].sort((a, b) => a.sortOrder - b.sortOrder)) {
    const g = groupOfResult(r)
    map.set(g, [...(map.get(g) ?? []), r])
  }
  const rank = (g: string) => {
    const i = (LAB_GROUPS as readonly string[]).indexOf(g)
    return i < 0 ? LAB_GROUPS.length - 1.5 : i
  }
  return [...map.entries()].sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))
}

/** A result's name: the known test's name, else the name as printed. */
export function resultName(r: Pick<LabResult, 'test' | 'testNameAsPrinted'>): string {
  return r.test?.name ?? r.testNameAsPrinted ?? 'Unnamed test'
}

export const CHECK_KINDS: Record<CheckKind, string> = {
  eye: 'Eye check (retina)', foot: 'Foot check', kidney: 'Kidney function', dental: 'Dental check',
  lipids: 'Lipid profile', hba1c: 'HbA1c', custom: 'Other check',
}

/** Words for a condition check's state; the server's own label wins where it sends one. */
export function checkStatusWords(c: ConditionCheck): string {
  if (c.status === 'due') return 'Due'
  if (c.status === 'upcoming') return 'Coming up'
  return c.statusLabel ?? (c.status === 'no_record' ? 'No record yet' : 'Not set: ask your doctor')
}

/** "+0.6", "−1.1" or "no change", in the test's decimals as the server rounded them. */
export function changeWords(c: { shown: string; direction: 'up' | 'down' | 'same' } | undefined | null): string | null {
  if (!c) return null
  if (c.direction === 'same') return 'no change'
  const n = c.shown.replace(/^[-+]/, '')
  return `${c.direction === 'up' ? '+' : '−'}${n}`
}
