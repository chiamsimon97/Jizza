import { useQuery } from '@tanstack/react-query'
import { Bed, Dumbbell, Footprints, HeartPulse, Smartphone } from 'lucide-react'
import type { ReactNode } from 'react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { formatDay } from '@/portfolio/components/AsOf'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { ActivityReading } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { localDay } from '../medicines'
import { vitaKeys } from '../queryKeys'
import { sourceWords } from '../readings'
import { useShareScope } from '../share'

const ZONE = 'Asia/Kolkata'
const DAYS = 14
const valid = (iso: string | null | undefined) => (iso && !Number.isNaN(Date.parse(iso)) ? new Date(iso) : null)
const dayOf = (iso: string) => { const d = valid(iso); return d ? new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d) : iso.slice(0, 10) }
const clock = (iso: string | null | undefined) => { const d = valid(iso); return d ? new Intl.DateTimeFormat('en-IN', { timeZone: ZONE, hour: 'numeric', minute: '2-digit' }).format(d) : '…' }
const whole = (n: number) => Math.round(n).toLocaleString('en-IN')
export function hoursWords(h: number): string {
  const mins = Math.round(h * 60)
  const hh = Math.floor(mins / 60)
  const mm = mins % 60
  return mm ? `${hh} h ${mm} min` : `${hh} h`
}
const workoutWords = (t: string | undefined) => {
  const s = (t ?? 'workout').replace(/_/g, ' ')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** The phones a list came from, in words: "From Pixel 8 (Health Connect)". */
function Sources({ rows }: { rows: ActivityReading[] }) {
  const seen = [...new Set(rows.map(r => [r.device, sourceWords(r.source)].filter(Boolean).join(', ')))].filter(Boolean)
  if (!seen.length) return null
  return <p className="text-xs text-gray-500 dark:text-gray-400">From {seen.join('; ')}</p>
}

function Section({ title, icon, children }: { title: string; icon: ReactNode; children: ReactNode }) {
  return (
    <section aria-label={title}>
      <Panel title={<>{icon}{title}</>}>{children}</Panel>
    </section>
  )
}

/**
 * Activity and sleep (7.13): steps a day, sleep with bedtime and wake time, workouts and resting
 * heart rate, as the person's phone or watch sent them through Health Connect or Apple Health. The
 * figures are shown as sent, with the phone named; Vita judges none of them. Before anything is
 * connected it says what will show here, with a way to Connect.
 */
export function Activity({ personId, personName: name, onOpen }: { personId: string; personName: string; onOpen: (view: string) => void }) {
  const share = useShareScope()
  const q = (kind: ActivityReading['kind']) => ({ queryKey: vitaKeys.activity(personId, kind), queryFn: () => vitaApi.activity(personId, kind) })
  const steps = useQuery(q('steps'))
  const sleep = useQuery(q('sleep'))
  const workouts = useQuery(q('workout'))
  const resting = useQuery(q('resting_heart_rate'))
  const connections = useQuery({ queryKey: vitaKeys.connectors(personId), queryFn: () => vitaApi.connectors(personId), retry: false })
  const all = [steps, sleep, workouts, resting]
  const you = name === 'You'
  const live = (connections.data ?? []).filter(c => !c.revokedAt)
  const loading = all.some(x => x.isLoading)
  const failed = all.find(x => x.error)
  const empty = !loading && !failed && all.every(x => !x.data?.length)

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Activity and sleep" sub={`From ${you ? 'your' : `${name}’s`} phone or watch, as it sent them`}>
        {!share.shared && <Button size="sm" variant="secondary" onClick={() => onOpen('connect')}><Smartphone className="h-3.5 w-3.5" aria-hidden />Connections</Button>}
      </ViewTitle>
      {loading && <SkeletonRows rows={4} />}
      {failed && <LoadFailed text="Could not load activity and sleep." onRetry={() => all.forEach(x => x.refetch())} />}
      {empty && (
        <Panel title={live.length ? 'Nothing has arrived yet' : 'Nothing is connected yet'}>
          <p className="text-sm text-gray-700 dark:text-gray-200">
            {live.length
              ? `A phone is connected (${live.map(c => c.deviceName ?? sourceWords(c.source)).join(', ')}). What it sends shows here after its next sync.`
              : 'Once a phone or watch is connected, this shows:'}
          </p>
          {!live.length && (
            <ul className="ml-4 list-disc text-sm text-gray-700 dark:text-gray-200">
              <li>Steps for each day</li>
              <li>Sleep each night, with bedtime and wake time</li>
              <li>Workouts and how long they lasted</li>
              <li>Resting heart rate</li>
            </ul>
          )}
          {!live.length && <p className="text-sm text-gray-600 dark:text-gray-300">Connect in the Jizza app on the phone: Health Connect on Android, Apple Health on an iPhone. You choose which data it sends.</p>}
          {!share.shared && !live.length && <div><Button size="sm" onClick={() => onOpen('connect')}>Connect a phone</Button></div>}
        </Panel>
      )}
      {!loading && !failed && !empty && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Steps rows={steps.data ?? []} />
          <Sleep rows={sleep.data ?? []} />
          <Workouts rows={workouts.data ?? []} />
          <Resting rows={resting.data ?? []} />
        </div>
      )}
    </div>
  )
}

function Steps({ rows }: { rows: ActivityReading[] }) {
  const byDay = new Map<string, number>()
  for (const r of rows) {
    const d = r.detail?.date ?? dayOf(r.takenAt)
    byDay.set(d, (byDay.get(d) ?? 0) + Number(r.value))
  }
  const days = Array.from({ length: DAYS }, (_, i) => localDay(-i))
  const shown = days.filter(d => byDay.has(d))
  const max = Math.max(1, ...shown.map(d => byDay.get(d)!))
  const avg = shown.length ? shown.reduce((a, d) => a + byDay.get(d)!, 0) / shown.length : null
  return (
    <Section title="Steps" icon={<Footprints className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />}>
      {!rows.length ? <p className="text-sm text-gray-500 dark:text-gray-400">No steps sent yet.</p> : (
        <>
          <p className="text-sm text-gray-700 dark:text-gray-200">
            {avg == null ? `No steps in the last ${DAYS} days.` : <>Average <span className="font-medium">{whole(avg)}</span> a day over {shown.length} {shown.length === 1 ? 'day' : 'days'} with steps, last {DAYS} days</>}
          </p>
          <ul className="flex flex-col gap-1">
            {days.map(d => {
              const v = byDay.get(d)
              return (
                <li key={d} className="grid grid-cols-[5.5rem_1fr_5.5rem] items-center gap-2 text-xs">
                  <span className="text-gray-500 dark:text-gray-400">{formatDay(d)}</span>
                  <span aria-hidden className="h-2 rounded-full bg-gray-100 dark:bg-gray-800">
                    {v != null && <span className="block h-2 rounded-full bg-[rgb(var(--agent-health))]" style={{ width: `${Math.max(2, (v / max) * 100)}%` }} />}
                  </span>
                  <span className="text-right text-gray-800 dark:text-gray-100">{v == null ? 'None sent' : `${whole(v)} steps`}</span>
                </li>
              )
            })}
          </ul>
          <Sources rows={rows} />
        </>
      )}
    </Section>
  )
}

function Sleep({ rows }: { rows: ActivityReading[] }) {
  const recent = [...rows].sort((a, b) => (b.endedAt ?? b.takenAt).localeCompare(a.endedAt ?? a.takenAt)).slice(0, DAYS)
  const avg = recent.length ? recent.reduce((a, r) => a + Number(r.value), 0) / recent.length : null
  return (
    <Section title="Sleep" icon={<Bed className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />}>
      {!recent.length ? <p className="text-sm text-gray-500 dark:text-gray-400">No sleep sent yet.</p> : (
        <>
          <p className="text-sm text-gray-700 dark:text-gray-200">Average <span className="font-medium">{hoursWords(avg!)}</span> over the last {recent.length} {recent.length === 1 ? 'night' : 'nights'}</p>
          <ul className="flex flex-col divide-y divide-gray-100 text-sm dark:divide-gray-800">
            {recent.map(r => (
              <li key={r.id} className="flex flex-wrap items-baseline justify-between gap-x-3 py-1.5">
                <span className="text-gray-500 dark:text-gray-400">{formatDay(dayOf(r.endedAt ?? r.takenAt))}</span>
                <span className="text-gray-700 dark:text-gray-200">{clock(r.startedAt)} to {clock(r.endedAt)}</span>
                <span className="font-medium text-gray-900 dark:text-gray-100">{hoursWords(Number(r.value))}</span>
              </li>
            ))}
          </ul>
          <Sources rows={recent} />
        </>
      )}
    </Section>
  )
}

function Workouts({ rows }: { rows: ActivityReading[] }) {
  const recent = [...rows].sort((a, b) => b.takenAt.localeCompare(a.takenAt)).slice(0, 10)
  return (
    <Section title="Workouts" icon={<Dumbbell className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />}>
      {!recent.length ? <p className="text-sm text-gray-500 dark:text-gray-400">No workouts sent yet.</p> : (
        <>
          <ul className="flex flex-col divide-y divide-gray-100 text-sm dark:divide-gray-800">
            {recent.map(r => (
              <li key={r.id} className="flex flex-wrap items-baseline justify-between gap-x-3 py-1.5">
                <span className="font-medium text-gray-900 dark:text-gray-100">{workoutWords(r.detail?.workoutType)}</span>
                <span className="text-gray-700 dark:text-gray-200">{whole(Number(r.value))} min</span>
                <span className="text-gray-500 dark:text-gray-400">{formatDay(dayOf(r.takenAt))}, {clock(r.startedAt ?? r.takenAt)}</span>
              </li>
            ))}
          </ul>
          <Sources rows={recent} />
        </>
      )}
    </Section>
  )
}

function Resting({ rows }: { rows: ActivityReading[] }) {
  const recent = [...rows].sort((a, b) => b.takenAt.localeCompare(a.takenAt)).slice(0, DAYS)
  const latest = recent[0]
  return (
    <Section title="Resting heart rate" icon={<HeartPulse className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />}>
      {!latest ? <p className="text-sm text-gray-500 dark:text-gray-400">No resting heart rate sent yet.</p> : (
        <>
          <p className="text-sm text-gray-700 dark:text-gray-200">Latest <span className="font-medium">{whole(Number(latest.value))} bpm</span> on {formatDay(dayOf(latest.takenAt))}</p>
          {recent.length > 1 && (
            <ul className="flex flex-wrap gap-1.5 text-xs">
              {recent.slice(1).map(r => (
                <li key={r.id} className="rounded-lg bg-gray-50 px-2 py-1 text-gray-700 dark:bg-gray-800/60 dark:text-gray-200">{formatDay(dayOf(r.takenAt))}: {whole(Number(r.value))} bpm</li>
              ))}
            </ul>
          )}
          <Sources rows={recent} />
        </>
      )}
    </Section>
  )
}
