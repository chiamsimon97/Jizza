import type { JsonValue } from './finance'

/** A person Vita can keep a record for: "Me" and the people the user manages (never an HUF). */
export interface VitaPerson {
  id: string
  displayName: string
  kind: 'self' | 'spouse' | 'child' | 'parent' | 'other' | 'huf'
  type: string
  dob: string | null
  status: 'active' | 'archived' | 'deceased' | 'handed_over'
  statusOn?: string | null
  sortOrder?: number
}

export interface VitaPersonInput {
  displayName: string
  kind: 'spouse' | 'child' | 'parent' | 'other'
  type?: 'individual' | 'minor'
  dob?: string | null
}

export type BloodGroup = 'a_pos' | 'a_neg' | 'b_pos' | 'b_neg' | 'ab_pos' | 'ab_neg' | 'o_pos' | 'o_neg' | 'unknown'
export type SexAtBirth = 'female' | 'male' | 'intersex' | 'unknown'
export type ReminderMode = 'each_slot' | 'daily_check' | 'off'

/** The profile row as the server sends it; secret columns are never in it. */
export interface VitaProfile {
  id: string
  personId: string
  bloodGroup: BloodGroup | null
  heightCm: number | null
  sexAtBirth: SexAtBirth | null
  organDonor: boolean | null
  preferredHospital: string | null
  preferredHospitalPhone: string | null
  abhaLast4: string | null
  timezone: string
  allergiesNoneKnown: boolean
  conditionsNoneKnown: boolean
  reminderMode: ReminderMode
  notes: string | null
  updatedAt?: string
}

export interface VitaProfileView {
  person: { id: string; displayName: string; kind: string; dob: string | null; age: number | null; status: string; statusOn: string | null }
  profile: VitaProfile
}

/** What the profile form sends; a field sent as null is cleared. */
export type VitaProfileInput = Partial<Omit<VitaProfile, 'id' | 'personId' | 'abhaLast4' | 'updatedAt'>> & {
  dob?: string | null
  abhaNumber?: string | null
}

export interface EmergencyContact {
  id: string
  name: string
  relation: string | null
  phone: string
  sortOrder: number
}
export type EmergencyContactInput = { name: string; relation?: string | null; phone: string }

export type ConditionTemplate = 'diabetes_t2' | 'hypertension' | 'generic'
export type ConditionStatus = 'active' | 'resolved' | 'suspected'
export interface Condition {
  id: string
  name: string
  template: ConditionTemplate | null
  status: ConditionStatus | null
  since: string | null
  advice: string | null
  private: boolean | null
  notes: string | null
  /** The doctor who looks after it (Phase 1a). */
  doctorId?: string | null
}
export type ConditionInput = Partial<Omit<Condition, 'id'>> & { name?: string }

export type AllergyCategory = 'drug' | 'food' | 'environment' | 'other'
export type AllergySeverity = 'mild' | 'moderate' | 'severe' | 'unknown'
export interface Allergy {
  id: string
  substance: string
  category: AllergyCategory | null
  reaction: string | null
  severity: AllergySeverity | null
  confirmedByDoctor: boolean | null
}
export type AllergyInput = Partial<Omit<Allergy, 'id'>> & { substance?: string }

/** A reading; Phase 0 keeps weight only, always stored in kg. */
export interface Measurement {
  id: string
  personId: string
  kind: 'weight'
  value: number
  unit: 'kg'
  takenAt: string
  note: string | null
  source: string
  detail?: { typed?: number; typedUnit?: string } | null
}
export interface MeasurementInput {
  kind: 'weight'
  value: number | string
  unit: 'kg' | 'lb'
  takenAt?: string
  note?: string | null
}

/** One figure on the emergency card, laid out and printed by the client. */
export interface EmergencyCard {
  personId: string
  name: string
  age: number | null
  bloodGroup: BloodGroup | null
  organDonor: boolean | null
  preferredHospital: string | null
  preferredHospitalPhone: string | null
  allergiesNoneKnown: boolean
  allergies: { substance: string; severity: string; reaction: string | null }[]
  conditionsNoneKnown: boolean
  conditions: { name: string; since: string | null }[]
  medicines: { name: string }[]
  medicinesAvailable: boolean
  emergencyContacts: { name: string; relation: string | null; phone: string }[]
  emergencyNumbers: { emergency?: string; ambulanceWhereTheyLive?: string } | null
  emergencyNumbersNeedVerification: boolean
  updatedOn: string
  closed: boolean
}

export type ConfigState = 'verified' | 'needs_verification' | 'not_set' | 'product_default'

/** A setting or clinical figure with the label the screen must show (spec 8). */
export interface VitaConfigItem {
  key: string
  area: string
  value: JsonValue | null
  defaultValue: JsonValue | null
  source: 'vita_default' | 'you' | 'none'
  isOverride: boolean
  effectiveFrom: string | null
  sourceUrl: string | null
  verifiedOn: string | null
  needsVerification: boolean
  state: ConfigState
  label: string
}

export type QuickStartStep = 'dob' | 'sex' | 'height_weight' | 'blood_group' | 'conditions' | 'allergies' | 'medicines' | 'contact'

export interface VitaQuickStartView {
  personId: string
  progress: { completed: QuickStartStep[]; skipped: QuickStartStep[]; next: QuickStartStep | null; done: boolean; total: number }
}

/** The help block for a red flag, exactly as the server words it (spec 7.19, 8.2). */
export interface RedFlag {
  level: 'emergency' | 'urgent' | 'self_harm'
  personName?: string
  wording?: string
  advice?: string
  question?: string
  signs?: string[]
  reading?: string
  emergencyNumbers?: { emergency?: string; ambulanceWhereTheyLive?: string } | null
  emergencyNumbersNeedVerification?: boolean
  preferredHospital?: string | null
  preferredHospitalPhone?: string | null
  doctorName?: string | null
  doctorPhone?: string | null
  helpline?: { helplineName?: string; helpline?: string; reply?: string } | null
  needsVerification?: boolean
  rule?: { key: string; state: ConfigState; label: string; note?: string }
}

/** A form Vita put in the chat; saved only when the person presses Save. */
export type VitaFormKind = 'profile' | 'emergency_contact'
export interface VitaForm {
  id: string
  form: VitaFormKind
  personId: string
  personName?: string
  title?: string
  intro?: string
  values?: Record<string, unknown>
}

// ── Readings, targets, doctors and visits (Phase 1a) ─────────────────────────

export type ReadingKind = 'bp' | 'glucose' | 'weight' | 'heart_rate' | 'spo2' | 'temperature'
export type ReadingContext = 'fasting' | 'before_meal' | 'after_meal' | 'random' | 'bedtime'
export type Meal = 'breakfast' | 'lunch' | 'dinner'
export type TargetStatus = 'in_range' | 'outside_range' | 'red_flag' | 'not_set'

/** A red flag the evaluator raised, in the server's words. */
export interface RedFlagHit {
  ruleId: string
  level: 'urgent' | 'emergency'
  advice: string | null
  question: string | null
  signs: string[]
  veryLow: boolean
  needsVerification: boolean
}

/** Target status of one reading: the range used and who set it (spec 5.1). */
export interface Evaluation {
  status: TargetStatus
  outside: string[]
  range: string | null
  rangeSource: string | null
  doctorId: string | null
  doctorName: string | null
  setOn: string | null
  needsVerification: boolean
  label: string
  redFlag: RedFlagHit | null
}

/** Any reading, in canonical units (mmHg, mg/dL, kg, °C, bpm, %). */
export interface Reading {
  id: string
  personId: string
  kind: ReadingKind
  value: number
  value2: number | null
  value3: number | null
  unit: string
  context: ReadingContext | null
  meal: Meal | null
  setting: 'home' | 'clinic' | null
  takenAt: string
  note: string | null
  source: string
  pairId: string | null
  createdByUserId?: string | null
  detail?: { redFlag?: { level: string; rule: string; confirmedAt?: string } } | null
  evaluation?: Evaluation | null
  /** A typed red flag: ask "Is 182/112 right?" before anyone is alerted. */
  confirmNeeded?: boolean
  confirmQuestion?: string
  alerted?: boolean
}

export interface ReadingInput {
  kind: ReadingKind
  value: number | string
  value2?: number | string | null
  value3?: number | string | null
  unit?: string
  context?: ReadingContext | null
  meal?: Meal | null
  setting?: 'home' | 'clinic' | null
  takenAt?: string
  note?: string | null
}

/** The answer to a red flag's confirmation: whether anyone was alerted, and the help to show. */
export interface RedFlagAnswer extends Omit<Reading, 'note'> {
  alerted: boolean
  /** "Nobody was alerted. Correct the reading or delete it." after a no. */
  note?: string | null
  help?: RedFlag
}

export interface TrendStat { sum: number; count: number; mean: number; shown: number; min: number; max: number }
export interface TrendSide { value: TrendStat | null; value2: TrendStat | null; count: number; outside: number }
export interface TrendPoint { id: string; takenAt: string; value: number; value2: number | null; pairId: string | null; status: TargetStatus | null }
export interface TrendDay { date: string; morning: TrendPoint[]; evening: TrendPoint[] }

export interface RangeSummary {
  status: 'set' | 'not_set'
  range?: string
  source?: string
  label: string
  needsVerification?: boolean
}

export interface TrendView {
  kind: ReadingKind
  days: number
  zone: string
  context?: ReadingContext
  range: RangeSummary
  trend: { kind: ReadingKind; count: number; last: TrendPoint | null; all: TrendSide; morning: TrendSide; evening: TrendSide; days: TrendDay[] }
}

/** One measure's range slot, with the person's own row and the doctor's target where set. */
export interface RangeView {
  kind: ReadingKind
  context: string | null
  setting: string | null
  status: 'set' | 'not_set'
  range: string | null
  source: string | null
  label: string
  needsVerification: boolean
  doctorId: string | null
  doctorName: string | null
  setOn: string | null
  own: UsualRange | null
  doctorTarget: UsualRange | null
}

export interface UsualRange {
  id: string
  kind: ReadingKind
  context: string | null
  setting: string | null
  low: number | null
  high: number | null
  low2: number | null
  high2: number | null
  setBy: 'user' | 'doctor'
  doctorId: string | null
  setOn: string | null
  note: string | null
}

export interface RangeInput {
  kind: ReadingKind
  context?: string | null
  setting?: string | null
  low?: number | string | null
  high?: number | string | null
  low2?: number | string | null
  high2?: number | string | null
  setBy: 'user' | 'doctor'
  doctorId?: string | null
  setOn?: string | null
  note?: string | null
}

/** A red-flag banner on Home: the latest reading of a kind, inside the recent window. */
export interface HomeRedFlag {
  measurementId: string
  kind: ReadingKind
  level: 'urgent' | 'emergency'
  rule: string
  confirmed: boolean
  advice: string | null
  question: string | null
  needsVerification: boolean
  wording: string
}

export interface HomeView {
  person: { id: string; name: string; recordOpen: boolean; status: string }
  quickStart: VitaQuickStartView['progress']
  latest: Reading[]
  redFlags: HomeRedFlag[]
  conditions: { id: string; name: string; template: string }[]
  nextVisit: Visit | null
  /** Today's medicine time slots. */
  slots: DoseSlot[]
  /** The next scheduled reminders, at most five. */
  reminders: VitaReminder[]
  refillsDue?: number
}

export type DoctorKind = 'doctor' | 'hospital' | 'clinic' | 'lab' | 'pharmacy'
export interface Doctor {
  id: string
  kind: DoctorKind
  name: string
  speciality: string | null
  clinic: string | null
  phone: string | null
  address: string | null
  notes: string | null
  nextVisit?: { visitId: string; scheduledAt: string; personId: string; personName: string | null } | null
  targets?: (UsualRange & { personId: string; personName: string | null })[]
}
export type DoctorInput = Partial<Omit<Doctor, 'id' | 'nextVisit' | 'targets'>> & { name?: string }

export type VisitStatus = 'planned' | 'done' | 'cancelled'
export interface Visit {
  id: string
  personId: string
  doctorId: string | null
  scheduledAt: string
  status: VisitStatus
  reason: string | null
  notes: string | null
  followUpOn: string | null
}
export type VisitInput = Partial<Omit<Visit, 'id' | 'personId'>>

export interface ConditionMeasure {
  kind: ReadingKind
  context?: ReadingContext
  last7?: TrendView
  last30?: TrendView
  last90?: TrendView
}

export interface ConditionCheck {
  check: string
  state: ConfigState
  label: string
  interval?: JsonValue
}

/** A condition page's data (spec 5.6, 7.5). */
export interface ConditionSummary {
  condition: Condition
  template: ConditionTemplate
  doctor?: Doctor
  measures: ConditionMeasure[]
  lowReadings?: Reading[]
  labs?: string
  checks: ConditionCheck[]
  medicines: { id: string; name: string; strength: string | null; status: MedicineStatus; schedule: MedicineSchedule | null; adherence7Days?: AdherenceResult }[]
}

// ── Medicines, doses and reminders (Phase 1a) ────────────────────────────────

export type MedicineForm = 'tablet' | 'capsule' | 'syrup' | 'injection' | 'inhaler' | 'drops' | 'cream' | 'supply' | 'other'
export type MedicineSystem = 'allopathy' | 'ayurveda' | 'homeopathy' | 'siddha' | 'unani' | 'supplement' | 'other'
export type DoseUnit = 'tablet' | 'ml' | 'units' | 'puffs' | 'drops' | 'other'
export type WithFood = 'before' | 'with' | 'after' | 'any'
export type MedicineStatus = 'active' | 'paused' | 'stopped'

export interface MedicineSchedule {
  id?: string
  times: string[]
  recurrence: string
  doseUnits: number
  doseUnit: DoseUnit
  withFood: WithFood
  effectiveFrom?: string
  effectiveTo?: string | null
}
export type ScheduleInput = Partial<Omit<MedicineSchedule, 'id' | 'effectiveFrom' | 'effectiveTo'>> & { times?: string[] }

export interface Medicine {
  id: string
  personId: string
  name: string
  brandName: string | null
  genericName: string | null
  strength: string | null
  form: MedicineForm | null
  system: MedicineSystem | null
  conditionId: string | null
  prescribedBy: string | null
  startDate: string | null
  endDate: string | null
  asNeeded: boolean | null
  trackStock: boolean | null
  stockUnits: number | null
  unitsPerPack: number | null
  refillLeadDays: number | null
  private: boolean | null
  notes: string | null
  status: MedicineStatus
  stoppedOn?: string | null
  schedule: MedicineSchedule | null
  refillDue: boolean
}

export type MedicineInput = Partial<Omit<Medicine, 'id' | 'personId' | 'status' | 'schedule' | 'refillDue' | 'stoppedOn'>> & {
  name?: string
  schedule?: ScheduleInput | null
}

export interface MedicineChange {
  fromDate?: string | null
  strength?: string | null
  schedule?: ScheduleInput
  doctorId?: string | null
  visitId?: string | null
  reason?: string | null
}

export interface MedicineEvent {
  id: string
  medicineId: string
  kind: 'started' | 'dose_changed' | 'paused' | 'resumed' | 'stopped' | 'refilled' | string
  onDate: string
  fromText: string | null
  toText: string | null
  reason: string | null
  doctorId: string | null
}

export type DoseStatus = 'due' | 'taken' | 'skipped' | 'missed' | 'partial' | 'snoozed'
export interface Dose {
  id: string
  medicineId: string
  medicineName: string | null
  strength: string | null
  doseUnits: number | null
  doseUnit: DoseUnit | null
  withFood: WithFood | null
  status: DoseStatus
  snoozedUntil: string | null
  takenAt: string | null
  actedAt: string | null
  markedBy: string | null
  late: boolean
  version: number
}

/** One time slot of one person's day, with its doses. */
export interface DoseSlot {
  personId: string
  personName: string
  dueAt: string
  localTime: string
  localDate: string
  zone: string
  status: DoseStatus
  snoozedUntil: string | null
  markedBy: string | null
  /** Whether the caller made the last mark. */
  markedByYou?: boolean
  markedAt: string | null
  missedAt: string
  doses: Dose[]
}

export interface DoseAction { action: 'taken' | 'skipped' | 'snoozed'; takenAt?: string; snoozeMinutes?: number; note?: string }

export interface AdherenceResult { due: number; taken: number; skipped: number; missed: number; late: number; exact: number | null; percent: number | null }
export interface Adherence {
  from: string
  to: string
  overall: AdherenceResult
  medicines: { medicineId: string; name: string | null; result: AdherenceResult }[]
}

export type ReminderKind = 'visit' | 'vaccination' | 'policy_renewal' | 'check_due' | 'custom'
export interface VitaReminder {
  id: string
  personId: string
  kind: ReminderKind | 'refill' | string
  title: string | null
  dueAt: string
  recurrence: string | null
  status: 'scheduled' | 'sent' | 'done' | 'dismissed' | 'cancelled'
}
export type ReminderInput = Partial<Omit<VitaReminder, 'id' | 'personId'>>
