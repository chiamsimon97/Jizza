import type { Meal, Reading, ReadingContext, ReadingKind, TargetStatus } from '@/types/vita'
import type { Units } from './data'

/** The six kinds a person types in (7.7), in chip order. */
export const READING_KINDS: { kind: ReadingKind; label: string; short: string }[] = [
  { kind: 'bp', label: 'Blood pressure', short: 'BP' },
  { kind: 'glucose', label: 'Sugar', short: 'Sugar' },
  { kind: 'weight', label: 'Weight', short: 'Weight' },
  { kind: 'heart_rate', label: 'Heart rate', short: 'Heart rate' },
  { kind: 'spo2', label: 'Oxygen (SpO2)', short: 'SpO2' },
  { kind: 'temperature', label: 'Temperature', short: 'Temperature' },
]

export function kindLabel(kind: string): string {
  return READING_KINDS.find(k => k.kind === kind)?.label ?? kind
}

export function isReadingKind(k: string | null | undefined): k is ReadingKind {
  return READING_KINDS.some(r => r.kind === k)
}

export const CONTEXTS: Record<ReadingContext, string> = {
  fasting: 'Fasting', before_meal: 'Before a meal', after_meal: 'After a meal', random: 'Any time', bedtime: 'Bedtime',
}
export const MEALS: Record<Meal, string> = { breakfast: 'Breakfast', lunch: 'Lunch', dinner: 'Dinner' }

export const STATUS_WORDS: Record<TargetStatus, string> = {
  in_range: 'In range', outside_range: 'Outside range', red_flag: 'Red flag', not_set: 'Target not set',
}

/** °C to °F by definition of the scales: a unit conversion, not a clinical figure. */
export const cToF = (c: number) => c * 9 / 5 + 32

/**
 * A reading in words and the person's units: "132/84 mmHg", "118 mg/dL", "64.5 kg", "98.6 °F".
 * Values arrive in canonical units; only weight and temperature have a choice of unit.
 */
export function showValue(kind: ReadingKind, value: number, value2: number | null | undefined, units: Units): string {
  switch (kind) {
    case 'bp': return `${Math.round(value)}/${value2 == null ? '–' : Math.round(value2)} mmHg`
    case 'glucose': return `${Number(value.toFixed(1))} mg/dL`
    case 'weight': return units.weight === 'lb' ? `${(value / 0.45359237).toFixed(1)} lb` : `${Number(value.toFixed(2))} kg`
    case 'temperature': return units.temperature === 'F' ? `${cToF(value).toFixed(1)} °F` : `${value.toFixed(1)} °C`
    case 'spo2': return `${Math.round(value)}%`
    case 'heart_rate': return `${Math.round(value)} bpm`
  }
}

export function showReading(r: Pick<Reading, 'kind' | 'value' | 'value2'>, units: Units): string {
  return showValue(r.kind, r.value, r.value2, units)
}

/** "Before a meal · breakfast · home" and the pulse with a BP reading. */
export function readingContext(r: Reading): string {
  const parts: string[] = []
  if (r.kind === 'bp' && r.value3 != null) parts.push(`pulse ${Math.round(r.value3)}`)
  if (r.context) parts.push(CONTEXTS[r.context] ?? r.context)
  if (r.meal) parts.push(MEALS[r.meal]?.toLowerCase() ?? r.meal)
  if (r.setting) parts.push(r.setting === 'clinic' ? 'at the clinic' : 'at home')
  return parts.join(' · ')
}

const SOURCES: Record<string, string> = {
  manual: 'Typed in', chat: 'From chat', quick_start: 'Quick start', device: 'Device', import: 'Imported', lab: 'Lab report',
}
export function sourceWords(source: string | null | undefined): string {
  return source ? SOURCES[source] ?? source : ''
}

/** The input unit the form asks in, from Settings; the server converts lb and °F. */
export function inputUnit(kind: ReadingKind, units: Units): string {
  switch (kind) {
    case 'bp': return 'mmHg'
    case 'glucose': return 'mg/dL'
    case 'weight': return units.weight
    case 'temperature': return units.temperature
    case 'spo2': return '%'
    case 'heart_rate': return 'bpm'
  }
}

/** The 7, 30 and 90-day toggle on Vitals. */
export const PERIODS = [7, 30, 90] as const
