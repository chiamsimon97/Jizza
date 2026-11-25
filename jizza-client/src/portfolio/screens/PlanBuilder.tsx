import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Pencil, RotateCcw, Sparkles, TriangleAlert, User } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { formatINR } from '@/lib/money'
import type { AllocationNode, AllocationResult, BuilderEdit, BuilderInput, Bucket, RiskProfile } from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { AmountField, SelectField, parseAmount } from '../components/Fields'
import { Money } from '../components/Money'
import { LoadFailed, Panel, Pill, Row } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText, useCashFlow } from '../data'
import { BUCKET_COLOURS, RISK_PROFILES } from '../labels'
import { financeKeys } from '../queryKeys'

/** Where a figure comes from: an edit of yours, a goal's need, or Kai's table (with which row). */
function SourceTag({ node }: { node: Pick<AllocationNode, 'source' | 'sourceLabel'> }) {
  if (node.source === 'user') {
    return <span className="inline-flex items-center gap-1 rounded-full bg-brand-100 px-1.5 py-0.5 text-[11px] font-medium text-brand-700 dark:bg-brand-900/40 dark:text-brand-200"><User className="h-3 w-3" aria-hidden />You</span>
  }
  if (node.source === 'goal') return <Pill tone="info">{node.sourceLabel}</Pill>
  return (
    <span className="inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[11px] font-medium text-amber-700 ring-1 ring-inset ring-amber-500/60 dark:text-amber-300">
      <Sparkles className="h-3 w-3" aria-hidden />{node.sourceLabel}
    </span>
  )
}

/** An amount that turns into a box when pressed; Enter or leaving the box keeps it. */
function EditableAmount({ node, onSet }: { node: AllocationNode; onSet: (v: number) => void }) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const done = () => {
    setEditing(false)
    const v = parseAmount(text)
    if (v != null && v !== node.monthly) onSet(Math.round(v / 100) * 100)
  }
  if (editing) {
    return (
      <input autoFocus inputMode="numeric" aria-label={`Monthly amount for ${node.label}`} value={text}
        className={`${fieldClass} !w-28 !py-0.5 text-right tabular-nums`}
        onChange={e => setText(e.target.value)} onBlur={done}
        onKeyDown={e => { if (e.key === 'Enter') done(); if (e.key === 'Escape') setEditing(false) }} />
    )
  }
  return (
    <button type="button" onClick={() => { setText(String(node.monthly)); setEditing(true) }} title="Change this amount"
      className="group inline-flex items-center gap-1 rounded px-1 tabular-nums hover:bg-gray-100 dark:hover:bg-gray-800">
      <Money amount={node.monthly} />
      <Pencil className="h-3 w-3 text-gray-400 opacity-0 group-hover:opacity-100" aria-hidden />
    </button>
  )
}

function BucketCard({ node, total, onSet, lens, goalNames }: {
  node: AllocationNode
  total: number
  onSet: (key: string, v: number) => void
  lens: 'horizon' | 'goal'
  goalNames: string[]
}) {
  const share = total > 0 ? Math.round((node.monthly / total) * 100) : 0
  return (
    <Panel title={<><span className={`inline-block h-2.5 w-2.5 rounded-full ${BUCKET_COLOURS[node.key as Bucket]}`} aria-hidden />{node.label}</>}
      action={<EditableAmount node={node} onSet={v => onSet(node.key, v)} />}>
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
        {share}% of what you invest each month <SourceTag node={node} />
        {node.lumpsum > 0 && <span>· lumpsum <Money amount={node.lumpsum} /></span>}
      </div>
      {lens === 'goal' && goalNames.length > 0 && (
        <p className="text-xs text-cyan-700 dark:text-cyan-300">Funds: {goalNames.join(', ')}</p>
      )}
      <div className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
        {node.children.map(c => (
          <div key={c.key} className="flex flex-col gap-1 py-2">
            <div className="flex items-center justify-between gap-2 text-sm font-medium text-gray-800 dark:text-gray-200">
              <span className="flex flex-wrap items-center gap-1.5">{c.label} <SourceTag node={c} /></span>
              <EditableAmount node={c} onSet={v => onSet(c.key, v)} />
            </div>
            {c.children.map(s => (
              <div key={s.key} className="flex items-center justify-between gap-2 pl-3 text-sm text-gray-600 dark:text-gray-300">
                <span className="flex flex-wrap items-center gap-1.5">{s.label} <SourceTag node={s} /></span>
                <span className="flex items-center gap-2 whitespace-nowrap">
                  <EditableAmount node={s} onSet={v => onSet(s.key, v)} />
                  {s.lumpsum > 0 && <span className="text-xs text-gray-400">· <Money amount={s.lumpsum} /></span>}
                </span>
              </div>
            ))}
          </div>
        ))}
      </div>
    </Panel>
  )
}

function OffTheTopStrip({ r }: { r: AllocationResult }) {
  const total = r.offTheTop.reduce((s, o) => s + o.monthly, 0)
  if (!r.offTheTop.length) {
    return (
      <div className="flex items-start gap-2 rounded-xl bg-green-50 p-3 text-sm text-green-900 dark:bg-green-900/20 dark:text-green-200" role="status">
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        <span><b>Off the top: ₹0.</b> No emergency gap and no costly loan, so the whole amount is invested.</span>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-1.5 rounded-xl bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-900/20 dark:text-amber-100" role="status">
      <span className="flex items-center gap-2 font-semibold"><TriangleAlert className="h-4 w-4" aria-hidden />Off the top first: {formatINR(total)} a month</span>
      {r.offTheTop.map(o => (
        <div key={o.key} className="flex justify-between gap-2">
          <span>{o.label}<span className="block text-xs opacity-80">{o.reason}</span></span>
          <span className="whitespace-nowrap tabular-nums"><Money amount={o.monthly} />{o.lumpsum > 0 && <> · <Money amount={o.lumpsum} /> now</>}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * Plan builder (7.19): what goes in each month and once, split first into Short, Mid and Long by
 * age and goals, then into asset classes and sub-categories by risk profile. Survival needs come
 * off the top. Any amount can be changed; the parts beside it rebalance and it is marked "You".
 * Saving writes the plan lines; nothing is bought and no fund is named.
 */
export function PlanBuilder({ onOpen }: { onOpen: (view: string) => void }) {
  const qc = useQueryClient()
  const loaded = useQuery({ queryKey: financeKeys.builder, queryFn: financeApi.builder })
  const cash = useCashFlow()
  const [input, setInput] = useState<BuilderInput | null>(null)
  const [lens, setLens] = useState<'horizon' | 'goal'>('horizon')
  const [debounced, setDebounced] = useState<BuilderInput | null>(null)
  const timer = useRef<number>()

  useEffect(() => {
    if (loaded.data && input == null) {
      const i = loaded.data.input
      setInput({ monthly: i?.monthly ?? 0, lumpsum: i?.lumpsum ?? 0, stepUpPct: i?.stepUpPct ?? 0, riskProfile: i?.riskProfile ?? null, age: i?.age ?? null, edits: i?.edits ?? [] })
    }
  }, [loaded.data, input])
  useEffect(() => {
    if (!input) return
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setDebounced(input), 300)
    return () => window.clearTimeout(timer.current)
  }, [input])

  const preview = useQuery({
    queryKey: ['finance', 'builder', 'preview', debounced],
    queryFn: () => financeApi.previewBuilder(debounced!),
    enabled: !!debounced && debounced.monthly > 0,
    placeholderData: prev => prev,
    retry: false,
  })
  const save = useMutation({
    mutationFn: () => financeApi.saveBuilder(input!),
    onSuccess: v => { qc.setQueryData(financeKeys.builder, v); qc.invalidateQueries({ queryKey: financeKeys.all }) },
  })

  const view = preview.data ?? loaded.data
  const r = view?.result
  const goalsByBucket = useMemo(() => {
    const m: Record<string, string[]> = {}
    for (const g of r?.goalLens ?? []) if (g.goalId && g.monthly > 0) (m[g.bucket] ??= []).push(g.name)
    return m
  }, [r])

  if (loaded.isLoading || !input) return <SkeletonRows rows={6} />
  if (loaded.error || !loaded.data) return <LoadFailed text="Could not open the plan builder." onRetry={() => loaded.refetch()} />

  const set = (patch: Partial<BuilderInput>) => setInput(i => ({ ...i!, ...patch }))
  const edit = (key: string, monthly: number) => {
    const rest = (input.edits ?? []).filter(e => e.key !== key && !e.key.startsWith(key + '.'))
    set({ edits: [...rest, { key, monthly } satisfies BuilderEdit] })
  }
  const surplus = cash.data?.surplus ?? 0
  const edits = input.edits ?? []

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Plan builder</h2>
        {loaded.data.savedAt && <span className="text-xs text-gray-500 dark:text-gray-400">Saved {formatDay(loaded.data.savedAt)}</span>}
        <span className="flex-1" />
        <Button size="sm" variant="secondary" disabled={!edits.length} onClick={() => set({ edits: [] })}><RotateCcw className="h-3.5 w-3.5" /> Reset to Kai default</Button>
      </div>

      <section className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <AmountField label="Each month" value={input.monthly || null} onChange={v => set({ monthly: v ?? 0, edits: [] })} />
          <AmountField label="One-time lumpsum" value={input.lumpsum || null} onChange={v => set({ lumpsum: v ?? 0 })} />
          <SelectField<RiskProfile | ''> label="Risk profile" value={input.riskProfile ?? ''}
            options={[{ value: '', label: 'From my risk answers' },
              ...(Object.keys(RISK_PROFILES) as RiskProfile[]).map(k => ({ value: k, label: RISK_PROFILES[k] }))]}
            onChange={v => set({ riskProfile: v || null, edits: [] })} />
          <div className="flex flex-col justify-end gap-1 pb-1 text-sm">
            <span className="flex flex-wrap items-center gap-1.5">
              <Pill tone="warn">Age {view?.age}</Pill>
              {view?.ageSource === 'missing' && <span className="text-xs text-gray-500 dark:text-gray-400">Add your date of birth under People for your own age</span>}
            </span>
            <div className="inline-flex self-start rounded-lg border border-gray-200 p-0.5 dark:border-gray-700" role="group" aria-label="Lens">
              {(['horizon', 'goal'] as const).map(l => (
                <button key={l} type="button" aria-pressed={lens === l} onClick={() => setLens(l)}
                  className={`rounded-md px-2 py-0.5 text-xs ${lens === l ? 'bg-brand-500 text-white' : 'text-gray-600 dark:text-gray-300'}`}>
                  {l === 'horizon' ? 'By horizon' : 'By goal'}
                </button>
              ))}
            </div>
          </div>
        </div>
        {!input.monthly && surplus > 0 && (
          <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300"
            onClick={() => set({ monthly: Math.floor(surplus / 100) * 100, edits: [] })}>
            Start from what is left each month ({formatINR(Math.floor(surplus / 100) * 100)})
          </button>
        )}
      </section>

      {!input.monthly ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">Enter what you can invest each month to see the split.</p>
      ) : preview.error ? (
        <p role="alert" className="text-sm text-red-600">{errorText(preview.error, 'That change does not fit.')}{edits.length > 0 && <> <button type="button" className="underline" onClick={() => set({ edits: edits.slice(0, -1) })}>Undo the last change</button></>}</p>
      ) : r ? (
        <>
          <OffTheTopStrip r={r} />
          {r.notes.map(n => <p key={n} className="text-xs text-gray-500 dark:text-gray-400">{n}</p>)}
          <p className="text-xs text-gray-500 dark:text-gray-400">Money in → Short, Mid, Long → asset class → sub-category. The figure after a dot is the lumpsum share. Press any amount to change it.</p>
          <div className="grid gap-3 lg:grid-cols-3">
            {r.tree.children.map(b => <BucketCard key={b.key} node={b} total={r.monthlyInvested} onSet={edit} lens={lens} goalNames={goalsByBucket[b.key] ?? []} />)}
          </div>

          {lens === 'goal' && (
            <Panel title="By goal">
              {r.goalLens.length ? r.goalLens.map(g => (
                <Row key={`${g.goalId}-${g.bucket}`} label={<>{g.name} <span className="text-xs text-gray-400">· {g.bucket}</span></>}>
                  <Money amount={g.monthly} />{g.required > g.monthly && <span className="ml-1 text-xs text-red-600 dark:text-red-400">needs <Money amount={g.required} /></span>}
                </Row>
              )) : <p className="text-sm text-gray-500 dark:text-gray-400">No goals yet: the whole amount follows the age table. <button type="button" className="text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('goals')}>Add a goal</button></p>}
            </Panel>
          )}

          {(input.lumpsum ?? 0) > 0 && (
            <Panel title="Lumpsum timeline">
              <Row label="Invested today (Short, Mid, Long debt and gold)"><Money amount={r.timeline.investedToday} /></Row>
              <Row label="Parked in a liquid fund"><Money amount={r.timeline.parked} /></Row>
              {r.timeline.stpMonths > 0 && r.timeline.parked > 0 && (
                <Row label={`Moved to equity over ${r.timeline.stpMonths} months`}><Money amount={r.timeline.stp[0]?.amount ?? 0} /> a month</Row>
              )}
            </Panel>
          )}
        </>
      ) : <SkeletonRows rows={4} />}

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm text-gray-500 dark:text-gray-400">The parts always add up to what you entered. A changed line is marked <b>You</b>.</span>
        <span className="flex-1" />
        <Button disabled={!input.monthly || !!preview.error } loading={save.isPending} onClick={() => save.mutate()}>Save plan</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600">{errorText(save.error, 'Could not save the plan.')}</p>}
      <p className="text-xs text-gray-500 dark:text-gray-400">Saving keeps the split as your plan. Nothing is bought, and SIP schedules come in a later release. Kai suggests sub-categories, never a named fund.</p>
    </div>
  )
}
