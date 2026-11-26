import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock, Pencil, SlidersHorizontal } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import type { JsonValue, RuleEffective, RuleField, RuleInput, RuleLevel, RuleState, RulesView } from '@/types/finance'
import { CheckField, SelectField, TextField } from '../components/Fields'
import { LoadFailed, Panel, Pill } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText, useSample } from '../data'
import { financeKeys } from '../queryKeys'
import { GoalPicker } from './GoalPicker'

type Params = Record<string, JsonValue>

const LEVEL_WORDS: Record<RuleLevel, string> = {
  instrument: 'This holding', sub_category: 'Sub-category', asset_class: 'Asset class', goal: 'Goal', user: 'All your goals', kai: 'Kai default',
}

/** A rule's value in words: "Fixed, 15% below cost", "Observe". */
export function ruleWords(field: RuleField, p: Params | null | undefined): string {
  if (!p) return 'Off'
  const n = (k: string) => (p[k] == null ? '?' : String(p[k]))
  switch (field) {
    case 'stop_loss': return `${p.kind === 'trailing' ? 'Trailing' : 'Fixed'}, ${n('pct')}% below`
    case 'profit_booking': return `Sell ${n('sellPct')}% at a ${n('atGainPct')}% gain`
    case 'rebalance_band': return p.mode === 'absolute' ? `±${n('points')} points` : p.mode === 'relative' ? `±${n('pct')}% of target` : '5/25 rule'
    case 'execution_mode': return p.mode === 'propose' ? 'Propose' : p.mode === 'auto' ? 'Auto' : 'Observe'
    case 'concentration_cap': return `At most ${n('maxPct')}% in one holding`
    case 'accumulate': return p.kind === 'sip_date' ? 'On the SIP date' : 'On a dip'
    case 'exit': return p.kind === 'time' ? 'After a time' : p.kind === 'bucket_change' ? 'When the bucket changes' : 'When the reason to hold is gone'
  }
}

function Effective({ e }: { e: RuleEffective | null }) {
  if (!e) return <span className="text-gray-500">Off</span>
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      <span className="text-gray-900 dark:text-gray-100">{e.state === 'off' ? 'Off' : ruleWords(e.field, e.value as Params)}</span>
      <Pill tone={e.sourceLevel === 'kai' ? 'neutral' : 'brand'} title="Where this value comes from">From {e.sourceLevel === 'kai' ? 'Kai' : e.sourceName}</Pill>
      {e.locked && <Pill tone="info" title={e.lockedBy ? `Locked by ${e.lockedBy}` : 'Locked'}><Lock className="h-3 w-3" aria-hidden />Locked</Pill>}
    </span>
  )
}

/**
 * Rules (7.5, Advanced): each rule for a goal (or one holding in it), its value and where that
 * value comes from, nearest level first. A lock above means lower levels can only be stricter.
 * Before saving, Kai says how many earmarks the change reaches.
 */
export function Rules({ goal, onGoal }: { goal: string | null; onGoal: (id: string | null) => void }) {
  const sample = useSample()
  const [instrument, setInstrument] = useState<string | null>(null)
  useEffect(() => setInstrument(null), [goal])
  const holdings = useQuery({ queryKey: financeKeys.holdings(null, goal), queryFn: () => financeApi.holdings(null, goal), enabled: !!goal && !sample })
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: financeKeys.rules(goal, instrument),
    queryFn: () => financeApi.rules(goal, instrument),
    enabled: !sample,
  })
  const [editing, setEditing] = useState<RuleField | null>(null)
  if (sample) return <EmptyState icon={SlidersHorizontal} title="Rules belong to your own goals" description="Leave the sample household to set rules." />

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Rules</h2>
        <span className="flex-1" />
        <GoalPicker value={goal} onChange={onGoal} />
        {holdings.data && holdings.data.rows.length > 0 && (
          <SelectField label="Holding" value={instrument ?? ''} onChange={v => setInstrument(v || null)}
            options={[{ value: '', label: 'The whole goal' }, ...holdings.data.rows.map(r => ({ value: r.instrumentId, label: r.name }))]} />
        )}
      </div>
      <p className="text-sm text-gray-600 dark:text-gray-400">
        Kai starts every goal in Observe: it watches and flags, and proposes nothing. It never places an order.
      </p>
      {isLoading ? <SkeletonRows rows={7} /> : error || !data ? <LoadFailed text="Could not load the rules." onRetry={() => refetch()} /> : (
        <Panel title={data.instrumentName ? `${data.instrumentName} in ${data.goalName}` : data.goalName ?? 'All your goals'}>
          <p className="text-xs text-gray-500 dark:text-gray-400">Looked up in this order: {data.chain.map(c => c.name || LEVEL_WORDS[c.level]).join(' → ')}.</p>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[30rem] text-sm">
              <caption className="sr-only">Rules in force</caption>
              <thead>
                <tr className="border-b border-gray-200 text-left text-xs text-gray-500 dark:border-gray-800 dark:text-gray-400">
                  <th scope="col" className="py-2 pr-3 font-medium">Rule</th>
                  <th scope="col" className="py-2 pr-3 font-medium">In force</th>
                  <th scope="col" className="py-2 font-medium"><span className="sr-only">Change</span></th>
                </tr>
              </thead>
              <tbody>
                {data.fields.map(f => (
                  <tr key={f.field} className="border-b border-gray-100 last:border-0 dark:border-gray-800/60">
                    <td className="py-2 pr-3 font-medium text-gray-900 dark:text-gray-100">{f.label}</td>
                    <td className="py-2 pr-3"><Effective e={f.effective} /></td>
                    <td className="py-2 text-right">
                      <Button size="sm" variant="ghost" onClick={() => setEditing(f.field)}><Pencil className="h-4 w-4" /> Change</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
      {editing && data && <RuleDialog view={data} field={editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function ParamFields({ field, params, set }: { field: RuleField; params: Params; set: (p: Params) => void }) {
  const num = (k: string, label: string) => (
    <TextField label={label} type="number" value={params[k] == null ? '' : String(params[k])}
      onChange={v => set({ ...params, [k]: v === '' ? null : Number(v) })} />
  )
  const pick = (k: string, label: string, options: { value: string; label: string }[]) => (
    <SelectField label={label} value={String(params[k] ?? options[0].value)} options={options} onChange={v => set({ ...params, [k]: v })} />
  )
  switch (field) {
    case 'stop_loss': return <>{pick('kind', 'Kind', [{ value: 'fixed', label: 'Fixed' }, { value: 'trailing', label: 'Trailing' }])}{num('pct', 'Percent below')}</>
    case 'profit_booking': return <>{num('sellPct', 'Sell this share (%)')}{num('atGainPct', 'At this gain (%)')}</>
    case 'rebalance_band': return (
      <>
        {pick('mode', 'Band', [{ value: 'five_twenty_five', label: '5/25 rule' }, { value: 'absolute', label: 'Points either side' }, { value: 'relative', label: 'Percent of target' }])}
        {params.mode === 'absolute' && num('points', 'Points')}
        {params.mode === 'relative' && num('pct', 'Percent')}
      </>
    )
    case 'execution_mode': return pick('mode', 'Mode', [{ value: 'observe', label: 'Observe: watch and flag' }, { value: 'propose', label: 'Propose: suggest for you to approve' }])
    case 'concentration_cap': return num('maxPct', 'Most in one holding (%)')
    case 'accumulate': return pick('kind', 'When', [{ value: 'dip', label: 'On a dip' }, { value: 'sip_date', label: 'On the SIP date' }])
    case 'exit': return pick('kind', 'When', [{ value: 'thesis', label: 'When the reason to hold is gone' }, { value: 'time', label: 'After a time' }, { value: 'bucket_change', label: 'When the bucket changes' }])
  }
}

const DEFAULT_PARAMS: Record<RuleField, Params> = {
  stop_loss: { kind: 'fixed', pct: 15 }, profit_booking: { sellPct: 25, atGainPct: 50 }, rebalance_band: { mode: 'five_twenty_five' },
  execution_mode: { mode: 'observe' }, concentration_cap: { maxPct: 20 }, accumulate: { kind: 'dip' }, exit: { kind: 'thesis' },
}

function RuleDialog({ view, field, onClose }: { view: RulesView; field: RuleField; onClose: () => void }) {
  const qc = useQueryClient()
  const f = view.fields.find(x => x.field === field)!
  const levels: Exclude<RuleLevel, 'kai'>[] = [...(view.instrumentId ? ['instrument' as const] : []), ...(view.goalId ? ['goal' as const] : []), 'user']
  const [level, setLevel] = useState(levels[0])
  const cell = f.cells.find(c => c.level === level)
  const [state, setState] = useState<RuleState>(cell?.state ?? 'inherit')
  const [params, setParams] = useState<Params>(cell?.params ?? (f.effective?.value as Params | null) ?? DEFAULT_PARAMS[field])
  const [locked, setLocked] = useState(cell?.locked ?? false)
  useEffect(() => {
    setState(cell?.state ?? 'inherit')
    setLocked(cell?.locked ?? false)
    if (cell?.params) setParams(cell.params)
    // Only when the level changes: the cell for that level becomes the starting point.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [level])
  const body: RuleInput = {
    level, field, state, locked: level !== 'instrument' && locked, params: state === 'set' ? params : undefined,
    goalId: level === 'user' ? null : view.goalId, instrumentId: level === 'instrument' ? view.instrumentId : null,
  }
  const key = JSON.stringify(body)
  const preview = useQuery({
    queryKey: ['finance', 'rules', 'preview', key],
    queryFn: () => financeApi.previewRule(body),
    retry: false,
  })
  const save = useMutation({
    mutationFn: () => financeApi.saveRule(body),
    onSuccess: () => { qc.invalidateQueries({ queryKey: financeKeys.all }); onClose() },
  })
  const n = preview.data?.affectedEarmarks
  return (
    <Dialog open title={`Change ${f.label.toLowerCase()}`} onClose={onClose} size="lg">
      <div className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Set it for" value={level} onChange={v => setLevel(v)}
            options={levels.map(l => ({ value: l, label: l === 'goal' ? view.goalName ?? 'This goal' : l === 'instrument' ? view.instrumentName ?? 'This holding' : LEVEL_WORDS[l] }))} />
          <SelectField label="State" value={state} onChange={v => setState(v)}
            options={[{ value: 'inherit', label: 'Take it from above' }, { value: 'set', label: 'Set here' }, { value: 'off', label: 'Off here' }]} />
          {state === 'set' && <ParamFields field={field} params={params} set={setParams} />}
        </div>
        {level !== 'instrument' && <CheckField label="Lock it: levels below can only be stricter" checked={locked} onChange={setLocked} />}
        <p className="text-sm" role="status" data-testid="rule-preview">
          {preview.error ? <span className="text-red-600">{errorText(preview.error, 'This change cannot be saved.')}</span>
            : n == null ? 'Checking what this reaches…'
              : `This change affects ${n} ${n === 1 ? 'earmark' : 'earmarks'}.`}
        </p>
        {save.error && <p className="text-sm text-red-600" role="alert">{errorText(save.error, 'Could not save the rule.')}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button disabled={!!preview.error || n == null} loading={save.isPending} onClick={() => save.mutate()}>Save</Button>
        </div>
      </div>
    </Dialog>
  )
}
