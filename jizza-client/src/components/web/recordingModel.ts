import type { EditOp, RecordedStep } from '@/api/web'

/** Fields of a step that hold a typed value a person might want to change on each run. */
const VALUE_FIELDS = ['text', 'url', 'value', 'keys', 'query'] as const

/** The steps still in the recording, in the order they will run. */
export const liveSteps = (steps: RecordedStep[] | undefined): RecordedStep[] =>
  [...(steps ?? [])].filter(s => !s.deleted).sort((a, b) => a.position - b.position)

export const isPassword = (s: RecordedStep): boolean => s.step.meta?.sensitive === true

/** The values in a step that can become an input. A password never can; it takes a saved login. */
export function mappableFields(s: RecordedStep): { field: string; value: string }[] {
  if (isPassword(s)) return []
  const input = s.step.input ?? {}
  return VALUE_FIELDS.flatMap(field => {
    const v = input[field]
    return typeof v === 'string' || typeof v === 'number' ? [{ field, value: String(v) }] : []
  })
}

/** The name an input gets by default: the last words of the step's label, as a lowercase word. */
export function suggestInputName(s: RecordedStep, field: string): string {
  const quoted = /"([^"]+)"/.exec(s.step.label ?? '')?.[1] ?? field
  const words = quoted.replace(/[^A-Za-z0-9]+/g, ' ').trim().split(' ').filter(Boolean)
  if (words.length === 0) return field
  return words.map((w, i) => (i === 0 ? w.toLowerCase() : w[0].toUpperCase() + w.slice(1).toLowerCase())).join('')
}

/** The edit for dragging or nudging a step to a new place among the live steps. */
export const moveOp = (s: RecordedStep, toIndex: number): EditOp => ({ op: 'move', seq: s.seq, toIndex })

/** Password steps that still have no saved login: the recording can't be saved until each has one. */
export const missingLogins = (steps: RecordedStep[] | undefined): RecordedStep[] =>
  liveSteps(steps).filter(s => isPassword(s) && !s.step.secretRef)
