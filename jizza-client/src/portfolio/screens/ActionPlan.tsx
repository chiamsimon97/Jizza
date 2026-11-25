import { ListChecks } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import type { ActionLayer, FinAction } from '@/types/finance'
import { ActionCard, LAYER_LABELS } from '../components/ActionCard'
import { SkeletonRows } from '../components/SkeletonRows'
import { useActions } from '../data'

const LAYERS: ActionLayer[] = ['survival', 'important', 'aspirational', 'hygiene']

const LAYER_NOTES: Record<ActionLayer, string> = {
  survival: 'An emergency buffer, high-interest debt and basic cover, in that order.',
  important: 'Retirement, children’s education and other must-have goals. Waits while a survival check is red.',
  aspirational: 'Nice-to-have goals, once important ones run by themselves.',
  hygiene: 'Nominees and a will: small, and easy to forget.',
}

const byRank = (a: FinAction, b: FinAction) => (a.rank ?? 99) - (b.rank ?? 99)

/**
 * The action plan (7.17): every action Kai suggests, grouped by layer and ranked, with three open
 * at a time. The rest wait in Later; snoozed and finished ones are kept below.
 */
export function ActionPlan({ onOpen }: { onOpen: (view: string) => void }) {
  const { data, isLoading, error, refetch } = useActions()
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 p-6 text-center">
        <p className="text-sm text-red-700 dark:text-red-300">Could not load the action plan.</p>
        <Button size="sm" variant="secondary" onClick={() => refetch()}>Try again</Button>
      </div>
    )
  }
  const live = data.filter(a => a.status === 'open' || a.status === 'later' || a.status === 'snoozed')
  const past = data.filter(a => a.status === 'done' || a.status === 'dismissed')
  if (!data.length) {
    return <EmptyState icon={ListChecks} title="No actions yet" description="Kai suggests actions once Quick start has some answers."
      action={{ label: 'Start Quick start', onClick: () => onOpen('quick-start') }} />
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Action plan</h2>
        <p className="text-sm text-gray-500 dark:text-gray-400">Three open at a time, most urgent layer first. The rest wait in Later. Kai suggests; you decide and act.</p>
      </div>
      {LAYERS.map(layer => {
        const rows = live.filter(a => a.layer === layer).sort(byRank)
        const open = rows.filter(a => a.status === 'open')
        const later = rows.filter(a => a.status === 'later')
        const snoozed = rows.filter(a => a.status === 'snoozed')
        return (
          <section key={layer} className="flex flex-col gap-2 rounded-xl border border-gray-200 p-3 dark:border-gray-800" aria-labelledby={`layer-${layer}`}>
            <div className="flex flex-wrap items-baseline gap-2">
              <h3 id={`layer-${layer}`} className="text-sm font-semibold text-gray-900 dark:text-gray-100">{LAYER_LABELS[layer]}</h3>
              {open.length > 0 && <span className="text-xs text-gray-500">{open.length} open</span>}
              <p className="w-full text-xs text-gray-500 dark:text-gray-400">{LAYER_NOTES[layer]}</p>
            </div>
            {open.map(a => <ActionCard key={a.id} action={a} index={a.rank ?? undefined} />)}
            {later.length > 0 && (
              <div className="flex flex-col gap-1">
                <p className="text-xs font-medium uppercase tracking-wide text-gray-500">Later</p>
                <ul className="flex flex-col gap-1">
                  {later.map(a => (
                    <li key={a.id} className="text-sm text-gray-700 dark:text-gray-300">
                      {a.title}<span className="block text-xs text-gray-500 dark:text-gray-400">{a.reason}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {snoozed.map(a => <ActionCard key={a.id} action={a} />)}
            {!rows.length && <p className="text-sm text-gray-500 dark:text-gray-400">Nothing here now.</p>}
          </section>
        )
      })}
      {past.length > 0 && (
        <details className="rounded-xl border border-gray-200 p-3 text-sm dark:border-gray-800">
          <summary className="cursor-pointer font-medium text-gray-700 dark:text-gray-300">Done and set aside ({past.length})</summary>
          <ul className="mt-2 flex flex-col gap-1">
            {past.map(a => (
              <li key={a.id} className="text-gray-600 dark:text-gray-400">
                {a.status === 'done' ? 'Done' : 'Not for me'}: {a.title}{a.dismissReason ? ` (${a.dismissReason})` : ''}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
