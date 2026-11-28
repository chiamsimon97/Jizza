import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Activity, CalendarClock, HeartPulse, IdCard, Phone } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { agentsApi } from '@/api/agents'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { DoseSlotCard } from '../components/DoseSlotCard'
import { formatWhen } from '../data'
import { vitaKeys } from '../queryKeys'
import { HEALTH_AGENT_KEY } from '../useHealthEnabled'
import { ShareContext, canEdit, canSee, scopeOf, useSharedWithMe } from '../share'

/**
 * "My medicines today" (7.20): what a person sees when they sign in with their own login, linked to
 * the record someone in their Family keeps. Large text; today's slots with All taken and a tick per
 * medicine; who marked what; the next visit with the doctor's phone; add a reading; the emergency
 * card; and one link to the full record. `slot` is the reminder's slot, opened from a reminder.
 */
export function MyMedicinesToday({ slot }: { slot?: string | null }) {
  const shared = useSharedWithMe()
  const me = shared.data?.find(e => e.asSelf)
  const personId = me?.personId ?? ''
  const today = useQuery({ queryKey: vitaKeys.doses(personId, 'today', 'today'), queryFn: () => vitaApi.doses(personId), enabled: !!me && canSee(scopeOf(me, personId), 'medicines') })
  const home = useQuery({ queryKey: vitaKeys.home(personId), queryFn: () => vitaApi.home(personId), enabled: !!me })
  const visitsShared = !!me && canSee(scopeOf(me, personId), 'visits')
  const doctors = useQuery({ queryKey: vitaKeys.personDoctors(personId), queryFn: () => vitaApi.personDoctors(personId), enabled: visitsShared })
  const agents = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), enabled: !!me })
  const vita = agents.data?.find(a => a.key === HEALTH_AGENT_KEY)

  useEffect(() => {
    if (!slot || !today.data) return
    const el = document.getElementById(`slot-${slot}`)
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center' })
  }, [slot, today.data])

  if (shared.isLoading) return <SkeletonRows rows={4} />
  if (shared.error) return <LoadFailed text="Could not open your medicines." onRetry={() => shared.refetch()} />
  if (!me) {
    return (
      <Panel title="Nothing is linked to you yet">
        <p className="text-base text-gray-600 dark:text-gray-300">
          When someone in your Family links you to your health record with “This is them”, your medicines for today show here.
        </p>
      </Panel>
    )
  }

  const scope = scopeOf(me, personId)
  const readOnly = !canEdit(scope, 'medicines')
  const record = (view?: string) => vita ? `/agents/${vita.id}?tab=health&person=${encodeURIComponent(personId)}${view ? `&view=${view}` : ''}` : null
  const visit = home.data?.nextVisit
  const doctor = visit?.doctorId ? doctors.data?.find(d => d.id === visit.doctorId) : undefined
  const slots = today.data?.slots ?? []
  const linkClass = 'inline-flex items-center gap-2 rounded-xl border border-gray-200 bg-white px-4 py-3 text-base font-medium text-gray-900 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:hover:bg-gray-800'

  return (
    <ShareContext.Provider value={scope}>
      <div className="flex flex-col gap-5">
        <p className="text-sm text-gray-500 dark:text-gray-400">Kept by {me.managerEmail ?? 'someone in your Family'}.</p>

        {canSee(scope, 'medicines') && (
          <section aria-label="Today’s medicines" className="flex flex-col gap-3">
            {today.isLoading && <SkeletonRows rows={3} />}
            {today.error && <LoadFailed text="Could not load today’s medicines." onRetry={() => today.refetch()} />}
            {today.data && !slots.length && <p className="rounded-xl bg-gray-50 p-4 text-lg text-gray-700 dark:bg-gray-800/60 dark:text-gray-200">Nothing is due today.</p>}
            {slots.map(s => <DoseSlotCard key={s.dueAt} personId={personId} slot={s} readOnly={readOnly} large highlight={s.dueAt === slot} />)}
          </section>
        )}

        {visitsShared && visit && (
          <Panel title={<><CalendarClock className="h-5 w-5 text-[rgb(var(--agent-health))]" aria-hidden />Next visit</>}>
            <p className="text-lg text-gray-900 dark:text-gray-100">{formatWhen(visit.scheduledAt)}{doctor ? ` with ${doctor.name}` : ''}</p>
            {visit.reason && <p className="text-base text-gray-600 dark:text-gray-300">{visit.reason}</p>}
            {doctor?.phone && (
              <a href={`tel:${doctor.phone.replace(/\s+/g, '')}`} className="inline-flex items-center gap-2 text-lg font-medium text-brand-700 underline dark:text-brand-300">
                <Phone className="h-5 w-5" aria-hidden />{doctor.phone}
              </a>
            )}
          </Panel>
        )}

        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
          {canEdit(scope, 'vitals') && record('vitals') && <Link to={record('vitals')!} className={linkClass}><Activity className="h-5 w-5 text-[rgb(var(--agent-health))]" aria-hidden />Add a reading</Link>}
          {canSee(scope, 'emergency') && record('emergency') && <Link to={record('emergency')!} className={linkClass}><IdCard className="h-5 w-5 text-[rgb(var(--agent-health))]" aria-hidden />Emergency card</Link>}
          {record() && <Link to={record()!} className={linkClass}><HeartPulse className="h-5 w-5 text-[rgb(var(--agent-health))]" aria-hidden />Open my full health record</Link>}
        </div>
      </div>
    </ShareContext.Provider>
  )
}
