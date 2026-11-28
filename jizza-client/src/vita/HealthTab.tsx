import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Activity, BedDouble, ChevronDown, Droplet, ClipboardList, FileText, FlaskConical, FolderInput, HeartPulse, Home as HomeIcon, IdCard, MessageSquare, NotebookPen,
  Pill, Plus, Scale, Settings as SettingsIcon, Share2, ShieldPlus, Stethoscope, Syringe, Target, TriangleAlert, UserPlus, type LucideIcon,
} from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { ErrorBoundary } from '@/components/ui/ErrorBoundary'
import { fieldClass } from '@/components/ui/SlideOver'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { setChatDraft } from '@/jizza/chatDraft'
import { useAssistantStore } from '@/jizza/assistantStore'
import type { AgentDto } from '@/types'
import type { ReadingKind, ShareSection, VitaPersonInput } from '@/types/vita'
import { AskBox, ClosedBanner, Disclaimer, NotYet } from './components/Common'
import { errorText, useVitaPeople } from './data'
import { PERSON_KINDS, personName } from './labels'
import { BUILT_VIEWS, HEALTH_VIEWS, findView } from './nav'
import { vitaKeys } from './queryKeys'
import { Allergies } from './screens/Allergies'
import { ConditionPage } from './screens/ConditionPage'
import { Conditions } from './screens/Conditions'
import { EmergencyCard } from './screens/EmergencyCard'
import { Home } from './screens/Home'
import { Profile } from './screens/Profile'
import { QuickStart } from './screens/QuickStart'
import { Settings } from './screens/Settings'
import { Medicines } from './screens/Medicines'
import { Documents } from './screens/Documents'
import { DocumentReview } from './screens/DocumentReview'
import { Labs } from './screens/Labs'
import { Connect } from './screens/Connect'
import { SurgeriesAndStays, Vaccinations } from './screens/History'
import { SummaryPage, VisitPrepPage } from './screens/VisitPrep'
import { LabReportPage } from './screens/LabReport'
import { Visits } from './screens/Visits'
import { Vitals } from './screens/Vitals'
import { isReadingKind } from './readings'
import { useHealthScope } from './useHealthScope'
import { splitPeople } from './people'
import { Sharing } from './screens/Sharing'
import { Insurance } from './screens/Insurance'
import { Journal } from './screens/Journal'
import { Goals } from './screens/Goals'
import { Activity as ActivityView } from './screens/Activity'
import { ShareContext, VIEW_SECTION, accessWords, canEdit, scopeOf, useSharedWithMe, viewOpens, type ShareScope } from './share'

const VIEW_ICONS: Record<string, LucideIcon> = {
  home: HomeIcon, conditions: HeartPulse, medicines: Pill, vitals: Activity, visits: Stethoscope, labs: FlaskConical, documents: FileText,
  vaccinations: Syringe, surgeries: BedDouble, allergies: TriangleAlert, sharing: Share2, insurance: ShieldPlus, journal: NotebookPen,
  goals: Target, activity: Activity, emergency: IdCard, connect: FolderInput, settings: SettingsIcon,
}

export { splitPeople }

/**
 * Vita's Health tab (7.1): a sticky bar with the person switcher, the Add menu and "Ask Vita", the
 * sub-navigation (left on desktop, a scrolling chip row on a phone), then the current view. Scope
 * lives in the URL. Views from later updates say what they will hold.
 */
export function HealthTab({ agent }: { agent: AgentDto }) {
  const [scope, setScope] = useHealthScope()
  const { data: people, isLoading, error, refetch } = useVitaPeople()
  const sharedWithMe = useSharedWithMe(!!people)
  const [adding, setAdding] = useState(false)
  const [addReading, setAddReading] = useState<ReadingKind | null>(null)
  const [addVisit, setAddVisit] = useState(false)
  const [addMedicine, setAddMedicine] = useState(false)
  const [addDocument, setAddDocument] = useState(false)
  const navigate = useNavigate()

  // Wait for the shared records too, so a shared person's link never flashes the caller's own record.
  if (isLoading || sharedWithMe.isLoading) return <SkeletonRows rows={4} />
  if (error || !people) return <LoadFailed text={`Could not open ${agent.name}’s Health tab.`} onRetry={() => refetch()} />

  const { open, past } = splitPeople(people)
  const all = [...open, ...past]
  const shared = sharedWithMe.data ?? []
  if (!all.length && !shared.length) {
    return <Panel title="No one here yet"><p className="text-sm text-gray-500 dark:text-gray-400">Your own record appears here once your account is set up. Try again in a moment.</p></Panel>
  }
  // A record shared with the caller as the person themself opens first: it is "You" (5.10).
  const sharedEntry = shared.find(e => e.personId === scope.person) ?? (scope.person ? undefined : shared.find(e => e.asSelf))
  const own = sharedEntry ? undefined : all.find(p => p.id === scope.person) ?? open[0] ?? all[0]
  const person = own ?? { id: sharedEntry!.personId, displayName: sharedEntry!.personName, kind: sharedEntry!.asSelf ? 'self' : 'other', status: 'active' }
  const share: ShareScope = scopeOf(sharedEntry, person.id)
  const name = sharedEntry ? (sharedEntry.asSelf ? 'You' : sharedEntry.personName) : personName(person)
  const closed = person.status !== 'active'
  const view = findView(scope.view)
  const opens = viewOpens(share, view.id)
  /** A view takes new entries on one's own open record, or on a shared section with Edit. */
  const ro = (id: string) => closed || !canEdit(share, VIEW_SECTION[id])
  const firstOwn = sharedEntry || shared.some(e => e.asSelf) ? undefined : open[0]?.id
  const go = (id: string) => setScope({ view: id, condition: null, doc: null, report: null, test: null, visit: null })
  const openPrep = (visitId: string) => setScope({ view: 'visit-prep', visit: visitId })
  const openSummary = (conditionId?: string | null) => setScope({ view: 'summary', condition: conditionId ?? null })
  const openReport = (id: string) => setScope({ view: 'lab', report: id, test: null })
  const openTest = (code: string | null) => setScope({ view: 'labs', report: null, test: code })
  const openDocument = (id: string) => setScope({ view: 'document', doc: id })
  const openCondition = (id: string) => setScope({ view: 'condition', condition: id })
  const askVita = () => {
    setChatDraft(`@${agent.name} ${view.ask}`)
    useAssistantStore.getState().newConversation()
    navigate('/chat')
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="sticky top-0 z-10 -mx-1 flex flex-wrap items-center gap-2 border-b border-gray-200 bg-gray-50/95 px-1 pb-2 pt-1 backdrop-blur dark:border-gray-800 dark:bg-gray-950/95">
        <label className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
          Person
          <select aria-label="Person" className={`${fieldClass} !w-auto !py-1 text-sm`} value={person.id}
            onChange={e => (e.target.value === '__add' ? setAdding(true) : setScope({ person: e.target.value === firstOwn ? null : e.target.value }))}>
            {shared.filter(e => e.asSelf).map(e => <option key={e.personId} value={e.personId}>You (shared)</option>)}
            {open.map(p => <option key={p.id} value={p.id}>{p.kind === 'self' && shared.some(e => e.asSelf) ? 'Your own record' : personName(p)}</option>)}
            {shared.some(e => !e.asSelf) && (
              <optgroup label="Shared with you">
                {shared.filter(e => !e.asSelf).map(e => <option key={e.personId} value={e.personId}>{e.personName} (shared)</option>)}
              </optgroup>
            )}
            {past.length > 0 && (
              <optgroup label="Past records">
                {past.map(p => <option key={p.id} value={p.id}>{p.displayName} (closed)</option>)}
              </optgroup>
            )}
            <option value="__add">+ Add a person…</option>
          </select>
        </label>
        <span className="ml-auto flex items-center gap-1">
          {!closed && <AddMenu scope={share} onReading={k => { go('vitals'); setAddReading(k) }} onVisit={() => { go('visits'); setAddVisit(true) }} onMedicine={() => { go('medicines'); setAddMedicine(true) }} onDocument={() => { go('documents'); setAddDocument(true) }}
            onCondition={() => go('conditions')} onAllergy={() => go('allergies')} onVaccination={() => go('vaccinations')} onStay={() => go('surgeries')} />}
          <button type="button" onClick={askVita} title={`Ask ${agent.name} about this screen`}
            className="inline-flex items-center gap-1 rounded-lg border border-gray-200 px-2 py-1 text-xs text-gray-700 hover:bg-gray-100 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800">
            <MessageSquare className="h-3.5 w-3.5" aria-hidden /> Ask {agent.name}
          </button>
        </span>
      </div>

      {share.shared && <SharedBanner scope={share} name={name} />}
      {adding && <AddPerson onDone={id => { setAdding(false); if (id) setScope({ person: id, view: 'quick-start' }) }} />}

      <div className="flex flex-col gap-4 md:flex-row md:items-start">
        <nav aria-label="Health sections"
          className="-mx-1 flex gap-1 overflow-x-auto px-1 pb-1 md:sticky md:top-14 md:mx-0 md:w-48 md:shrink-0 md:flex-col md:overflow-visible md:px-0">
          {HEALTH_VIEWS.filter(v => !v.hidden && viewOpens(share, v.id)).map(v => {
            const Icon = VIEW_ICONS[v.id]
            const active = view.id === v.id || (view.hidden && v.id === ({ profile: 'emergency', condition: 'conditions', document: 'documents', lab: 'labs', 'visit-prep': 'visits', summary: 'visits' } as Record<string, string>)[view.id]) || (view.hidden && !['profile', 'condition', 'document', 'lab', 'visit-prep', 'summary'].includes(view.id) && v.id === 'home')
            return (
              <span key={v.id} className="contents">
                {v.groupStart && <span aria-hidden className="hidden md:my-1 md:block md:border-t md:border-gray-200 dark:md:border-gray-800" />}
                <button type="button" onClick={() => go(v.id)} aria-current={view.id === v.id ? 'page' : undefined}
                  className={`group inline-flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-2.5 py-1.5 text-sm transition-colors ${active
                    ? 'bg-rose-50 font-medium text-gray-900 ring-1 ring-inset ring-[rgb(var(--agent-health)/0.35)] dark:bg-rose-950/30 dark:text-gray-100'
                    : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-gray-100'}`}>
                  {Icon && <Icon className={`h-4 w-4 ${active ? 'text-[rgb(var(--agent-health))]' : 'text-gray-400 dark:text-gray-500'}`} aria-hidden />}
                  {v.label}
                  {!BUILT_VIEWS.has(v.id) && <span className="sr-only"> (coming later)</span>}
                </button>
              </span>
            )
          })}
        </nav>

        <div key={`${view.id}-${person.id}`} className="flex min-w-0 flex-1 flex-col gap-4 motion-safe:animate-fade-in-up">
          {closed && <ClosedBanner name={person.displayName} on={own?.statusOn} onExport={view.id === 'profile' ? undefined : () => go('profile')} />}
          <ShareContext.Provider value={share}>
          <ErrorBoundary>
            {!opens && (
              <Panel title="Not shared with you">
                <p className="text-sm text-gray-600 dark:text-gray-300">{share.managerEmail ?? 'The person who keeps this record'} has not shared {view.label.toLowerCase()} with you. Ask them to change it under Sharing.</p>
              </Panel>
            )}
            {opens && <>
            {(view.id === 'home' || (view.id === 'quick-start' && closed)) && (
              <Home personId={person.id} personName={name} readOnly={closed} onOpen={go} onOpenCondition={openCondition} addReading={null} onAddReadingDone={() => undefined} />
            )}
            {view.id === 'quick-start' && !closed && <QuickStart personId={person.id} personName={name} onDone={() => go('home')} />}
            {view.id === 'conditions' && <Conditions personId={person.id} readOnly={ro('conditions')} onOpenCondition={openCondition} />}
            {view.id === 'condition' && <ConditionPage personId={person.id} conditionId={scope.condition} readOnly={ro('conditions')} onBack={() => go('conditions')} onOpen={go} onOpenTest={openTest} onSummary={() => openSummary(scope.condition)} />}
            {view.id === 'vitals' && (
              <Vitals personId={person.id} readOnly={ro('vitals')} adding={addReading} onAddDone={() => setAddReading(null)} onOpenCard={() => go('emergency')}
                initialKind={isReadingKind(scope.kind) ? scope.kind : undefined} />
            )}
            {view.id === 'medicines' && <Medicines personId={person.id} readOnly={ro('medicines')} adding={addMedicine} onAddDone={() => setAddMedicine(false)} />}
            {view.id === 'visits' && <Visits personId={person.id} personName={name} readOnly={ro('visits')} adding={addVisit} onAddDone={() => setAddVisit(false)} onPrepare={openPrep} onSummary={() => openSummary(null)} />}
            {view.id === 'visit-prep' && <VisitPrepPage personId={person.id} visitId={scope.visit} onBack={() => go('visits')} />}
            {view.id === 'summary' && <SummaryPage personId={person.id} conditionId={scope.condition} onBack={() => (scope.condition ? openCondition(scope.condition) : go('visits'))} backLabel={scope.condition ? 'Back to the condition' : 'Doctors and visits'} />}
            {view.id === 'vaccinations' && <Vaccinations personId={person.id} readOnly={ro('vaccinations')} />}
            {view.id === 'surgeries' && <SurgeriesAndStays personId={person.id} readOnly={ro('surgeries')} />}
            {view.id === 'connect' && <Connect personId={person.id} readOnly={closed} onOpen={go} onReview={id => setScope({ view: 'document', doc: id })} />}
            {view.id === 'labs' && (
              <Labs personId={person.id} readOnly={ro('labs')} test={scope.test} onOpenReport={openReport} onOpenTest={openTest} onUpload={() => { go('documents'); setAddDocument(true) }} />
            )}
            {view.id === 'lab' && <LabReportPage personId={person.id} reportId={scope.report} readOnly={ro('lab')} onBack={() => go('labs')} onOpenTest={openTest} />}
            {view.id === 'documents' && (
              <Documents personId={person.id} readOnly={ro('documents')} onReview={openDocument} uploading={addDocument} onUploadDone={() => setAddDocument(false)} />
            )}
            {view.id === 'document' && scope.doc && (
              <DocumentReview personId={person.id} documentId={scope.doc} readOnly={ro('document')} onBack={() => go('documents')} onOpen={go} onOpenReport={openReport} />
            )}
            {view.id === 'allergies' && <Allergies personId={person.id} readOnly={ro('allergies')} />}
            {view.id === 'emergency' && <EmergencyCard personId={person.id} readOnly={ro('emergency')} onOpen={go} />}
            {view.id === 'profile' && <Profile personId={person.id} readOnly={ro('profile')} onOpen={go} />}
            {view.id === 'settings' && <Settings onOpen={go} />}
            {view.id === 'insurance' && <Insurance personId={person.id} personName={name} readOnly={closed} onOpenDocument={openDocument} />}
            {view.id === 'journal' && <Journal personId={person.id} personName={name} readOnly={ro('journal')} />}
            {view.id === 'goals' && <Goals personId={person.id} personName={name} readOnly={ro('goals')} />}
            {view.id === 'activity' && <ActivityView personId={person.id} personName={name} onOpen={go} />}
            {view.id === 'sharing' && <Sharing personId={person.id} personName={name} kind={person.kind} closed={closed} />}
            {!BUILT_VIEWS.has(view.id) && <NotYet view={view} />}
            </>}
          </ErrorBoundary>
          </ShareContext.Provider>
          {view.id !== 'quick-start' && <AskBox agentName={agent.name} view={view} personName={name} />}
          <Disclaimer />
        </div>
      </div>
    </div>
  )
}

/** The shared view's banner (7.19): whose record this is and what the caller may do with it. */
function SharedBanner({ scope, name }: { scope: ShareScope; name: string }) {
  const whose = scope.asSelf ? 'Your record' : `${name}’s record`
  return (
    <p role="status" className="flex flex-wrap items-center gap-2 rounded-lg border border-[rgb(var(--agent-health)/0.3)] bg-rose-50 px-3 py-2 text-sm text-gray-800 dark:bg-rose-950/30 dark:text-gray-100">
      <Share2 className="h-4 w-4 shrink-0 text-[rgb(var(--agent-health))]" aria-hidden />
      <span className="rounded-full bg-white px-1.5 py-0.5 text-[11px] font-medium text-gray-700 ring-1 ring-inset ring-gray-300 dark:bg-gray-900 dark:text-gray-200 dark:ring-gray-600">Shared</span>
      <span>{whose} is kept by {scope.managerEmail ?? 'another member of your Family'}. You can {accessWords(scope.sections)}. Only they can delete entries or change sharing.</span>
    </p>
  )
}

/** "Add": what can be added today, and what comes later, never a dead end. */
function AddMenu({ scope, onReading, onVisit, onMedicine, onDocument, onCondition, onAllergy, onVaccination, onStay }: {
  scope: ShareScope
  onReading: (kind: ReadingKind) => void; onVisit: () => void; onMedicine: () => void; onDocument: () => void; onCondition: () => void; onAllergy: () => void
  onVaccination: () => void; onStay: () => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])
  // On a shared record only the sections shared with Edit take new entries.
  const item = (label: string, Icon: LucideIcon, onClick?: () => void, later?: string, section?: ShareSection) => (section && !canEdit(scope, section)) ? null : (
    <button type="button" role="menuitem" disabled={!onClick} onClick={() => { setOpen(false); onClick?.() }}
      className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-default disabled:text-gray-400 disabled:hover:bg-transparent dark:text-gray-200 dark:hover:bg-gray-800 dark:disabled:text-gray-500">
      <Icon className="h-4 w-4" aria-hidden />{label}{later && <span className="ml-auto text-xs">{later}</span>}
    </button>
  )
  return (
    <div ref={ref} className="relative">
      <button type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}
        className="inline-flex items-center gap-1 rounded-lg bg-[rgb(var(--agent-health))] px-2.5 py-1 text-xs font-medium text-white hover:opacity-90">
        <Plus className="h-3.5 w-3.5" aria-hidden />Add<ChevronDown className="h-3 w-3" aria-hidden />
      </button>
      {open && (
        <div role="menu" className="absolute right-0 z-20 mt-1 w-56 overflow-hidden rounded-xl border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-900">
          {item('Medicine', Pill, onMedicine, undefined, 'medicines')}
          {item('Blood pressure', HeartPulse, () => onReading('bp'), undefined, 'vitals')}
          {item('Sugar', Droplet, () => onReading('glucose'), undefined, 'vitals')}
          {item('Weight', Scale, () => onReading('weight'), undefined, 'vitals')}
          {item('Other reading', Activity, () => onReading('heart_rate'), undefined, 'vitals')}
          {item('Visit', Stethoscope, onVisit, undefined, 'visits')}
          {item('Condition', HeartPulse, onCondition, undefined, 'conditions')}
          {item('Allergy', TriangleAlert, onAllergy, undefined, 'allergies')}
          {item('Vaccination', Syringe, onVaccination, undefined, 'vaccinations')}
          {item('Surgery or stay', BedDouble, onStay, undefined, 'events')}
          {item('Document or report', ClipboardList, onDocument, undefined, 'documents')}
        </div>
      )}
    </div>
  )
}

function AddPerson({ onDone }: { onDone: (id: string | null) => void }) {
  const qc = useQueryClient()
  const [v, setV] = useState<VitaPersonInput>({ displayName: '', kind: 'parent', dob: '' })
  const save = useMutation({
    mutationFn: () => vitaApi.addPerson({ displayName: v.displayName.trim(), kind: v.kind, dob: v.dob || null, type: v.kind === 'child' ? 'minor' : 'individual' }),
    onSuccess: p => { qc.invalidateQueries({ queryKey: vitaKeys.people }); onDone(p.id) },
  })
  return (
    <Panel title={<><UserPlus className="h-4 w-4" aria-hidden />Add a person you look after</>}>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Someone without their own login here, such as a parent or a child. Kai sees the same list of people.</p>
      <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-3">
          <TextField label="Name" value={v.displayName} maxLength={80} placeholder="Mother" onChange={displayName => setV({ ...v, displayName })} />
          <SelectField label="Who" value={v.kind} onChange={kind => setV({ ...v, kind })}
            options={(Object.keys(PERSON_KINDS) as VitaPersonInput['kind'][]).map(k => ({ value: k, label: PERSON_KINDS[k] }))} />
          <TextField label="Date of birth (optional)" type="date" value={v.dob ?? ''} onChange={dob => setV({ ...v, dob })} />
        </div>
        <div className="flex gap-2">
          <Button size="sm" type="submit" loading={save.isPending} disabled={!v.displayName.trim()}>Add and start Quick start</Button>
          <Button size="sm" variant="ghost" type="button" onClick={() => onDone(null)}>Cancel</Button>
        </div>
        {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not add the person.')}</p>}
      </form>
    </Panel>
  )
}
