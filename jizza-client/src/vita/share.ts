import { createContext, useContext } from 'react'
import { useQuery } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { ShareAccess, SharePreset, ShareSection, SharedWithMe } from '@/types/vita'
import { vitaKeys } from './queryKeys'

/**
 * Whose record the Health tab is showing (spec 5.10): the caller's own person, where everything is
 * open, or a record someone else keeps and shared with them, section by section.
 */
export interface ShareScope {
  personId: string | null
  shared: boolean
  asSelf: boolean
  managerEmail: string | null
  sections: Partial<Record<ShareSection, ShareAccess>>
}

export const OWN_RECORD: ShareScope = { personId: null, shared: false, asSelf: false, managerEmail: null, sections: {} }

export const ShareContext = createContext<ShareScope>(OWN_RECORD)

export function useShareScope(): ShareScope {
  return useContext(ShareContext)
}

export function scopeOf(entry: SharedWithMe | undefined | null, personId: string): ShareScope {
  if (!entry) return { ...OWN_RECORD, personId }
  return { personId, shared: true, asSelf: entry.asSelf, managerEmail: entry.managerEmail, sections: entry.sections }
}

/** Whether a section can be read: always on one's own record, else only when it was shared. */
export function canSee(scope: ShareScope, section: ShareSection | null | undefined): boolean {
  if (!scope.shared || section == null) return true
  // The emergency card shows the allergies, conditions and medicines printed on it.
  return !!scope.sections[section]
}

/** Whether a section takes new entries: one's own record, or a share with Edit. */
export function canEdit(scope: ShareScope, section: ShareSection | null | undefined): boolean {
  if (!scope.shared) return true
  return section != null && scope.sections[section] === 'edit'
}

/** The section each Health view reads. Null: open on every record. */
export const VIEW_SECTION: Record<string, ShareSection | null> = {
  home: null, conditions: 'conditions', condition: 'conditions', medicines: 'medicines', vitals: 'vitals',
  visits: 'visits', 'visit-prep': 'visits', labs: 'labs', lab: 'labs', documents: 'documents', document: 'documents',
  vaccinations: 'vaccinations', surgeries: 'events', allergies: 'allergies', emergency: 'emergency', profile: 'profile',
  insurance: 'insurance', journal: 'journal', goals: 'goals',
}

/** Views that belong to whoever keeps the record, never shown on a shared one. */
export const MANAGER_ONLY_VIEWS = new Set(['sharing', 'connect', 'settings', 'quick-start', 'summary', 'activity'])

/** Whether a view opens on this record. */
export function viewOpens(scope: ShareScope, viewId: string): boolean {
  if (!scope.shared) return true
  if (MANAGER_ONLY_VIEWS.has(viewId)) return false
  return canSee(scope, VIEW_SECTION[viewId])
}

export const SECTION_LABELS: Record<ShareSection, string> = {
  profile: 'Profile', emergency: 'Emergency card', conditions: 'Conditions', allergies: 'Allergies', medicines: 'Medicines',
  vitals: 'Vitals', labs: 'Lab results', documents: 'Documents', visits: 'Doctors and visits', vaccinations: 'Vaccinations',
  events: 'Surgeries and stays', insurance: 'Insurance', journal: 'Journal', goals: 'Goals',
}

export const PRESET_LABELS: Record<SharePreset, { label: string; about: string }> = {
  self: { label: 'This is them', about: 'Their own login: Edit on medicines and vitals, View on the rest. Opens on My medicines today.' },
  emergency: { label: 'Emergency only', about: 'View on the emergency card only.' },
  caregiver: { label: 'Caregiver', about: 'Edit on medicines and vitals; View on labs, visits and documents.' },
  everything_except_journal: { label: 'Everything except journal', about: 'View on every section except the journal.' },
}

/** "View on medicines and vitals; Edit on labs" in plain words, for a banner or a row. */
export function accessWords(sections: Partial<Record<ShareSection, ShareAccess>>): string {
  const by = (a: ShareAccess) => (Object.keys(sections) as ShareSection[]).filter(s => sections[s] === a).map(s => SECTION_LABELS[s] ?? s)
  const list = (xs: string[]) => (xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`)
  const edit = by('edit'), view = by('view')
  return [edit.length ? `add to ${list(edit)}` : '', view.length ? `see ${list(view)}` : ''].filter(Boolean).join('; ') || 'nothing yet'
}

/** Records shared with the caller, the person themself first. */
export function useSharedWithMe(enabled = true) {
  return useQuery({ queryKey: vitaKeys.sharedWithMe, queryFn: vitaApi.sharedWithMe, enabled, retry: false })
}

/**
 * Doctors for the record on screen: the household's own list, or on a shared record only the
 * doctors its shared visits and medicines name.
 */
export function useDoctors(enabled = true) {
  const scope = useShareScope()
  const shared = scope.shared && !!scope.personId
  return useQuery({
    queryKey: shared ? vitaKeys.personDoctors(scope.personId!) : vitaKeys.doctors,
    queryFn: () => (shared ? vitaApi.personDoctors(scope.personId!) : vitaApi.doctors()),
    enabled,
  })
}
