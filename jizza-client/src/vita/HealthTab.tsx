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
import type { ReadingKind, VitaPersonInput } from '@/types/vita'
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
import { Visits } from './screens/Visits'
import { Vitals } from './screens/Vitals'
import { isReadingKind } from './readings'
import { useHealthScope } from './useHealthScope'
import { splitPeople } from './people'

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
  const [adding, setAdding] = useState(false)
  const [addReading, setAddReading] = useState<ReadingKind | null>(null)
  const [addVisit, setAddVisit] = useState(false)
  const navigate = useNavigate()

  if (isLoading) return <SkeletonRows rows={4} />
  if (error || !people) return <LoadFailed text={`Could not open ${agent.name}’s Health tab.`} onRetry={() => refetch()} />

  const { open, past } = splitPeople(people)
  const all = [...open, ...past]
  if (!all.length) {
    return <Panel title="No one here yet"><p className="text-sm text-gray-500 dark:text-gray-400">Your own record appears here once your account is set up. Try again in a moment.</p></Panel>
  }
  const person = all.find(p => p.id === scope.person) ?? open[0] ?? all[0]
  const name = personName(person)
  const closed = person.status !== 'active'
  const view = findView(scope.view)
  const go = (id: string) => setScope({ view: id, condition: null })
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
            onChange={e => (e.target.value === '__add' ? setAdding(true) : setScope({ person: e.target.value === open[0]?.id ? null : e.target.value }))}>
            {open.map(p => <option key={p.id} value={p.id}>{personName(p)}</option>)}
            {past.length > 0 && (
              <optgroup label="Past records">
                {past.map(p => <option key={p.id} value={p.id}>{p.displayName} (closed)</option>)}
              </optgroup>
            )}
            <option value="__add">+ Add a person…</option>
          </select>
        </label>
        <span className="ml-auto flex items-center gap-1">
          {!closed && <AddMenu onReading={k => { go('vitals'); setAddReading(k) }} onVisit={() => { go('visits'); setAddVisit(true) }}
            onCondition={() => go('conditions')} onAllergy={() => go('allergies')} />}
          <button type="button" onClick={askVita} title={`Ask ${agent.name} about this screen`}
            className="inline-flex items-center gap-1 rounded-lg border border-gray-200 px-2 py-1 text-xs text-gray-700 hover:bg-gray-100 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800">
            <MessageSquare className="h-3.5 w-3.5" aria-hidden /> Ask {agent.name}
          </button>
        </span>
      </div>

      {adding && <AddPerson onDone={id => { setAdding(false); if (id) setScope({ person: id, view: 'quick-start' }) }} />}

      <div className="flex flex-col gap-4 md:flex-row md:items-start">
        <nav aria-label="Health sections"
          className="-mx-1 flex gap-1 overflow-x-auto px-1 pb-1 md:sticky md:top-14 md:mx-0 md:w-48 md:shrink-0 md:flex-col md:overflow-visible md:px-0">
          {HEALTH_VIEWS.filter(v => !v.hidden).map(v => {
            const Icon = VIEW_ICONS[v.id]
            const active = view.id === v.id || (view.hidden && v.id === ({ profile: 'emergency', condition: 'conditions' } as Record<string, string>)[view.id]) || (view.hidden && !['profile', 'condition'].includes(view.id) && v.id === 'home')
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
          {closed && <ClosedBanner name={person.displayName} />}
          <ErrorBoundary>
            {(view.id === 'home' || (view.id === 'quick-start' && closed)) && (
              <Home personId={person.id} personName={name} readOnly={closed} onOpen={go} onOpenCondition={openCondition} addReading={null} onAddReadingDone={() => undefined} />
            )}
            {view.id === 'quick-start' && !closed && <QuickStart personId={person.id} personName={name} onDone={() => go('home')} />}
            {view.id === 'conditions' && <Conditions personId={person.id} readOnly={closed} onOpenCondition={openCondition} />}
            {view.id === 'condition' && <ConditionPage personId={person.id} conditionId={scope.condition} readOnly={closed} onBack={() => go('conditions')} onOpen={go} />}
            {view.id === 'vitals' && (
              <Vitals personId={person.id} readOnly={closed} adding={addReading} onAddDone={() => setAddReading(null)} onOpenCard={() => go('emergency')}
                initialKind={isReadingKind(scope.kind) ? scope.kind : undefined} />
            )}
            {view.id === 'visits' && <Visits personId={person.id} personName={name} readOnly={closed} adding={addVisit} onAddDone={() => setAddVisit(false)} />}
            {view.id === 'allergies' && <Allergies personId={person.id} readOnly={closed} />}
            {view.id === 'emergency' && <EmergencyCard personId={person.id} readOnly={closed} onOpen={go} />}
            {view.id === 'profile' && <Profile personId={person.id} readOnly={closed} onOpen={go} />}
            {view.id === 'settings' && <Settings onOpen={go} />}
            {!BUILT_VIEWS.has(view.id) && <NotYet view={view} />}
          </ErrorBoundary>
          {view.id !== 'quick-start' && <AskBox agentName={agent.name} view={view} personName={name} />}
          <Disclaimer />
        </div>
      </div>
    </div>
  )
}

/** "Add": what can be added today, and what comes later, never a dead end. */
function AddMenu({ onReading, onVisit, onCondition, onAllergy }: {
  onReading: (kind: ReadingKind) => void; onVisit: () => void; onCondition: () => void; onAllergy: () => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])
  const item = (label: string, Icon: LucideIcon, onClick?: () => void, later?: string) => (
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
          {item('Blood pressure', HeartPulse, () => onReading('bp'))}
          {item('Sugar', Droplet, () => onReading('glucose'))}
          {item('Weight', Scale, () => onReading('weight'))}
          {item('Other reading', Activity, () => onReading('heart_rate'))}
          {item('Visit', Stethoscope, onVisit)}
          {item('Condition', HeartPulse, onCondition)}
          {item('Allergy', TriangleAlert, onAllergy)}
          {item('Dose or medicine', Pill, undefined, 'Next update')}
          {item('Document', ClipboardList, undefined, 'Later')}
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
