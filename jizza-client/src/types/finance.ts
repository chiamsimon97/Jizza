/** Kai Portfolio DTOs, as /api/finance returns them (spec 5.15). */

export type PersonKind = 'self' | 'spouse' | 'child' | 'parent' | 'huf' | 'other'
export type PersonType = 'individual' | 'minor' | 'huf'
export type PersonStatus = 'active' | 'archived' | 'deceased' | 'handed_over'

export interface FinPerson {
  id: string
  userId: string
  displayName: string
  kind: PersonKind
  type: PersonType
  dob: string | null
  earning: boolean
  dependent: boolean
  status: PersonStatus
  sortOrder: number
  handedOverToUserId: string | null
}

/** What the add and edit form sends. */
export interface FinPersonInput {
  displayName?: string
  kind?: PersonKind
  type?: PersonType
  dob?: string | null
  isEarning?: boolean
  isDependent?: boolean
}

export interface FinSettings {
  advanced: boolean
  hideAmounts: boolean
}

export interface FinProfileView {
  profile: { id: string; baseCurrency: string; version: number }
  people: FinPerson[]
  settings: FinSettings
  setupProgress: { quickStart: 'not_started' | 'in_progress' | 'done'; peopleManaged: number }
}

export interface FinConsent {
  id: string
  personId: string
  scope: 'view' | 'manage'
  grantedAt: string
  withdrawnAt: string | null
  channel: string | null
  evidenceFileId: string | null
  purgeAfter: string | null
}

export type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue }

export interface FinConfigItem {
  key: string
  area: string
  value: JsonValue
  defaultValue: JsonValue
  source: 'kai_default' | 'you'
  isOverride: boolean
  effectiveFrom: string
  sourceUrl: string | null
  verifiedOn: string | null
  needsVerification: boolean
}
