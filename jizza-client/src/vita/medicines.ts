import type { DoseStatus, DoseUnit, Medicine, MedicineForm, MedicineSchedule, MedicineSystem, WithFood } from '@/types/vita'

export const FORMS: Record<MedicineForm, string> = {
  tablet: 'Tablet', capsule: 'Capsule', syrup: 'Syrup', injection: 'Injection', inhaler: 'Inhaler', drops: 'Drops', cream: 'Cream', supply: 'Supply (strips, needles…)', other: 'Other',
}
export const SYSTEMS: Record<MedicineSystem, string> = {
  allopathy: 'Allopathy', ayurveda: 'Ayurveda', homeopathy: 'Homeopathy', siddha: 'Siddha', unani: 'Unani', supplement: 'Supplement', other: 'Other',
}
export const DOSE_UNITS: Record<DoseUnit, [string, string]> = {
  tablet: ['tablet', 'tablets'], ml: ['ml', 'ml'], units: ['unit', 'units'], puffs: ['puff', 'puffs'], drops: ['drop', 'drops'], other: ['dose', 'doses'],
}
export const WITH_FOOD: Record<WithFood, string> = { before: 'before food', with: 'with food', after: 'after food', any: 'with or without food' }
export const DOSE_STATUS: Record<DoseStatus, string> = {
  due: 'Due', taken: 'Taken', skipped: 'Skipped', missed: 'Missed', partial: 'Partly taken', snoozed: 'Snoozed',
}

const DAYS: Record<string, string> = { MO: 'Mon', TU: 'Tue', WE: 'Wed', TH: 'Thu', FR: 'Fri', SA: 'Sat', SU: 'Sun' }

export function andList(items: string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

/** "8:00 AM" from "08:00": a time of day, not a moment, so no time zone. */
export function clock(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number)
  if (Number.isNaN(h) || Number.isNaN(m)) return hhmm
  const ap = h < 12 ? 'AM' : 'PM'
  return `${h % 12 === 0 ? 12 : h % 12}:${String(m).padStart(2, '0')} ${ap}`
}

/** "Every day" or "Mon and Thu" from the schedule's rule; anything else is shown as written. */
export function recurrenceWords(rule: string | null | undefined): string {
  if (!rule || rule === 'FREQ=DAILY') return 'every day'
  const weekly = rule.match(/^FREQ=WEEKLY;BYDAY=([A-Z,]+)$/)
  if (weekly) return `on ${andList(weekly[1].split(',').map(d => DAYS[d] ?? d))}`
  const every = rule.match(/^FREQ=DAILY;INTERVAL=(\d+)$/)
  if (every) return `every ${every[1]} days`
  return rule
}

export function doseWords(units: number | null | undefined, unit: DoseUnit | null | undefined): string {
  const n = units ?? 1
  const [one, many] = DOSE_UNITS[unit ?? 'tablet'] ?? ['dose', 'doses']
  return `${Number(n)} ${n === 1 ? one : many}`
}

/** "1 tablet at 8:00 AM and 8:00 PM, every day, after food". */
export function scheduleWords(s: MedicineSchedule | null | undefined): string {
  if (!s) return ''
  return `${doseWords(s.doseUnits, s.doseUnit)} at ${andList(s.times.map(clock))}, ${recurrenceWords(s.recurrence)}${s.withFood && s.withFood !== 'any' ? `, ${WITH_FOOD[s.withFood]}` : ''}`
}

export type MedicineGroup = 'active' | 'as_needed' | 'paused' | 'stopped' | 'supplies'
export const GROUPS: { id: MedicineGroup; title: string }[] = [
  { id: 'active', title: 'Active' }, { id: 'as_needed', title: 'As needed' }, { id: 'paused', title: 'Paused' },
  { id: 'stopped', title: 'Stopped' }, { id: 'supplies', title: 'Supplies' },
]

/** Which list a medicine sits in on Medicines (7.6). */
export function groupOf(m: Medicine): MedicineGroup {
  if (m.status === 'stopped') return 'stopped'
  if (m.form === 'supply') return 'supplies'
  if (m.status === 'paused') return 'paused'
  if (m.asNeeded) return 'as_needed'
  return 'active'
}

/** Today as yyyy-mm-dd in the household's zone, and n days before. */
export function localDay(offsetDays = 0, timeZone = 'Asia/Kolkata'): string {
  const d = new Date(Date.now() + offsetDays * 86_400_000)
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d)
}
