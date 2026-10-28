import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Wrench } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { webApi, type LocatorFix } from '@/api/web'
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
