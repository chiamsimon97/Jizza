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
  /** Null on a lab test's row, which carries labTestCode instead. */
  kind: ReadingKind | null
  labTestCode?: string | null
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
  kind?: ReadingKind
  labTestCode?: string
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
  person: {
    id: string; name: string; recordOpen: boolean; status: string
    /** A record someone else keeps, shared with the caller (8a): only its shared sections' cards come. */
    shared?: boolean
    asSelf?: boolean
    sections?: Record<string, ShareAccess>
  }
  /** Null on a shared record: Quick start belongs to the manager. */
  quickStart: VitaQuickStartView['progress'] | null
  latest: Reading[]
  redFlags: HomeRedFlag[]
  conditions: { id: string; name: string; template: string }[]
  nextVisit: Visit | null
  /** Today's medicine time slots. */
  slots: DoseSlot[]
  /** The next scheduled reminders, at most five. */
  reminders: VitaReminder[]
  refillsDue?: number
  lastLabReport?: LastLabReport | null
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
  /** Questions for the doctor, at most 30 (from 7c). */
  questions?: { text: string }[] | null
  documentIds?: string[] | null
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
  /** The interval's config state and label. */
  state: ConfigState
  label: string
  interval?: JsonValue
  needsVerification?: boolean
  /** From 7b: whether the check is due. */
  status?: 'due' | 'upcoming' | 'no_record' | 'not_set'
  statusLabel?: string
  lastDone?: string
  lastDoneFrom?: 'lab' | 'check'
  dueOn?: string
  dueFrom?: 'doctor' | 'interval'
  intervalUsed?: string
}

/** A condition page's data (spec 5.6, 7.5). */
export interface ConditionSummary {
  condition: Condition
  template: ConditionTemplate
  doctor?: Doctor
  measures: ConditionMeasure[]
  lowReadings?: Reading[]
  /** Shown only until there is a result; then labResults takes its place. */
  labs?: string
  labResults?: LabTrend[]
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
  /** Who made the last mark, in words: "You", "Mother", or a member's email (8a). */
  markedByName?: string | null
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

// ── Documents and the extraction review (Phase 1b) ───────────────────────────

export type DocumentKind = 'prescription' | 'lab_report' | 'scan' | 'discharge_summary' | 'certificate' | 'bill' | 'policy' | 'other'

export interface VitaDocument {
  id: string
  personId: string
  kind: DocumentKind
  title: string | null
  documentDate: string | null
  doctorId: string | null
  conditionId: string | null
  fileId: string | null
  contentType: string | null
  sizeBytes: number | null
  pageCount: number | null
  extractionStatus: 'none' | 'pending' | 'done' | 'failed'
  extractionError: string | null
  extractedAt: string | null
  needsPassword: boolean
  hasDraft: boolean
  draftRows?: number
  patientCheck?: 'match' | 'mismatch' | 'unknown'
  confirmed: boolean
  confirmedAt: string | null
  confirmedByUserId: string | null
  private: boolean | null
  notes: string | null
  version?: number
  createdAt?: string
}

export type DocumentInput = Partial<Pick<VitaDocument, 'kind' | 'title' | 'documentDate' | 'doctorId' | 'conditionId' | 'private' | 'notes'>>

export interface DraftTest {
  section?: string | null
  name: string | null
  valueAsPrinted: string | null
  value: number | null
  valueText?: string | null
  unit: string | null
  rangeAsPrinted: string | null
  refText?: string | null
  refLow?: number | null
  refHigh?: number | null
  refLowInclusive?: boolean | null
  refHighInclusive?: boolean | null
  flagAsPrinted: string | null
  flag: 'high' | 'low' | 'normal' | 'unknown' | null
  needsCheck: boolean
  confidence?: number | null
  notes?: string[]
  /** Left out when confirming. */
  skip?: boolean
}

export interface DraftMedicine {
  name: string | null
  nameUnsure: boolean
  strength: string | null
  form: MedicineForm | null
  doseAsPrinted: string | null
  timingAsPrinted: string | null
  frequencyAsPrinted?: string | null
  durationAsPrinted: string | null
  scheduleReading?: string | null
  schedule: ScheduleInput | null
  asNeeded: boolean
  startDate: string | null
  endDate: string | null
  needsCheck: boolean
  notes?: string[]
  skip?: boolean
}

/** What Vita read from a document; nothing in it is saved until the person confirms. */
export interface DocumentDraft {
  kind: DocumentKind
  documentDate: string | null
  documentDateAsPrinted?: string | null
  labName?: string | null
  doctorName?: string | null
  readFrom: 'text' | 'scan' | 'photo'
  pages?: number | null
  rowCount?: number
  notes?: string[]
  patient?: { name?: string | null; age?: string | null; sex?: string | null } | null
  /** Vita's own check; never sent back as truth. */
  patientCheck: { status: 'match' | 'mismatch' | 'unknown'; reasons?: string[]; notes?: string[] }
  tests: DraftTest[]
  medicines: DraftMedicine[]
  advice?: string[]
  reviewAfter?: string | null
  confirmed?: boolean
}

export interface DraftView { document: VitaDocument; draft: DocumentDraft | null; needsPassword?: boolean }

// ── Lab results and checks (Phase 1b, 7b) ────────────────────────────────────

export interface LabTestRef { code: string; name: string; group: string; unit: string; decimals: number }
export interface LabTest extends LabTestRef {
  conversions: { from: string; source: string | null; needsVerification: boolean }[]
}

export interface LabReport {
  id: string
  personId: string
  labName: string | null
  collectedOn: string | null
  reportedOn: string | null
  documentId: string | null
  doctorId: string | null
  status: string
  notes: string | null
  version: number
  date: string | null
  rows: number
  labFlagged: number
}

export type LabFlag = 'low' | 'normal' | 'high' | 'critical' | 'unknown'

/** The target side of a lab value: separate from the lab flag, never merged with it. */
export interface LabTarget {
  status: 'in_range' | 'outside_range' | 'not_set' | 'set'
  range?: string | null
  source?: string | null
  label: string
  needsVerification?: boolean
  doctorName?: string | null
  setOn?: string | null
  outside?: string[]
}

export interface LabResult {
  id: string
  reportId: string
  testCode: string | null
  testNameAsPrinted: string | null
  section: string | null
  valueAsPrinted: string | null
  valueNum: number | null
  valueText: string | null
  unit: string | null
  refText: string | null
  flagAsPrinted: string | null
  flag: LabFlag
  confidence: number | null
  sortOrder: number
  test: LabTestRef | null
  shown: { value: string | null; unit: string | null; range: string | null; converted: boolean; asPrinted?: string; needsVerification?: boolean }
  labFlag: { flag: LabFlag; label: string }
  target: LabTarget
  secondary?: { what: string; value: number; shown: string; unit: string; needsVerification: boolean; source: string | null }[]
}

export interface LabReportView { report: LabReport; date: string | null; results: LabResult[] }

export interface LabResultChange {
  id: string
  testNameAsPrinted?: string | null
  section?: string | null
  valueAsPrinted?: string | null
  unit?: string | null
  rangeAsPrinted?: string | null
  flagAsPrinted?: string | null
  testCode?: string | null
  remove?: boolean
}
export interface LabReportInput {
  labName?: string | null
  collectedOn?: string | null
  reportedOn?: string | null
  doctorId?: string | null
  notes?: string | null
  version?: number
  results?: LabResultChange[]
}

export interface LabChange { by: number; shown: string; direction: 'up' | 'down' | 'same' }
export interface LabTrendPoint extends LabResult {
  date: string
  reportId: string
  labName?: string | null
  comparable: boolean
  changeSincePrevious?: LabChange
}
export interface LabTrend {
  test: LabTestRef
  target: LabTarget
  points: LabTrendPoint[]
  count: number
  changeSinceFirst?: LabChange
  changeSincePrevious?: LabChange
}

export type CheckKind = 'eye' | 'foot' | 'kidney' | 'dental' | 'lipids' | 'hba1c' | 'custom'
export interface VitaCheck {
  id: string
  personId: string
  kind: CheckKind
  customName: string | null
  conditionId: string | null
  doneOn: string | null
  resultNote: string | null
  visitId: string | null
  documentId: string | null
  nextDueOn: string | null
}
export type CheckInput = Partial<Omit<VitaCheck, 'id' | 'personId'>>

export interface LastLabReport { id: string; date: string | null; labName: string | null; rows: number; labFlagged: number }

// ── Visit prep, summary, vaccinations, events (7c) and mailbox sources (7d) ─

export interface PrepReading {
  kind: ReadingKind
  context: string | null
  days: number
  count: number
  outside: number
  average: number | null
  average2?: number | null
  last: { id: string; takenAt: string; value: number; value2: number | null; status: TargetStatus } | null
  range: { status: string; range?: string | null; source?: string | null; label: string; needsVerification?: boolean } | null
}
export interface PrepMedicine {
  name: string
  strength: string | null
  form: string | null
  system: string | null
  status: string
  asNeeded: boolean
  schedule?: { times: string[]; doseUnits: number; doseUnit: DoseUnit; withFood: WithFood; recurrence: string } | null
}
export interface PrepMedicineChange { onDate: string; kind: string; medicine: string; fromText: string | null; toText: string | null; doctorName: string | null; line: string }
export interface PrepLab { code: string; name: string; date: string; flag: LabFlag; value: string | null; unit: string | null; rangeAsPrinted: string | null }
export interface PrepPerson { id: string; name: string; dob?: string | null; age?: number | null; bloodGroup?: string | null; sexAtBirth?: string | null; heightCm?: string | null }

export interface VisitPrep {
  visit: Visit
  person: PrepPerson
  doctor?: { id: string; name: string; speciality: string | null; clinic: string | null }
  questions: string[]
  readings: PrepReading[]
  medicines: PrepMedicine[]
  changesSince?: string
  changesSinceLabel: string
  medicineChanges: PrepMedicineChange[]
  labs: PrepLab[]
  adherence: AdherenceResult | null
  note: string
  sent: false
}

export interface HealthSummary {
  person: PrepPerson
  condition?: Condition
  allergies: { substance: string; severity: string | null; reaction: string | null }[]
  conditions: { name: string; since: string | null }[]
  medicines: PrepMedicine[]
  medicineChanges: PrepMedicineChange[]
  readings: PrepReading[]
  labs: PrepLab[]
  adherence: AdherenceResult | null
  nextVisit?: { id: string; scheduledAt: string; questions: string[] }
  preparedOn: string
  note: string
  sent: false
}

export interface Vaccination {
  id: string
  personId: string
  vaccine: string
  doseNumber: number | null
  givenOn: string | null
  place: string | null
  batch: string | null
  documentId: string | null
  nextDueOn: string | null
  notes: string | null
}
export type VaccinationInput = Partial<Omit<Vaccination, 'id' | 'personId'>>

export type HealthEventKind = 'surgery' | 'procedure' | 'admission' | 'injury' | 'fall'
export interface HealthEvent {
  id: string
  personId: string
  kind: HealthEventKind
  title: string | null
  fromDate: string
  toDate: string | null
  hospital: string | null
  doctorId: string | null
  notes: string | null
  documentIds: string[] | null
}
export type HealthEventInput = Partial<Omit<HealthEvent, 'id' | 'personId'>>

export type MailStatus = 'to_review' | 'nothing_new' | 'needs_sign_in' | 'needs_password' | 'failed'
export interface MailSource {
  id: string
  serverId: string
  serverName: string | null
  personId: string
  personName: string | null
  sender: string
  subject: string | null
  hasPassword: boolean
  enabled: boolean
  signedIn: boolean
  lastStatus: MailStatus | null
  lastCheckedAt: string | null
  lastDocumentId: string | null
}
export interface MailServer { serverId: string; name: string; signedIn: boolean; enabled: boolean }
export interface MailSourcesView { sources: MailSource[]; servers: MailServer[] }
export interface MailSourceInput { serverId?: string; personId?: string; sender?: string; subject?: string | null; password?: string; enabled?: boolean }
export interface MailCheckResult { status: MailStatus; documentId: string | null; found: number; message: string }

// ── Family and sharing (8a, spec 5.10, 7.16) ────────────────────────────────

export type ShareAccess = 'view' | 'edit'
/** The sections a record can be shared by (VitaSection on the server). */
export type ShareSection = 'profile' | 'emergency' | 'conditions' | 'allergies' | 'medicines' | 'vitals' | 'labs' | 'documents'
  | 'visits' | 'vaccinations' | 'events' | 'insurance' | 'journal' | 'goals'
export type SharePreset = 'self' | 'emergency' | 'caregiver' | 'everything_except_journal'

export interface ShareLink {
  personId: string
  personName: string
  granteeUserId: string
  granteeEmail: string | null
  asSelf: boolean
  sections: Partial<Record<ShareSection, ShareAccess>>
  shares: { id: string; section: ShareSection; access: ShareAccess; grantedAt: string }[]
}

export interface SharesView {
  links: ShareLink[]
  members: { userId: string; email: string | null }[]
  hasFamily: boolean
  presets: { key: SharePreset; sections: Partial<Record<ShareSection, ShareAccess>> }[]
  sectionKeys: ShareSection[]
  /** "Pages already saved or printed cannot be recalled." */
  note: string
}

export interface ShareInput {
  personId: string
  granteeUserId: string
  preset?: SharePreset | null
  /** Overrides the preset section by section; "none" ends that section. */
  sections?: Partial<Record<ShareSection, ShareAccess | 'none'>>
  asSelf?: boolean
}

export interface SharedWithMe {
  personId: string
  /** "You" when the caller is linked as the person themself. */
  name: string
  personName: string
  asSelf: boolean
  managerEmail: string | null
  sections: Partial<Record<ShareSection, ShareAccess>>
}

export interface AccessEntry {
  id: string
  granteeUserId: string
  granteeEmail: string | null
  asSelf: boolean
  section: ShareSection
  access: ShareAccess
  grantedAt: string
  endedAt: string | null
  endedReason: string | null
  active: boolean
}
export interface AccessList { personId: string; personName: string; entries: AccessEntry[]; note: string }

export interface HealthConsent {
  id: string
  personId: string
  scope: 'view' | 'manage'
  channel: string | null
  grantedAt: string
  withdrawnAt: string | null
  open: boolean
}

export interface CareNote { id: string; text: string; writtenBy: string | null; writtenByName: string; writtenAt: string }

// ── Insurance and claims (8b, spec 4.3, 7.15) ──────────────────────────────

export type PolicyKind = 'health' | 'super_topup' | 'critical_illness' | 'personal_accident' | 'government_scheme'
export type PremiumFrequency = 'monthly' | 'quarterly' | 'yearly' | 'single'
export interface PolicyDetail {
  policyId: string
  /** "•••• 3456": the full numbers go in and never come back. */
  policyNumberMasked: string | null
  cardNumberMasked: string | null
  tpaName: string | null
  cashlessCardDocumentId: string | null
  roomRentLimit: number | null
  copayPct: number | null
  waitingPeriods: { text: string }[]
  networkHospitalsNote: string | null
}
export interface HealthPolicy {
  id: string
  /** The holder. */
  personId: string
  kind: PolicyKind
  providerName: string | null
  sumAssured: number | null
  deductible: number | null
  premium: number | null
  premiumFrequency: PremiumFrequency | null
  renewalDate: string | null
  policyEndDate: string | null
  employerProvided: boolean | null
  coveredPersonIds: string[]
  floater: boolean
  policyYear: { from: string; to: string } | null
  /** Settled claims this policy year; null for a helper or with no renewal date. */
  usedThisYear: number | null
  leftThisYear: number | null
  renewalReminderOn: string | null
  detail: PolicyDetail
  /** Only for whoever keeps the policies. */
  claimCount?: number
  shared: boolean
}
export interface PolicyInput {
  kind?: PolicyKind
  providerName?: string | null
  sumAssured?: number | null
  deductible?: number | null
  premium?: number | null
  premiumFrequency?: PremiumFrequency | null
  renewalDate?: string | null
  policyEndDate?: string | null
  employerProvided?: boolean | null
  personId?: string
  coveredPersonIds?: string[]
}
export interface PolicyDetailInput {
  policyNumber?: string | null
  cardNumber?: string | null
  tpaName?: string | null
  cashlessCardDocumentId?: string | null
  roomRentLimit?: number | null
  copayPct?: number | null
  waitingPeriods?: { text: string }[]
  networkHospitalsNote?: string | null
}
export type ClaimStatus = 'draft' | 'filed' | 'query' | 'settled' | 'rejected'
export interface HealthClaim {
  id: string
  personId: string
  policyId: string
  eventId: string | null
  visitId: string | null
  admittedOn: string
  dischargedOn: string | null
  amountClaimed: number | null
  amountPaid: number | null
  status: ClaimStatus
  documentIds: string[] | null
  notes: string | null
  outOfPocket: number | null
  policyYearTo?: string | null
  policy?: { kind: PolicyKind; providerName: string | null }
}
export type ClaimInput = Partial<Pick<HealthClaim, 'policyId' | 'eventId' | 'visitId' | 'admittedOn' | 'dischargedOn' | 'amountClaimed' | 'amountPaid' | 'status' | 'documentIds' | 'notes'>>

// ── Journal and goals (8c) ─────────────────────────────────────────────────────

/** One day of the journal (7.12): mood and energy 1-5, pain 0-10, symptom words and a note. */
export interface JournalEntry {
  id: string
  personId: string
  entryDate: string
  mood: number | null
  energy: number | null
  pain: number | null
  symptoms: string[]
  notes: string | null
  source: string
  createdByUserId: string | null
  createdAt: string
  updatedAt: string | null
}
export interface JournalInput {
  mood?: number | null
  energy?: number | null
  pain?: number | null
  symptoms?: string[] | null
  notes?: string | null
}

export type GoalKind = 'weight' | 'steps' | 'sleep_hours' | 'water' | 'custom' | 'bp_range' | 'glucose_range' | 'hba1c'
export type GoalStatus = 'active' | 'paused' | 'met' | 'dropped'
export type GoalContext = 'before_meal' | 'after_meal'

/** The target a goal is held to, with where it comes from, in the server's words. */
export interface GoalTarget {
  text: string
  source: 'doctor' | 'vita_default' | 'you' | string
  label: string
  needsVerification?: boolean
  configKey?: string
  doctorId?: string
  doctorName?: string
  setOn?: string
  note?: string
}
export interface GoalCheckin {
  weekStart: string
  source: 'manual' | 'measurements' | 'none'
  value: number | null
  value2: number | null
  meets: boolean | null
  note?: string
}
export interface HealthGoal {
  id: string
  personId: string
  kind: GoalKind
  title: string | null
  context: GoalContext | null
  /** A medical goal's target from the doctor or config; the user's own number otherwise. */
  target: GoalTarget | null
  targetNote?: string
  target2?: number | null
  startValue: number | null
  unit: string | null
  byDate: string | null
  weeklyPlan: { text: string }[]
  status: GoalStatus
  metOn: string | null
  medical: boolean
  /** The last 12 weeks, oldest first. */
  checkins: GoalCheckin[]
  weeksMetInARow: number
  metAfterWeeks: number
  planNote: string
  createdByUserId?: string | null
  createdAt?: string
}
export interface GoalInput {
  kind?: GoalKind
  title?: string | null
  context?: GoalContext | null
  /** Refused for blood pressure, sugar and HbA1c, which read the doctor's or Vita's target. */
  target?: number | null
  startValue?: number | null
  unit?: string | null
  byDate?: string | null
  weeklyPlan?: string[]
  status?: GoalStatus
}
export interface GoalCheckinInput {
  weekStart?: string
  value: number
  value2?: number | null
  note?: string | null
}
export interface GoalPreview {
  personId: string
  kind: GoalKind
  canCreate: boolean
  target?: GoalTarget | null
  message: string
  planNote: string
  metAfterWeeks: number
}
