import { api } from '@/lib/api'
import type { JsonValue } from '@/types/finance'
import type {
  Allergy, AllergyInput, Condition, ConditionInput, EmergencyCard, EmergencyContact, EmergencyContactInput, Measurement,
  MeasurementInput, QuickStartStep, ConditionSummary, Doctor, DoctorInput, HomeView, RangeInput, RangeView, Reading, ReadingInput,
  ReadingKind, RedFlagAnswer, AccessList, CareNote, HealthConsent, ShareInput, SharedWithMe, SharesView, HealthEvent, HealthEventInput, HealthSummary, MailCheckResult, MailSource, MailSourceInput, MailSourcesView, Vaccination, VaccinationInput, VisitPrep, CheckInput, LabReport, LabReportInput, LabReportView, LabTest, LabTrend, VitaCheck, DocumentDraft, DocumentInput, DraftView, VitaDocument, Adherence, DoseAction, DoseSlot, Medicine, MedicineChange, MedicineEvent, MedicineInput, ReminderInput, VitaReminder, TrendView, UsualRange, Visit, VisitInput, VitaConfigItem, VitaPerson, VitaPersonInput, VitaProfileInput, VitaProfileView, VitaQuickStartView,
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

  medicines: (personId: string) => api.get<Medicine[]>(`${person(personId)}/medicines`),
  addMedicine: (personId: string, body: MedicineInput) => api.post<Medicine>(`${person(personId)}/medicines`, body),
  /** Corrects details; the dose and times change only through `changeMedicine`. */
  updateMedicine: (personId: string, id: string, body: MedicineInput) => api.put<Medicine>(`${person(personId)}/medicines/${id}`, body),
  changeMedicine: (personId: string, id: string, body: MedicineChange) => api.post<Medicine>(`${person(personId)}/medicines/${id}/change`, body),
  pauseMedicine: (personId: string, id: string, reason?: string | null) => api.post<Medicine>(`${person(personId)}/medicines/${id}/pause`, { reason: reason ?? null }),
  resumeMedicine: (personId: string, id: string) => api.post<Medicine>(`${person(personId)}/medicines/${id}/resume`, {}),
  stopMedicine: (personId: string, id: string, reason?: string | null) => api.post<Medicine>(`${person(personId)}/medicines/${id}/stop`, { reason: reason ?? null }),
  refillMedicine: (personId: string, id: string, units?: number | null) =>
    api.post<Medicine>(`${person(personId)}/medicines/${id}/refill`, units == null ? {} : { units }),
  medicineEvents: (personId: string, id: string) => api.get<MedicineEvent[]>(`${person(personId)}/medicines/${id}/events`),

  /** Time slots from `from` to `to` (local dates; default today). */
  doses: (personId: string, from?: string, to?: string) => {
    const q = new URLSearchParams()
    if (from) q.set('from', from)
    if (to) q.set('to', to)
    const qs = q.toString()
    return api.get<{ zone: string; slots: DoseSlot[] }>(`${person(personId)}/doses${qs ? `?${qs}` : ''}`)
  },
  actSlot: (personId: string, dueAt: string, body: DoseAction) =>
    api.post<DoseSlot>(`${person(personId)}/slots/${encodeURIComponent(dueAt)}/act`, body),
  actDose: (doseId: string, body: DoseAction) => api.post<DoseSlot>(`/api/vita/doses/${doseId}/act`, body),
  adherence: (personId: string, from: string, to: string) => api.get<Adherence>(`${person(personId)}/adherence?from=${from}&to=${to}`),

  reminders: (personId: string) => api.get<VitaReminder[]>(`${person(personId)}/reminders`),
  addReminder: (personId: string, body: ReminderInput) => api.post<VitaReminder>(`${person(personId)}/reminders`, body),
  updateReminder: (personId: string, id: string, body: ReminderInput) => api.put<VitaReminder>(`${person(personId)}/reminders/${id}`, body),
  removeReminder: (personId: string, id: string) => api.delete<void>(`${person(personId)}/reminders/${id}`),

  documents: (personId: string) => api.get<VitaDocument[]>(`${person(personId)}/documents`),
  /** A PDF or a photo; Vita reads it only when asked, and saves nothing it read until confirmed. */
  uploadDocument: (personId: string, file: File, meta: DocumentInput = {}) => {
    const form = new FormData()
    form.append('file', file)
    for (const [k, v] of Object.entries(meta)) if (v != null && v !== '') form.append(k, String(v))
    return api.upload<VitaDocument>(`${person(personId)}/documents`, form)
  },
  document: (id: string) => api.get<VitaDocument>(`/api/vita/documents/${id}`),
  updateDocument: (id: string, body: DocumentInput) => api.put<VitaDocument>(`/api/vita/documents/${id}`, body),
  removeDocument: (id: string) => api.delete<void>(`/api/vita/documents/${id}`),
  moveDocument: (id: string, toPersonId: string) => api.post<VitaDocument>(`/api/vita/documents/${id}/move`, { toPersonId }),
  /** The stored file, for the preview beside the review; same-origin, so the session cookie applies. */
  documentFileUrl: (id: string) => `/api/vita/documents/${id}/file`,
  extractDocument: (id: string, password?: string) => api.post<DraftView>(`/api/vita/documents/${id}/extract`, password ? { password } : {}),
  draft: (id: string) => api.get<DraftView>(`/api/vita/documents/${id}/draft`),
  discardDraft: (id: string) => api.delete<VitaDocument>(`/api/vita/documents/${id}/draft`),
  confirmDraft: (id: string, draft: DocumentDraft, opts: { patientConfirmed?: boolean; doctorId?: string | null } = {}) =>
    api.post<{ document: VitaDocument; saved: { medicines: string[]; labReport?: string | null } }>(`/api/vita/documents/${id}/confirm`, { draft, ...opts }),
  // Lab results and checks (7b)
  labTests: () => api.get<LabTest[]>('/api/vita/lab-tests'),
  labs: (personId: string) => api.get<LabReport[]>(`${person(personId)}/labs`),
  lab: (id: string) => api.get<LabReportView>(`/api/vita/labs/${id}`),
  updateLab: (id: string, body: LabReportInput) => api.put<LabReportView>(`/api/vita/labs/${id}`, body),
  removeLab: (id: string) => api.delete<void>(`/api/vita/labs/${id}`),
  labTrend: (personId: string, test: string) => api.get<LabTrend>(`${person(personId)}/labs/trend?test=${encodeURIComponent(test)}`),
  checks: (personId: string) => api.get<VitaCheck[]>(`${person(personId)}/checks`),
  addCheck: (personId: string, body: CheckInput) => api.post<VitaCheck>(`${person(personId)}/checks`, body),
  updateCheck: (personId: string, id: string, body: CheckInput) => api.put<VitaCheck>(`${person(personId)}/checks/${id}`, body),
  removeCheck: (personId: string, id: string) => api.delete<void>(`${person(personId)}/checks/${id}`),
  // Visit prep, summary, vaccinations and events (7c)
  prepareVisit: (visitId: string) => api.post<VisitPrep>(`/api/vita/visits/${visitId}/prepare`, {}),
  summary: (personId: string, opts: { condition?: string | null; includePrivate?: boolean } = {}) => {
    const q = new URLSearchParams()
    if (opts.condition) q.set('condition', opts.condition)
    if (opts.includePrivate) q.set('includePrivate', 'true')
    const qs = q.toString()
    return api.get<HealthSummary>(`${person(personId)}/summary${qs ? `?${qs}` : ''}`)
  },
  vaccinations: (personId: string) => api.get<Vaccination[]>(`${person(personId)}/vaccinations`),
  addVaccination: (personId: string, body: VaccinationInput) => api.post<Vaccination>(`${person(personId)}/vaccinations`, body),
  updateVaccination: (personId: string, id: string, body: VaccinationInput) => api.put<Vaccination>(`${person(personId)}/vaccinations/${id}`, body),
  removeVaccination: (personId: string, id: string) => api.delete<void>(`${person(personId)}/vaccinations/${id}`),
  events: (personId: string) => api.get<HealthEvent[]>(`${person(personId)}/events`),
  addEvent: (personId: string, body: HealthEventInput) => api.post<HealthEvent>(`${person(personId)}/events`, body),
  updateEvent: (personId: string, id: string, body: HealthEventInput) => api.put<HealthEvent>(`${person(personId)}/events/${id}`, body),
  removeEvent: (personId: string, id: string) => api.delete<void>(`${person(personId)}/events/${id}`),
  // Mailbox sources (7d)
  mailSources: () => api.get<MailSourcesView>('/api/vita/mail-sources'),
  addMailSource: (body: MailSourceInput) => api.post<MailSource>('/api/vita/mail-sources', body),
  updateMailSource: (id: string, body: MailSourceInput) => api.put<MailSource>(`/api/vita/mail-sources/${id}`, body),
  removeMailSource: (id: string) => api.delete<void>(`/api/vita/mail-sources/${id}`),
  checkMailSource: (id: string) => api.post<MailCheckResult>(`/api/vita/mail-sources/${id}/check`, {}),

  // Family and sharing (8a)
  shares: (personId?: string) => api.get<SharesView>(`/api/vita/shares${personId ? `?personId=${encodeURIComponent(personId)}` : ''}`),
  /** Replaces what one member may see of one record. Any Edit needs a fresh step-up. */
  share: (body: ShareInput) => api.post<SharesView>('/api/vita/shares', body),
  endShare: (id: string) => api.delete<void>(`/api/vita/shares/${id}`),
  /** Ends everything one member was given on one record. */
  endShares: (personId: string, granteeUserId: string) =>
    api.delete<void>(`/api/vita/shares?personId=${encodeURIComponent(personId)}&granteeUserId=${encodeURIComponent(granteeUserId)}`),
  sharedWithMe: () => api.get<SharedWithMe[]>('/api/vita/shared-with-me'),
  accessList: (personId: string) => api.get<AccessList>(`${person(personId)}/access-list`),
  consents: (personId: string) => api.get<HealthConsent[]>(`${person(personId)}/consents`),
  recordConsent: (personId: string, body: { scope: 'view' | 'manage'; channel?: string | null }) =>
    api.post<HealthConsent>(`${person(personId)}/consents`, body),
  withdrawConsent: (personId: string, id: string) => api.delete<HealthConsent>(`${person(personId)}/consents/${id}`),
  careNotes: (personId: string) => api.get<CareNote[]>(`${person(personId)}/care-notes`),
  addCareNote: (personId: string, text: string) => api.post<CareNote>(`${person(personId)}/care-notes`, { text }),
  /** The doctors a shared record names (for the manager, every doctor). */
  personDoctors: (personId: string) => api.get<Doctor[]>(`${person(personId)}/doctors`),
  /** Every Vita row of the person. Needs a fresh step-up. */
  exportRecord: (personId: string) => api.get<Record<string, JsonValue>>(`${person(personId)}/export`),
  /** Deletes the person's health record for good. Needs a fresh step-up. */
  deleteRecord: (personId: string) => api.delete<{ deleted: number }>(`${person(personId)}/record`),
}
