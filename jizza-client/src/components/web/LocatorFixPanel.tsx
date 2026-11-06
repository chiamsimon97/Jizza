import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2, MousePointerClick, Wrench } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { webApi, type LocatorFix, type PickResult } from '@/api/web'
import { devicesApi } from '@/api/devices'
import { ElementPreview } from './PageView'
import { describeCandidate, scoreText } from './fixModel'

/**
 * The suggested fixes for this workflow's web steps that lost their element. The person picks the
 * element that is the right one, or dismisses the suggestion. Nothing changes until they do.
 */
export function LocatorFixPanel({ workflowId, focusNode, onApplied }: {
  workflowId: string
  /** The step the person came here for, shown first. */
  focusNode?: string | null
  onApplied: (fix: LocatorFix, version: number) => void
}) {
  const { data } = useQuery({
    queryKey: ['web-fixes', 'open'],
    queryFn: () => webApi.fixes('open'),
    staleTime: 15_000,
  })
  const fixes = (data ?? []).filter(f => f.workflowId === workflowId)
    .sort((a, b) => Number(b.nodeId === focusNode) - Number(a.nodeId === focusNode))
  if (fixes.length === 0) return null
  return (
    <div className="flex flex-col gap-3">
      {fixes.map(f => <FixCard key={f.id} fix={f} onApplied={onApplied} />)}
    </div>
  )
}

function FixCard({ fix, onApplied }: { fix: LocatorFix; onApplied: (fix: LocatorFix, version: number) => void }) {
  const qc = useQueryClient()
  const [chosen, setChosen] = useState<number | null>(null)
  const [error, setError] = useState('')
  const done = () => void qc.invalidateQueries({ queryKey: ['web-fixes'] })
  const apply = useMutation({
    mutationFn: (index: number) => webApi.applyFix(fix.id, index),
    onSuccess: r => { done(); onApplied(fix, r.version) },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'The fix could not be applied'),
  })
  const dismiss = useMutation({
    mutationFn: () => webApi.dismissFix(fix.id),
    onSuccess: done,
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'The fix could not be dismissed'),
  })

  return (
    <section aria-label={`Fix for ${fix.nodeLabel}`}
      className="rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-700 dark:bg-amber-500/10">
      <p className="flex items-start gap-2 font-medium text-amber-900 dark:text-amber-200">
        <Wrench className="mt-0.5 h-4 w-4 shrink-0" />
        <span>{fix.nodeLabel} could not find its element. The page may have changed. Is one of these the right one?</span>
      </p>
      <fieldset className="mt-2 flex flex-col gap-1.5">
        <legend className="sr-only">Suggestions for {fix.nodeLabel}</legend>
        {fix.candidates.map((c, i) => {
          const { title, how } = describeCandidate(c)
          const score = scoreText(c.score)
          return (
            <label key={i} className="flex cursor-pointer items-start gap-2 rounded-lg bg-white px-2.5 py-2 dark:bg-gray-900">
              <input type="radio" name={`fix-${fix.id}`} className="mt-1" checked={chosen === i} onChange={() => setChosen(i)} />
              <span className="min-w-0 flex-1">
                <span className="block font-medium text-gray-900 dark:text-gray-100">{title}</span>
                <span className="block text-xs text-gray-500 dark:text-gray-400">{how}{score ? ` · ${score}` : ''}</span>
              </span>
            </label>
          )
        })}
      </fieldset>
      <PickInstead fix={fix} onApplied={version => { done(); onApplied(fix, version) }} />
      <p className="mt-2 text-xs text-amber-800 dark:text-amber-300">
        Applying saves a new version of this workflow. The step tries the element you choose first and keeps its old ways of finding it as fallbacks.
      </p>
      {error && <p role="alert" className="mt-2 text-xs text-red-600">{error}</p>}
      <div className="mt-2 flex gap-2">
        <Button type="button" size="sm" disabled={chosen === null} loading={apply.isPending}
          onClick={() => { setError(''); if (chosen !== null) apply.mutate(chosen) }}>Apply fix</Button>
        <Button type="button" size="sm" variant="ghost" loading={dismiss.isPending}
          onClick={() => { setError(''); dismiss.mutate() }}>Dismiss</Button>
      </div>
    </section>
  )
}

/**
 * None of the suggestions is right: the person clicks the right element on the page in their
 * browser (the run's page is usually still open), sees what they picked, and applies it.
 */
function PickInstead({ fix, onApplied }: { fix: LocatorFix; onApplied: (version: number) => void }) {
  const { data: browsers } = useQuery({ queryKey: ['web-browsers'], queryFn: webApi.browsers, staleTime: 15_000 })
  const online = (browsers ?? []).filter(b => b.online)
  const [chosen, setChosen] = useState('')
  const deviceId = online.length === 1 ? online[0].id : chosen
  const [picking, setPicking] = useState<string>()
  const [picked, setPicked] = useState<{ commandId: string; result: PickResult }>()
  const [error, setError] = useState('')

  useEffect(() => {
    if (!picking) return
    let stop = false
    const timer = setInterval(async () => {
      try {
        const r = await webApi.pickResult(picking)
        if (stop || r.status === 'waiting') return
        setPicking(undefined)
        if (r.status === 'done' && r.element) setPicked({ commandId: picking, result: r })
        else if (r.error?.kind !== 'cancelled_by_person') setError(r.error?.detail ?? 'Picking did not finish.')
      } catch (e) {
        if (stop) return
        setPicking(undefined)
        setError(e instanceof Error ? e.message : 'Picking did not finish.')
      }
    }, 1000)
    return () => { stop = true; clearInterval(timer) }
  }, [picking])

  const start = async () => {
    setError('')
    setPicked(undefined)
    try {
      const started = await webApi.pick({ deviceId, prompt: `Click the element "${fix.nodeLabel}" should use` })
      setPicking(started.commandId)
    } catch (e) {
      const problem = (e as { problem?: { detail?: string; title?: string } }).problem
      setError(problem?.detail ?? problem?.title ?? (e instanceof Error ? e.message : 'Could not start picking.'))
    }
  }
  const cancel = () => {
    const id = picking
    setPicking(undefined)
    if (id && deviceId) devicesApi.cancelCommand(deviceId, id).catch(() => {})
  }
  const apply = useMutation({
    mutationFn: (commandId: string) => webApi.applyPickedFix(fix.id, commandId),
    onSuccess: r => onApplied(r.version),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'The fix could not be applied'),
  })

  const label = picked?.result.element && typeof picked.result.element.label === 'string' ? picked.result.element.label : 'The element you picked'
  return (
    <div className="mt-2 rounded-lg bg-white px-2.5 py-2 dark:bg-gray-900">
      <p className="text-xs font-medium text-gray-900 dark:text-gray-100">None of these? Pick it on the page.</p>
      {picking ? (
        <div className="mt-1.5 flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          <span className="flex-1">Click the right element in your browser… Press Esc there to cancel.</span>
          <Button type="button" size="sm" variant="secondary" onClick={cancel}>Cancel</Button>
        </div>
      ) : picked ? (
        <div className="mt-1.5 flex items-start gap-2">
          {deviceId && <ElementPreview deviceId={deviceId} preview={picked.result.preview} pagePreview={picked.result.pagePreview}
            className="max-h-16 rounded border border-gray-200" />}
          <div className="min-w-0 flex-1">
            <p className="truncate text-xs text-gray-700 dark:text-gray-300">{label}</p>
            <div className="mt-1 flex gap-2">
              <Button type="button" size="sm" loading={apply.isPending} onClick={() => { setError(''); apply.mutate(picked.commandId) }}>Use this element</Button>
              <Button type="button" size="sm" variant="ghost" onClick={start}>Pick again</Button>
            </div>
          </div>
        </div>
      ) : (
        <div className="mt-1.5 flex flex-wrap items-center gap-2">
          {online.length > 1 && (
            <select aria-label="Browser to pick in" value={chosen} onChange={e => setChosen(e.target.value)}
              className="rounded-md border border-gray-300 bg-transparent px-2 py-1 text-xs dark:border-gray-600">
              <option value="">Choose a browser…</option>
              {online.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          )}
          <Button type="button" size="sm" variant="secondary" disabled={!deviceId} onClick={start}>
            <MousePointerClick className="h-3.5 w-3.5" />Pick on page
          </Button>
          {online.length === 0 && <span className="text-[11px] text-gray-500">No browser agent is online.</span>}
        </div>
      )}
      {error && <p role="alert" className="mt-1.5 text-xs text-red-600">{error}</p>}
    </div>
  )
}
