import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, CalendarClock, ClipboardCheck, IdCard, Pill as PillIcon, Plus, RefreshCw } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { LoadFailed, Meter, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { Sparkline } from '@/portfolio/components/Sparkline'
import type { EmergencyCard, HomeRedFlag, HomeView, Reading, ReadingKind, RedFlag, VitaConfigItem } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { DoseSlotCard } from '../components/DoseSlotCard'
import { ReadingEntry } from '../components/ReadingEntry'
import { RedFlagBanner } from '../components/RedFlagBanner'
import { StatusBadge, TargetLabel } from '../components/Readings'
import { errorText, formatWhen, useUnits, useVitaConfig, type Units } from '../data'
import { BLOOD_GROUPS } from '../labels'
import { kindLabel, readingContext, showReading, showValue } from '../readings'
import { vitaKeys } from '../queryKeys'

/**
 * Home (7.2) for one person, from one server call: red flags first and full width, today's
 * medicines, the latest readings with their target labels, what is coming up, conditions and the
 * emergency card.
 */
export function Home({ personId, personName, readOnly, onOpen, onOpenCondition, addReading, onAddReadingDone }: {
  personId: string
  personName: string
  readOnly: boolean
  onOpen: (view: string) => void
  onOpenCondition: (id: string) => void
  /** The Add menu asked for a reading of this kind. */
  addReading: ReadingKind | null
  onAddReadingDone: () => void
}) {
  const home = useQuery({ queryKey: vitaKeys.home(personId), queryFn: () => vitaApi.home(personId) })
  const card = useQuery({ queryKey: vitaKeys.card(personId), queryFn: () => vitaApi.emergencyCard(personId) })
  const config = useVitaConfig()
  const units = useUnits()
  const [adding, setAdding] = useState(false)
  const entryKind = addReading ?? (adding ? 'bp' : null)
  const closeEntry = () => { setAdding(false); onAddReadingDone() }
  const h = home.data
  const progress = h?.quickStart
  const started = progress && (progress.completed.length + progress.skipped.length) > 0
  const you = personName === 'You'

  if (home.isLoading) return <SkeletonRows rows={5} />
  if (home.error || !h) return <LoadFailed text="Could not load Home." onRetry={() => home.refetch()} />

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title={`Today for ${you ? 'you' : personName}`} sub="Medicines, readings and what is coming up" />

      {h.redFlags.map(f => (
        <HomeFlag key={f.measurementId} personId={personId} personName={you ? undefined : personName} flag={f} reading={h.latest.find(r => r.id === f.measurementId)}
          card={card.data} config={config.data} units={units} readOnly={readOnly} onOpenCard={() => onOpen('emergency')} />
      ))}

      {progress && !progress.done && !readOnly && (
        <Panel title={<><ClipboardCheck className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />{started ? 'Carry on with Quick start' : 'Get started'}</>}>
          <p className="text-sm text-gray-600 dark:text-gray-300">
            {started
              ? `${progress.completed.length + progress.skipped.length} of ${progress.total} steps done. Pick up where you left off.`
              : `Answer eight short questions and Vita sets up ${you ? 'your' : `${personName}’s`} emergency card and record. You can skip any step and come back later.`}
          </p>
          {started && <Meter pct={((progress.completed.length + progress.skipped.length) / progress.total) * 100} label="Quick start progress" className="bg-[rgb(var(--agent-health))]" />}
          <div className="flex flex-wrap gap-2">
            <Button size="sm" onClick={() => onOpen('quick-start')}>{started ? 'Continue Quick start' : 'Start Quick start'}</Button>
          </div>
        </Panel>
      )}

      <Panel title={<><PillIcon className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Today’s medicines</>}
        action={<Button size="sm" variant="secondary" onClick={() => onOpen('medicines')}>Medicines</Button>}>
        {!h.slots.length && <p className="text-sm text-gray-500 dark:text-gray-400">No doses due today. Add medicines with their times and each time of day gets a card here.</p>}
        {h.slots.length > 0 && (
          <div className="grid gap-2 md:grid-cols-2">
            {h.slots.map(slot => <DoseSlotCard key={slot.dueAt} personId={personId} slot={slot} readOnly={readOnly} />)}
          </div>
        )}
      </Panel>

      <Panel title={<><Activity className="h-4 w-4 text-gray-400" aria-hidden />Latest readings</>}
        action={!readOnly && !entryKind && <Button size="sm" variant="secondary" onClick={() => setAdding(true)}><Plus className="h-3.5 w-3.5" aria-hidden />Add reading</Button>}>
        {entryKind && <ReadingEntry key={entryKind} personId={personId} kind={entryKind} onDone={closeEntry} onOpenCard={() => onOpen('emergency')} />}
        {!h.latest.length && !entryKind && <p className="text-sm text-gray-500 dark:text-gray-400">No readings yet. Add blood pressure, sugar, weight, pulse, oxygen or temperature.</p>}
        {h.latest.length > 0 && (
          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            {h.latest.map(r => <Tile key={r.id} personId={personId} r={r} units={units} />)}
          </div>
        )}
        {h.latest.length > 0 && <div><Button size="sm" variant="ghost" onClick={() => onOpen('vitals')}>All readings and charts</Button></div>}
      </Panel>

      <div className="grid gap-4 md:grid-cols-2">
        <Panel title={<><CalendarClock className="h-4 w-4 text-gray-400" aria-hidden />Coming up</>} action={<Button size="sm" variant="secondary" onClick={() => onOpen('visits')}>Visits</Button>}>
          <ComingUp home={h} onOpen={onOpen} />
        </Panel>
        <Panel title="Conditions" action={<Button size="sm" variant="secondary" onClick={() => onOpen('conditions')}>Open</Button>}>
          {!h.conditions.length && <p className="text-sm text-gray-500 dark:text-gray-400">No active conditions recorded.</p>}
          {h.conditions.length > 0 && (
            <ul className="flex flex-col gap-1.5">
              {h.conditions.map(c => (
                <li key={c.id}><button type="button" onClick={() => onOpenCondition(c.id)} className="text-left text-sm text-gray-800 underline-offset-2 hover:underline dark:text-gray-200">{c.name}</button></li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      <Panel title={<><IdCard className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Emergency card</>}
        action={<Button size="sm" variant="secondary" onClick={() => onOpen('emergency')}>Open card</Button>}>
        {card.isLoading && <SkeletonRows rows={1} />}
        {card.error && <LoadFailed text="Could not load the emergency card." onRetry={() => card.refetch()} />}
        {card.data && (
          <p className="text-sm text-gray-700 dark:text-gray-300">
            Blood group <b className="font-mono">{card.data.bloodGroup ? BLOOD_GROUPS[card.data.bloodGroup] : 'not set'}</b>
            {' · '}{card.data.allergies.length ? `${card.data.allergies.length} ${card.data.allergies.length === 1 ? 'allergy' : 'allergies'}` : card.data.allergiesNoneKnown ? 'no known allergies' : 'allergies not recorded'}
            {' · '}{card.data.emergencyContacts.length ? `${card.data.emergencyContacts.length} ${card.data.emergencyContacts.length === 1 ? 'contact' : 'contacts'}` : 'no contacts yet'}
          </p>
        )}
      </Panel>
    </div>
  )
}

const REMINDER_KINDS: Record<string, string> = {
  visit: 'Visit', vaccination: 'Vaccination', policy_renewal: 'Policy renewal', check_due: 'Check due', custom: 'Reminder', refill: 'Refill',
}

/** Visits, refills, renewals and checks that are due, soonest first. */
function ComingUp({ home: h, onOpen }: { home: HomeView; onOpen: (view: string) => void }) {
  const items = [
    ...(h.nextVisit ? [{ key: `v-${h.nextVisit.id}`, at: h.nextVisit.scheduledAt, words: `Visit${h.nextVisit.reason ? ` · ${h.nextVisit.reason}` : ''}` }] : []),
    ...h.reminders.map(r => ({ key: `r-${r.id}`, at: r.dueAt, words: [REMINDER_KINDS[r.kind] ?? 'Reminder', r.title].filter(Boolean).join(' · ') })),
  ].sort((a, b) => a.at.localeCompare(b.at))
  return (
    <div className="flex flex-col gap-2">
      {!!h.refillsDue && (
        <button type="button" onClick={() => onOpen('medicines')} className="flex w-fit items-center gap-1.5 text-left text-sm font-medium text-amber-800 underline-offset-2 hover:underline dark:text-amber-300">
          <RefreshCw className="h-4 w-4" aria-hidden />{h.refillsDue === 1 ? 'One medicine needs a refill' : `${h.refillsDue} medicines need a refill`}
        </button>
      )}
      {!items.length && !h.refillsDue && <p className="text-sm text-gray-500 dark:text-gray-400">Nothing planned. Visits, refills, renewals and checks that are due show here.</p>}
      {items.length > 0 && (
        <ul className="flex flex-col gap-1">
          {items.map(i => <li key={i.key} className="text-sm text-gray-800 dark:text-gray-100"><b className="font-medium">{formatWhen(i.at)}</b> · {i.words}</li>)}
        </ul>
      )}
    </div>
  )
}

/** One latest reading: value, time, status in words, who set the target, and the last month. */
function Tile({ personId, r, units }: { personId: string; r: Reading; units: Units }) {
  const trend = useQuery({ queryKey: vitaKeys.trend(personId, r.kind, 30, null), queryFn: () => vitaApi.trend(personId, r.kind, 30) })
  const series = (trend.data?.trend.days ?? []).flatMap(d => [...d.morning, ...d.evening]).sort((a, b) => a.takenAt.localeCompare(b.takenAt))
  const e = r.evaluation
  return (
    <div className="flex flex-col gap-1 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/60">
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="text-xs text-gray-500 dark:text-gray-400">{kindLabel(r.kind)}</p>
          <p className="font-mono text-xl font-semibold text-gray-900 dark:text-gray-100">{showReading(r, units)}</p>
        </div>
        <Sparkline values={series.map(p => p.value)} label={`${kindLabel(r.kind)}, last ${series.length} readings`} width={88} height={28}
          dates={series.map(p => formatWhen(p.takenAt))} format={v => showValue(r.kind, v, null, units).replace(/\/–/, '')} />
      </div>
      <p className="text-xs text-gray-500 dark:text-gray-400">{[formatWhen(r.takenAt), readingContext(r)].filter(Boolean).join(' · ')}</p>
      {r.kind === 'weight'
        ? <p className="text-xs text-gray-500 dark:text-gray-400">No target: weight has no range in Vita</p>
        : e && (
          <div className="flex flex-col items-start gap-1">
            <StatusBadge status={e.status} outside={e.outside} />
            {e.status !== 'not_set' && <TargetLabel source={e.rangeSource} label={e.label} needsVerification={e.needsVerification} range={e.range} />}
            {e.status === 'not_set' && <TargetLabel source={null} label={e.label} />}
          </div>
        )}
    </div>
  )
}

/**
 * A red flag on Home. A typed reading nobody has confirmed asks "Is 182/112 right?" first; once
 * confirmed it shows the level's banner with the server's wording, the emergency number and the
 * rule's question.
 */
function HomeFlag({ personId, personName, flag, reading, card, config, units, readOnly, onOpenCard }: {
  personId: string
  personName?: string
  flag: HomeRedFlag
  reading?: Reading
  card?: EmergencyCard
  config?: VitaConfigItem[]
  units: Units
  readOnly: boolean
  onOpenCard: () => void
}) {
  const qc = useQueryClient()
  const [help, setHelp] = useState<RedFlag | null>(null)
  const [wrong, setWrong] = useState(false)
  const answer = useMutation({
    mutationFn: ({ right, signsNow }: { right: boolean; signsNow?: boolean }) => vitaApi.answerRedFlag(personId, flag.measurementId, right, signsNow ?? null),
    onSuccess: (out, { right }) => {
      if (!right) setWrong(true)
      if (out.help) setHelp(out.help)
      qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
    },
  })
  const shown = reading ? showReading(reading, units) : kindLabel(flag.kind)

  if (wrong) {
    return (
      <div role="status" className="flex flex-wrap items-center gap-2 rounded-2xl border border-gray-200 bg-white p-3 text-sm dark:border-gray-800 dark:bg-gray-900">
        Nobody was alerted. Correct the reading or delete it in Vitals.
      </div>
    )
  }

  if (!flag.confirmed && !help) {
    return (
      <section role="alert" aria-label="Check the reading" className="flex flex-col gap-2 rounded-2xl border-2 border-red-500 bg-red-50 p-4 dark:bg-red-950/40">
        <p className="text-base font-semibold text-red-950 dark:text-red-50">Is {shown} right?</p>
        <p className="text-sm text-red-900 dark:text-red-100">{flag.wording} Nobody is alerted until you say it is right.</p>
        {!readOnly && (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="danger" loading={answer.isPending && answer.variables?.right} onClick={() => answer.mutate({ right: true })}>Yes, it’s right</Button>
            <Button size="sm" variant="secondary" loading={answer.isPending && !answer.variables?.right} onClick={() => answer.mutate({ right: false })}>No, I typed it wrong</Button>
          </div>
        )}
        {answer.error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{errorText(answer.error, 'Could not send the answer.')}</p>}
      </section>
    )
  }

  const rule = config?.find(i => i.key === `clinical.redflags.${flag.rule}`)
  const ruleValue = rule?.value && typeof rule.value === 'object' && !Array.isArray(rule.value) ? rule.value : null
  const fromHome: RedFlag = {
    level: flag.level,
    personName,
    wording: flag.wording,
    advice: flag.advice ?? undefined,
    question: flag.question ?? undefined,
    signs: Array.isArray(ruleValue?.signs) ? ruleValue.signs.map(String) : undefined,
    emergencyNumbers: card?.emergencyNumbers ?? null,
    emergencyNumbersNeedVerification: card?.emergencyNumbersNeedVerification,
    preferredHospital: card?.preferredHospital,
    preferredHospitalPhone: card?.preferredHospitalPhone,
    needsVerification: flag.needsVerification,
    rule: rule ? { key: rule.key, state: rule.state, label: rule.label } : undefined,
  }
  const f: RedFlag = { ...fromHome, ...(help ?? {}), reading: shown, personName }
  const raise = !readOnly && f.level === 'urgent' && f.question && f.signs?.length ? () => answer.mutate({ right: true, signsNow: true }) : undefined
  return <RedFlagBanner flag={f} onRaise={raise} onOpenCard={onOpenCard} />
}
