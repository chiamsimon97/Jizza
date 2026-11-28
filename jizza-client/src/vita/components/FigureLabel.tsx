import { AlertTriangle, BadgeCheck, HelpCircle, Stethoscope } from 'lucide-react'
import type { ConfigState } from '@/types/vita'
import { formatDay } from '@/portfolio/components/AsOf'

/** The yellow badge on every Vita default nobody has checked against its source yet. */
export function NeedsVerification() {
  return (
    <span title="Vita default, not yet checked against its named source"
      className="inline-flex shrink-0 items-center gap-1 rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300">
      <AlertTriangle className="h-3 w-3" aria-hidden />Needs verification
    </span>
  )
}

/**
 * Where a clinical figure comes from, always in words (spec 8): "Vita default" with the
 * needs-verification badge, "from Dr …, set on …", or "Not set: ask your doctor". The words are the
 * server's; this only lays them out.
 */
export function FigureLabel({ state, label, doctor, setOn }: {
  state: ConfigState | 'doctor'
  /** The label the server sent; used as is for Vita's own figures. */
  label?: string
  doctor?: string
  setOn?: string | null
}) {
  if (state === 'doctor') {
    return (
      <span className="inline-flex flex-wrap items-center gap-1 text-xs text-gray-600 dark:text-gray-300">
        <Stethoscope className="h-3 w-3 text-brand-600 dark:text-brand-300" aria-hidden />
        from {doctor}{setOn ? `, set ${formatDay(setOn)}` : ''}
      </span>
    )
  }
  if (state === 'not_set') {
    return (
      <span className="inline-flex items-center gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
        <HelpCircle className="h-3 w-3" aria-hidden />{label ?? 'Not set: ask your doctor'}
      </span>
    )
  }
  const words = (label ?? 'Vita default').replace(/\s*·\s*needs verification$/i, '')
  return (
    <span className="inline-flex flex-wrap items-center gap-1 text-xs text-gray-600 dark:text-gray-300">
      {state === 'verified' && <BadgeCheck className="h-3 w-3 text-green-600 dark:text-green-400" aria-hidden />}
      {words}
      {state === 'needs_verification' && <NeedsVerification />}
    </span>
  )
}
