import { AlertTriangle, Sparkles, User } from 'lucide-react'

/** Where a value comes from: Kai's default (amber outline) or the person's own ("You", brand). */
export function SourceBadge({ source }: { source: 'kai_default' | 'you' }) {
  return source === 'you'
    ? <span className="inline-flex items-center gap-1 rounded-full bg-brand-100 px-2 py-0.5 text-xs font-medium text-brand-700 dark:bg-brand-900/40 dark:text-brand-200"><User className="h-3 w-3" aria-hidden />You</span>
    : <span className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-amber-700 ring-1 ring-inset ring-amber-500/60 dark:text-amber-300"><Sparkles className="h-3 w-3" aria-hidden />Kai default</span>
}

/** A tax or regulatory figure nobody has confirmed for this financial year. */
export function NeedsVerification() {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300">
      <AlertTriangle className="h-3 w-3" aria-hidden />Needs verification
    </span>
  )
}

/** The line every Portfolio screen ends with. */
export function Disclaimer() {
  return (
    <p className="text-center text-xs text-gray-500 dark:text-gray-400">
      Rule-based analysis of your family’s own money, not advice from a SEBI-registered adviser. Kai proposes; you decide.
    </p>
  )
}
