import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { formatDay } from '@/portfolio/components/AsOf'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { InsightFactor, InsightField, InsightStatement } from '@/types/vita'
import { vitaKeys } from '../queryKeys'
import { ENERGY_WORDS, MOOD_WORDS } from './Journal'

const FIELD: Record<InsightField, string> = { mood: 'Mood', energy: 'Energy', pain: 'Pain' }
const FACTOR: Record<InsightFactor, string> = { sleep: 'Sleep', steps: 'Steps', missed_doses: 'Missed doses', outside_range: 'Readings outside range' }
const num = (n: number | null | undefined, digits = 1) => (n == null ? '—' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: digits }))

function fieldValue(f: InsightField, v: number | undefined): string {
  if (v == null) return '—'
  const words = f === 'mood' ? MOOD_WORDS : f === 'energy' ? ENERGY_WORDS : null
  const w = words && Number.isInteger(v) ? words[v - 1] : null
  return f === 'pain' ? `${num(v)} of 10` : w ? `${num(v)}, ${w.toLowerCase()}` : `${num(v)} of 5`
}
function factorValue(f: InsightFactor, v: number | undefined): string {
  if (v == null) return '—'
  switch (f) {
    case 'sleep': return `${num(v)} h`
    case 'steps': return num(v, 0)
    default: return num(v, 0)
  }
}

/**
 * "Goes with" (spec 5.9): what in the journal goes together with sleep, steps, missed doses or
 * readings outside range, in the server's words, with the days behind each statement. Silent, with
 * the reason, until the journal has the number of days Settings asks for. Never "causes".
 */
export function Insights({ personId, compact = false }: { personId: string; compact?: boolean }) {
  const q = useQuery({ queryKey: vitaKeys.insights(personId), queryFn: () => vitaApi.insights(personId), retry: false })
  const d = q.data
  return (
    <section aria-label="Goes with">
      <Panel title={<><Sparkles className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Goes with</>}>
        {q.isLoading && <SkeletonRows rows={2} />}
        {q.error && <LoadFailed text="Could not load what goes together." onRetry={() => q.refetch()} />}
        {d && (
          <>
            {d.statements.length === 0
              ? <p className="text-sm text-gray-600 dark:text-gray-300">{d.message}</p>
              : <ul className="flex flex-col gap-3">{d.statements.map((s, i) => <Statement key={i} s={s} compact={compact} />)}</ul>}
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {d.journalDays} of the {d.minJournalDays} journal days needed{d.journalDays >= d.minJournalDays ? ' are there' : ' so far'}, from {formatDay(d.from)} to {formatDay(d.to)}.
            </p>
            {d.note && <p className="text-xs font-medium text-gray-600 dark:text-gray-300">{d.note}</p>}
            <p className="text-xs text-gray-500 dark:text-gray-400">{d.disclaimer}</p>
          </>
        )}
      </Panel>
    </section>
  )
}

function Statement({ s, compact }: { s: InsightStatement; compact: boolean }) {
  const [open, setOpen] = useState(false)
  const sp = s.split
  return (
    <li className="flex flex-col gap-1.5 rounded-lg bg-gray-50 px-3 py-2 dark:bg-gray-800/60">
      <p className="text-sm text-gray-900 dark:text-gray-100">{s.text}</p>
      <p className="text-xs text-gray-600 dark:text-gray-300">
        Over {s.days} days. On the {sp.daysAbove} days with {FACTOR[s.factor].toLowerCase()} above {factorValue(s.factor, sp.factorMedian)}, {FIELD[s.journalField].toLowerCase()} averaged {num(sp.journalAverageAbove)};
        on the other {sp.daysAtOrBelow}, {num(sp.journalAverageAtOrBelow)}.
      </p>
      {!compact && s.data.length > 0 && (
        <>
          <button type="button" aria-expanded={open} onClick={() => setOpen(o => !o)} className="self-start text-xs text-brand-700 underline dark:text-brand-300">
            {open ? 'Hide the days' : `Show the ${s.data.length} days`}
          </button>
          {open && (
            <div className="max-h-64 overflow-auto">
              <table className="w-full text-left text-xs">
                <thead className="text-gray-500 dark:text-gray-400">
                  <tr><th className="py-1 font-medium">Day</th><th className="py-1 font-medium">{FIELD[s.journalField]}</th><th className="py-1 font-medium">{FACTOR[s.factor]}</th></tr>
                </thead>
                <tbody className="text-gray-800 dark:text-gray-100">
                  {s.data.map(r => (
                    <tr key={r.date} className="border-t border-gray-100 dark:border-gray-800">
                      <td className="py-1">{formatDay(r.date)}</td>
                      <td className="py-1">{fieldValue(s.journalField, r[s.journalField])}</td>
                      <td className="py-1">{factorValue(s.factor, r[s.factor])}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </li>
  )
}
