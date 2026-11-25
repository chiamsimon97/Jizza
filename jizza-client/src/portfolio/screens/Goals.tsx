import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Flag, Pencil, Plus, Trophy } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import type { FinPerson, GoalStatus, GoalView } from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { Money } from '../components/Money'
import { LoadFailed, Meter, Panel, Pill, Row } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText } from '../data'
import { BUCKETS, BUCKET_COLOURS, BUCKET_SHORT, GOAL_STATUS, GOAL_TIERS, INFLATION_KEYS, RISK_PROFILES } from '../labels'
import { financeKeys } from '../queryKeys'

const MIX_ORDER = ['equity', 'debt', 'gold', 'cash']

const LIVE: GoalStatus[] = ['active', 'paused', 'draft']

const RISK_SOURCE: Record<GoalView['riskSource'], string> = {
  goal: 'set on this goal', self: 'your risk profile', person: 'their risk profile', default: 'Kai default until you answer the risk questions',
}

export function useGoals() {
  return useQuery({ queryKey: financeKeys.goals, queryFn: financeApi.goals })
}

function year(iso: string) {
  return iso.slice(0, 4)
}

function fundedTone(pct: number): 'ok' | 'warn' | 'bad' {
  return pct >= 90 ? 'ok' : pct >= 60 ? 'warn' : 'bad'
}

/**
 * Goals (7.2): every goal with its bucket, priority, how funded it is at today's pace and the SIP
 * that would reach it. Projections assume Kai's default returns until holdings arrive.
 */
export function Goals({ person, onOpenGoal, onNew }: {
  person: string
  onOpenGoal: (id: string) => void
  onNew: () => void
}) {
  const { data, isLoading, error, refetch } = useGoals()
  const [showDone, setShowDone] = useState(false)
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) return <LoadFailed text="Could not load your goals." onRetry={() => refetch()} />
  const mine = data.filter(g => person === 'all' || g.goal.ownerPersonId === person)
  const live = mine.filter(g => LIVE.includes(g.goal.status))
  const done = mine.filter(g => !LIVE.includes(g.goal.status))

  if (!data.length) {
    return <EmptyState icon={Flag} title="No goals yet" description="Pick one from the library: Kai fills in the amount, the date and the SIP it needs, and you correct what is wrong."
      action={{ label: 'Add a goal', onClick: onNew }} />
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Goals</h2>
        <span className="flex-1" />
        <Button size="sm" onClick={onNew}><Plus className="h-4 w-4" /> New goal</Button>
      </div>
      {live.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">No live goals for this person.</p>}
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {live.map(g => <GoalCard key={g.goal.id} view={g} onOpen={() => onOpenGoal(g.goal.id)} />)}
      </div>
      {done.length > 0 && (
        <div className="flex flex-col gap-2">
          <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" aria-expanded={showDone}
            onClick={() => setShowDone(s => !s)}>
            {showDone ? 'Hide' : 'Show'} achieved and archived ({done.length})
          </button>
          {showDone && (
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              {done.map(g => <GoalCard key={g.goal.id} view={g} onOpen={() => onOpenGoal(g.goal.id)} />)}
            </div>
          )}
        </div>
      )}
      <p className="text-xs text-gray-500 dark:text-gray-400">
        Funded % compares what you have and put in each month with the target grown by inflation. Until holdings are added, Kai assumes its default returns for each bucket.
      </p>
    </div>
  )
}

function GoalCard({ view, onOpen }: { view: GoalView; onOpen: () => void }) {
  const { goal, funding: f } = view
  const done = goal.status === 'achieved' || goal.status === 'archived'
  return (
    <article className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <h3 className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-gray-900 dark:text-gray-100">
        {f && <span className={`inline-block h-2.5 w-2.5 rounded-full ${BUCKET_COLOURS[f.bucket]}`} aria-hidden />}
        {goal.name}
        <Pill>{view.ownerName}</Pill>
        {f && <Pill tone={f.bucket === 'short' ? 'warn' : 'info'}>{BUCKET_SHORT[f.bucket]}</Pill>}
        <Pill tone={goal.priorityTier === 'survival' ? 'bad' : goal.priorityTier === 'important' ? 'brand' : 'neutral'}>{GOAL_TIERS[goal.priorityTier]}</Pill>
        {goal.status !== 'active' && <Pill tone={goal.status === 'achieved' ? 'ok' : 'neutral'}>{GOAL_STATUS[goal.status]}</Pill>}
      </h3>
      {f && !done ? (
        <>
          <div className="flex justify-between gap-2 text-sm">
            <span className="text-gray-500 dark:text-gray-400">Target {year(goal.targetDate)} · <Money amount={f.futureTarget} short /> then</span>
            <span className="font-semibold tabular-nums text-gray-900 dark:text-gray-100">{Math.round(f.fundedPct)}% funded</span>
          </div>
          <Meter pct={f.fundedPct} label={`${goal.name} funded`} className={f.fundedPct >= 90 ? 'bg-green-600' : f.fundedPct >= 60 ? 'bg-amber-500' : 'bg-red-500'} />
          <Row label="Going in each month"><Money amount={goal.monthlyContribution} /></Row>
          <Row label="Needed for 100%"><Money amount={f.requiredSip} /></Row>
          {view.moveNote && <p className="text-xs text-gray-500 dark:text-gray-400">{view.moveNote}</p>}
        </>
      ) : (
        <p className="text-sm text-gray-500 dark:text-gray-400">
          {goal.status === 'achieved' ? `Achieved${goal.achievedAt ? ` on ${formatDay(goal.achievedAt)}` : ''}.` : 'Not counted in your plan.'}
        </p>
      )}
      <div className="mt-1"><Button size="sm" variant="secondary" onClick={onOpen}>Open goal</Button></div>
    </article>
  )
}

/**
 * Goal detail (7.26): the funding sum worked out, the mix the goal's bucket starts from, and the
 * details Kai used. Holdings, schedules and history arrive with Phases 2 and 3.
 */
export function GoalDetail({ id, people, onBack, onEdit }: {
  id: string
  people: FinPerson[]
  onBack: () => void
  onEdit: () => void
}) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: financeKeys.goal(id), queryFn: () => financeApi.goal(id) })
  const refresh = () => qc.invalidateQueries({ queryKey: financeKeys.all })
  const achieve = useMutation({ mutationFn: () => financeApi.achieveGoal(id), onSuccess: refresh })
  const status = useMutation({ mutationFn: (s: GoalStatus) => financeApi.updateGoal(id, { status: s }), onSuccess: refresh })
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) return <LoadFailed text="Could not open this goal." onRetry={() => refetch()} />
  const { goal, funding: f } = data
  const live = LIVE.includes(goal.status)
  const owner = people.find(p => p.id === goal.ownerPersonId)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="ghost" onClick={onBack} aria-label="Back to goals"><ArrowLeft className="h-4 w-4" /></Button>
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">{goal.name}</h2>
        <Pill tone={goal.priorityTier === 'survival' ? 'bad' : goal.priorityTier === 'important' ? 'brand' : 'neutral'}>{GOAL_TIERS[goal.priorityTier]}</Pill>
        <Pill tone={goal.status === 'active' ? 'ok' : 'neutral'}>{GOAL_STATUS[goal.status]}</Pill>
        <span className="flex-1" />
        {live && <Button size="sm" variant="secondary" onClick={onEdit}><Pencil className="h-3.5 w-3.5" /> Edit</Button>}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Panel title="Funding">
          {f ? (
            <>
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-semibold tabular-nums text-gray-900 dark:text-gray-100">{Math.round(f.fundedPct)}%</span>
                <Pill tone={fundedTone(f.fundedPct)}>{f.fundedPct >= 100 ? 'On course' : 'Short at today’s pace'}</Pill>
              </div>
              <Meter pct={f.fundedPct} label="Funded" className={f.fundedPct >= 90 ? 'bg-green-600' : f.fundedPct >= 60 ? 'bg-amber-500' : 'bg-red-500'} />
              <Row label={`Target in ${year(goal.targetDate)}`}><Money amount={f.futureTarget} /></Row>
              <Row label="Projected by then"><Money amount={f.projected} /></Row>
              <Row label="Going in each month"><Money amount={goal.monthlyContribution} /></Row>
              <Row label="Needed each month for 100%" strong><Money amount={f.requiredSip} /></Row>
              <p className="text-xs text-gray-500 dark:text-gray-400">
                {formatDay(goal.targetDate)}, {f.monthsToTarget} months away. Assumes {f.blendedReturnNow.toFixed(1)}% a year for this bucket’s mix and {f.inflationPct}% inflation ({INFLATION_KEYS[goal.inflationKey].toLowerCase()}). Kai default.
              </p>
            </>
          ) : <p className="text-sm text-gray-500 dark:text-gray-400">This goal is no longer counted in your plan.</p>}
        </Panel>

        <Panel title={f ? <>Starting mix <Pill tone="info">{BUCKETS[f.bucket]}</Pill></> : 'Starting mix'}>
          {f ? (
            <>
              {Object.entries(f.mixNow).filter(([, v]) => v > 0).sort(([a], [b]) => MIX_ORDER.indexOf(a) - MIX_ORDER.indexOf(b)).map(([k, v]) => (
                <div key={k} className="flex flex-col gap-1">
                  <Row label={k.charAt(0).toUpperCase() + k.slice(1)}><span className="tabular-nums">{Number(v.toFixed(1))}%</span></Row>
                  <Meter pct={v} label={`${k} share`} />
                </div>
              ))}
              <p className="text-xs text-gray-500 dark:text-gray-400">
                From the plan builder’s tables for a {RISK_PROFILES[data.risk].toLowerCase()} profile ({RISK_SOURCE[data.riskSource]}).
                {f.nextBucket && f.monthsToNextBucket != null ? ` Moves to ${BUCKET_SHORT[f.nextBucket]} in ${f.monthsToNextBucket} months.` : ''}
              </p>
              <p className="text-xs text-gray-500 dark:text-gray-400">Your actual mix against this target comes when you add holdings.</p>
            </>
          ) : <p className="text-sm text-gray-500 dark:text-gray-400">—</p>}
        </Panel>

        <Panel title="Details">
          <Row label="Whose goal">{owner ? (owner.kind === 'self' ? 'You' : owner.displayName) : data.ownerName}</Row>
          <Row label="In today’s rupees"><Money amount={goal.targetAmountToday} /></Row>
          <Row label="Saved for it so far"><Money amount={goal.currentValue} /></Row>
          <Row label="Yearly step-up">{goal.stepupPct ? `${goal.stepupPct}%` : 'None'}</Row>
          <Row label="Risk">{RISK_PROFILES[data.risk]}</Row>
          <p className="text-xs text-gray-500 dark:text-gray-400">Schedules and history come with holdings and SIP tracking.</p>
          {live && (
            <div className="mt-2 flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" loading={achieve.isPending} onClick={() => achieve.mutate()}><Trophy className="h-3.5 w-3.5" /> Mark achieved</Button>
              {goal.status === 'active' && <Button size="sm" variant="ghost" loading={status.isPending} onClick={() => status.mutate('paused')}>Pause</Button>}
              {goal.status === 'paused' && <Button size="sm" variant="ghost" loading={status.isPending} onClick={() => status.mutate('active')}>Resume</Button>}
              <Button size="sm" variant="ghost" loading={status.isPending} onClick={() => status.mutate('archived')}>Archive</Button>
            </div>
          )}
          {(achieve.error || status.error) && <p role="alert" className="text-sm text-red-600">{errorText(achieve.error ?? status.error, 'Could not change this goal.')}</p>}
        </Panel>
      </div>
    </div>
  )
}
