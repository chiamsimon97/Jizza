import { api } from '@/lib/api'
import type { JsonValue } from '@/types/finance'
import type {
  Allergy, AllergyInput, Condition, ConditionInput, EmergencyCard, EmergencyContact, EmergencyContactInput, Measurement,
  MeasurementInput, QuickStartStep, ConditionSummary, Doctor, DoctorInput, HomeView, RangeInput, RangeView, Reading, ReadingInput,
  ReadingKind, RedFlagAnswer, TrendView, UsualRange, Visit, VisitInput, VitaConfigItem, VitaPerson, VitaPersonInput, VitaProfileInput, VitaProfileView, VitaQuickStartView,
} from '@/types/vita'

const person = (id: string) => `/api/vita/people/${encodeURIComponent(id)}`

/**
 * Vita's Health tab (spec 5.12). Every /api/vita path answers 404 while the server's health switch
 * is off; the household people list (/api/people) works whatever the finance and health switches say.
 */
export const vitaApi = {
  status: () => api.get<{ enabled: boolean; openOn?: string | null }>('/api/vita/status'),

  people: () => api.get<VitaPerson[]>('/api/people?forHealth=true'),
  addPerson: (body: VitaPersonInput) => api.post<VitaPerson>('/api/people', { type: 'individual', ...body }),

  config: () => api.get<VitaConfigItem[]>('/api/vita/config'),
  putConfig: (key: string, value: JsonValue) => api.put<VitaConfigItem>(`/api/vita/config/${encodeURIComponent(key)}`, { value }),
  resetConfig: (key: string) => api.delete<VitaConfigItem>(`/api/vita/config/${encodeURIComponent(key)}`),

  quickStart: (personId: string) => api.get<VitaQuickStartView>(`/api/vita/quick-start?personId=${encodeURIComponent(personId)}`),
  saveQuickStart: (personId: string, step: QuickStartStep, answers: Record<string, unknown>) =>
    api.post<VitaQuickStartView>('/api/vita/quick-start', { personId, step, answers }),
  skipQuickStart: (personId: string, step: QuickStartStep) =>
    api.post<VitaQuickStartView>('/api/vita/quick-start', { personId, step, skip: true }),

  profile: (personId: string) => api.get<VitaProfileView>(`${person(personId)}/profile`),
  saveProfile: (personId: string, body: VitaProfileInput) => api.put<VitaProfileView>(`${person(personId)}/profile`, body),
  emergencyCard: (personId: string) => api.get<EmergencyCard>(`${person(personId)}/emergency-card`),

  contacts: (personId: string) => api.get<EmergencyContact[]>(`${person(personId)}/emergency-contacts`),
  addContact: (personId: string, body: EmergencyContactInput) => api.post<EmergencyContact>(`${person(personId)}/emergency-contacts`, body),
  updateContact: (personId: string, id: string, body: Partial<EmergencyContactInput>) =>
    api.put<EmergencyContact>(`${person(personId)}/emergency-contacts/${id}`, body),
  removeContact: (personId: string, id: string) => api.delete<void>(`${person(personId)}/emergency-contacts/${id}`),

  conditions: (personId: string) => api.get<Condition[]>(`${person(personId)}/conditions`),
  addCondition: (personId: string, body: ConditionInput) => api.post<Condition>(`${person(personId)}/conditions`, body),
  updateCondition: (personId: string, id: string, body: ConditionInput) => api.put<Condition>(`${person(personId)}/conditions/${id}`, body),
  removeCondition: (personId: string, id: string) => api.delete<void>(`${person(personId)}/conditions/${id}`),

  allergies: (personId: string) => api.get<Allergy[]>(`${person(personId)}/allergies`),
  addAllergy: (personId: string, body: AllergyInput) => api.post<Allergy>(`${person(personId)}/allergies`, body),
  updateAllergy: (personId: string, id: string, body: AllergyInput) => api.put<Allergy>(`${person(personId)}/allergies/${id}`, body),
  removeAllergy: (personId: string, id: string) => api.delete<void>(`${person(personId)}/allergies/${id}`),

  measurements: (personId: string, kind?: 'weight') =>
    api.get<Measurement[]>(`${person(personId)}/measurements${kind ? `?kind=${kind}` : ''}`),
  addMeasurement: (personId: string, body: MeasurementInput) => api.post<Measurement>(`${person(personId)}/measurements`, body),
  updateMeasurement: (personId: string, id: string, body: Partial<MeasurementInput>) =>
    api.put<Measurement>(`${person(personId)}/measurements/${id}`, body),
  removeMeasurement: (personId: string, id: string) => api.delete<void>(`${person(personId)}/measurements/${id}`),
  moveMeasurement: (personId: string, id: string, toPersonId: string) =>
    api.post<Measurement>(`${person(personId)}/measurements/${id}/move`, { toPersonId }),

  /** Any kind of reading. A 409 says "Already saved. Add anyway?"; `confirmDuplicate` adds it anyway. */
  readings: (personId: string, kind: ReadingKind) => api.get<Reading[]>(`${person(personId)}/measurements?kind=${kind}`),
  addReading: (personId: string, body: ReadingInput, opts: { secondOf?: string; confirmDuplicate?: boolean } = {}) => {
    const q = new URLSearchParams()
    if (opts.secondOf) q.set('secondOf', opts.secondOf)
    if (opts.confirmDuplicate) q.set('confirmDuplicate', 'true')
    const qs = q.toString()
    return api.post<Reading>(`${person(personId)}/measurements${qs ? `?${qs}` : ''}`, body)
  },
  updateReading: (personId: string, id: string, body: Partial<ReadingInput>) => api.put<Reading>(`${person(personId)}/measurements/${id}`, body),
  /** "Is 182/112 right?" and the rule's question; a yes alerts the manager. */
  answerRedFlag: (personId: string, id: string, right: boolean, signsNow?: boolean | null) =>
    api.post<RedFlagAnswer>(`${person(personId)}/measurements/${id}/red-flag`, { right, signsNow: signsNow ?? null }),
  trend: (personId: string, kind: ReadingKind, days: number, context?: string | null) =>
    api.get<TrendView>(`${person(personId)}/measurements/trend?kind=${kind}&days=${days}${context ? `&context=${context}` : ''}`),

  home: (personId: string) => api.get<HomeView>(`${person(personId)}/home`),
  ranges: (personId: string) => api.get<RangeView[]>(`${person(personId)}/ranges`),
  putRange: (personId: string, body: RangeInput) => api.put<UsualRange>(`${person(personId)}/ranges`, body),
  removeRange: (personId: string, id: string) => api.delete<void>(`${person(personId)}/ranges/${id}`),
  conditionSummary: (personId: string, id: string) => api.get<ConditionSummary>(`${person(personId)}/conditions/${id}/summary`),

  doctors: () => api.get<Doctor[]>('/api/vita/doctors'),
  addDoctor: (body: DoctorInput) => api.post<Doctor>('/api/vita/doctors', body),
  updateDoctor: (id: string, body: DoctorInput) => api.put<Doctor>(`/api/vita/doctors/${id}`, body),
  removeDoctor: (id: string) => api.delete<void>(`/api/vita/doctors/${id}`),

  visits: (personId: string) => api.get<Visit[]>(`${person(personId)}/visits`),
  addVisit: (personId: string, body: VisitInput) => api.post<Visit>(`${person(personId)}/visits`, body),
  updateVisit: (personId: string, id: string, body: VisitInput) => api.put<Visit>(`${person(personId)}/visits/${id}`, body),
  removeVisit: (personId: string, id: string) => api.delete<void>(`${person(personId)}/visits/${id}`),
}
