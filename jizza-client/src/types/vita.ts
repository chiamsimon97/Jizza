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
