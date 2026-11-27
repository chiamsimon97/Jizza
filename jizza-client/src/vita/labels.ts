import type { JsonValue } from '@/types/finance'
import type {
  AllergyCategory, AllergySeverity, BloodGroup, ConditionStatus, ConditionTemplate, QuickStartStep, ReminderMode, SexAtBirth, VitaPerson,
} from '@/types/vita'

export const BLOOD_GROUPS: Record<BloodGroup, string> = {
  a_pos: 'A+', a_neg: 'A−', b_pos: 'B+', b_neg: 'B−', ab_pos: 'AB+', ab_neg: 'AB−', o_pos: 'O+', o_neg: 'O−', unknown: 'Don’t know',
}
export const SEXES: Record<SexAtBirth, string> = { female: 'Female', male: 'Male', intersex: 'Intersex', unknown: 'Prefer not to say' }
export const REMINDER_MODES: Record<ReminderMode, { label: string; about: string }> = {
  each_slot: { label: 'Each time slot', about: 'A reminder at every dose time.' },
  daily_check: { label: 'One check a day', about: 'One reminder at the daily-check time.' },
  off: { label: 'Off', about: 'No reminders; readings and records still work.' },
}
export const SEVERITIES: Record<AllergySeverity, string> = { severe: 'Severe', moderate: 'Moderate', mild: 'Mild', unknown: 'Not sure' }
export const ALLERGY_KINDS: Record<AllergyCategory, string> = { drug: 'Medicine', food: 'Food', environment: 'Environment', other: 'Other' }
export const CONDITION_STATUS: Record<ConditionStatus, string> = { active: 'Active', resolved: 'Resolved', suspected: 'Being checked' }
export const TEMPLATES: Record<ConditionTemplate, string> = { diabetes_t2: 'Type 2 diabetes', hypertension: 'High blood pressure', generic: 'Other condition' }

/** The quick picks on Conditions and in Quick start (7.3); the first two have their own pages from Phase 1a. */
export const COMMON_CONDITIONS: { name: string; template: ConditionTemplate }[] = [
  { name: 'Type 2 diabetes', template: 'diabetes_t2' },
  { name: 'High blood pressure', template: 'hypertension' },
  { name: 'Thyroid', template: 'generic' },
  { name: 'Asthma', template: 'generic' },
  { name: 'Heart disease', template: 'generic' },
]

export const PERSON_KINDS: Record<'parent' | 'spouse' | 'child' | 'other', string> = {
  parent: 'Parent', spouse: 'Spouse or partner', child: 'Child', other: 'Someone else',
}

/** "Me" for the user, otherwise the name they gave. */
export function personName(p: Pick<VitaPerson, 'kind' | 'displayName'> | undefined): string {
  if (!p) return ''
  return p.kind === 'self' ? 'You' : p.displayName
}

export const QUICK_START_TITLES: Record<QuickStartStep, { title: string; why: string }> = {
  dob: { title: 'Date of birth', why: 'Age goes on the emergency card.' },
  sex: { title: 'Sex at birth', why: 'Some lab ranges differ by it.' },
  height_weight: { title: 'Height and weight', why: 'A first weight starts the trend.' },
  blood_group: { title: 'Blood group', why: 'Shown large on the emergency card.' },
  conditions: { title: 'Known conditions', why: 'Each long-term condition gets its own page.' },
  allergies: { title: 'Allergies', why: 'Shown first on the emergency card, most severe first.' },
  medicines: { title: 'Current medicines', why: 'Medicines and their reminders come with the next update.' },
  contact: { title: 'Emergency contact', why: 'Who a hospital should call.' },
}
export const QUICK_START_ORDER: QuickStartStep[] = ['dob', 'sex', 'height_weight', 'blood_group', 'conditions', 'allergies', 'medicines', 'contact']

/** Plain names for Vita's settings and figures (spec 7.18, 8). Unknown keys show the key itself. */
const CONFIG_LABELS: Record<string, string> = {
  'display.units': 'Units',
  'emergency.numbers': 'Emergency numbers',
  'clinical.redflags.bp': 'Blood pressure red flag',
  'clinical.redflags.low_sugar': 'Low sugar red flag',
  'clinical.redflags.high_sugar': 'High sugar red flag',
  'clinical.redflags.spo2': 'Oxygen (SpO2) red flag',
  'clinical.redflags.temperature': 'Temperature red flag',
  'clinical.redflags.chat_words': 'Emergency words in chat',
  'clinical.redflags.self_harm': 'Self-harm: helpline',
  'clinical.ranges.general.bp_clinic': 'Blood pressure, clinic reading',
  'clinical.ranges.general.bp_home': 'Blood pressure, home reading',
  'clinical.ranges.general.glucose_fasting': 'Sugar, fasting',
  'clinical.ranges.general.glucose_after_meal': 'Sugar, after food',
  'clinical.ranges.general.spo2': 'Oxygen (SpO2)',
  'clinical.ranges.general.resting_heart_rate': 'Resting heart rate',
  'clinical.ranges.general.temperature': 'Temperature',
  'clinical.ranges.diabetes_t2.glucose_before_meal': 'Sugar before meals',
  'clinical.ranges.diabetes_t2.glucose_after_meal': 'Sugar after meals',
  'clinical.ranges.diabetes_t2.hba1c': 'HbA1c',
  'clinical.ranges.hypertension.bp_clinic': 'Blood pressure, clinic reading',
  'clinical.ranges.hypertension.bp_home': 'Blood pressure, home reading',
  'clinical.intervals.diabetes_t2.hba1c': 'HbA1c check',
  'clinical.intervals.diabetes_t2.eye': 'Eye check (retina)',
  'clinical.intervals.diabetes_t2.foot': 'Foot check',
  'clinical.intervals.diabetes_t2.urine_albumin': 'Urine albumin',
  'clinical.intervals.diabetes_t2.kidney_function': 'Kidney function',
  'clinical.intervals.diabetes_t2.lipids': 'Lipid profile',
  'clinical.intervals.hypertension.bp_checks': 'Blood pressure checks',
  'clinical.intervals.hypertension.kidney_function': 'Kidney function',
  'clinical.intervals.hypertension.lipids': 'Lipid profile',
}

export function configLabel(key: string): string {
  return CONFIG_LABELS[key] ?? key
}

/** The groups Settings shows the figures in, by key prefix. */
export const FIGURE_GROUPS: { title: string; about: string; prefix: string }[] = [
  { title: 'Red flags', about: 'When Vita shows “Act today” or “Call now” help first.', prefix: 'clinical.redflags.' },
  { title: 'Usual ranges: everyone', about: 'Used when no doctor has set a target for the person.', prefix: 'clinical.ranges.general.' },
  { title: 'Usual ranges: type 2 diabetes', about: 'For a person with the diabetes page.', prefix: 'clinical.ranges.diabetes_t2.' },
  { title: 'Usual ranges: high blood pressure', about: 'For a person with the blood-pressure page.', prefix: 'clinical.ranges.hypertension.' },
  { title: 'Check intervals: type 2 diabetes', about: 'How often each check is due.', prefix: 'clinical.intervals.diabetes_t2.' },
  { title: 'Check intervals: high blood pressure', about: 'How often each check is due.', prefix: 'clinical.intervals.hypertension.' },
]

type Obj = { [k: string]: JsonValue }
const isObj = (v: JsonValue | null | undefined): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
const num = (v: JsonValue | undefined) => (typeof v === 'number' ? String(v) : typeof v === 'string' ? v : '')

function bound(b: JsonValue | undefined): string {
  if (!isObj(b)) return ''
  if (b.below != null) return `below ${num(b.below)}`
  if (b.atMost != null) return `${num(b.atMost)} or lower`
  return ''
}

function times(n: JsonValue | undefined): string {
  return n === 1 ? 'once' : n === 2 ? 'twice' : `${num(n)} times`
}

/**
 * A figure in one line of plain words, written only from the value the server sent: this never adds
 * a number of its own. Anything it does not recognise is shown as its fields.
 */
export function describeFigure(key: string, value: JsonValue | null): string {
  if (value == null) return 'Not set: ask your doctor'
  if (!isObj(value)) return String(value)
  const unit = typeof value.unit === 'string' ? ` ${value.unit}` : ''
  if (typeof value.sameAs === 'string') return `Same as “${configLabel(value.sameAs)}” for everyone`
  if (key === 'clinical.redflags.bp') return `Systolic above ${num(value.systolicAbove)} or diastolic above ${num(value.diastolicAbove)}${unit}`
  if (key === 'clinical.redflags.low_sugar') return `Below ${num(value.below)}${unit}; very low below ${num(value.veryLowBelow)}${unit}`
  if (key === 'clinical.redflags.self_harm') return `${value.helplineName ?? 'Helpline'} ${num(value.helpline)}`
  if (Array.isArray(value.signs)) return `Happening now: ${value.signs.join(', ')}`
  if (isObj(value.systolic) || isObj(value.diastolic)) {
    const s = isObj(value.systolic) ? value.systolic : {}
    const d = isObj(value.diastolic) ? value.diastolic : {}
    if (s.below != null && d.below != null) return `Below ${num(s.below)}/${num(d.below)}${unit}`
    return `Systolic ${bound(s)}, diastolic ${bound(d)}${unit}`
  }
  if (value.low != null && value.high != null) return `${num(value.low)}–${num(value.high)}${unit}`
  if (value.below != null) return `Below ${num(value.below)}${unit}`
  if (value.atMost != null) return unit === ' %' && typeof value.atMost === 'number' ? `${value.atMost.toFixed(1)}% or lower` : `${num(value.atMost)}${unit} or lower`
  if (value.timesPerYearAtLeast != null) return `At least ${times(value.timesPerYearAtLeast)} a year`
  if (isObj(value.atTarget) || isObj(value.notAtTargetOrAfterChange)) {
    const a = isObj(value.atTarget) ? `at least ${times(value.atTarget.timesPerYearAtLeast)} a year at target` : ''
    const b = isObj(value.notAtTargetOrAfterChange) ? `about every ${num(value.notAtTargetOrAfterChange.aboutEveryMonths)} months when not, or after a change of treatment` : ''
    const line = [a, b].filter(Boolean).join('; ')
    return line.charAt(0).toUpperCase() + line.slice(1)
  }
  return Object.entries(value).map(([k, v]) => `${k} ${typeof v === 'object' ? '…' : String(v)}`).join(' · ')
}
