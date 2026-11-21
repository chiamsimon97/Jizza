import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { AlertCircle, ChevronDown, ChevronRight } from 'lucide-react'

/**
 * Details on demand for a step's settings: the important values stay in view so the step is easy
 * to review, and the rest (selectors, kinds, groups, patterns, timings…) sit behind this toggle.
 *
 * The details stay mounted while folded (only hidden: the `hidden` class too, since a `flex`
 * class would override the hidden attribute in the browser), so a pick in progress or a half-typed
 * value is not lost. When something inside needs fixing (`hasError`), it opens by itself; if the
 * person folds it again, the toggle keeps a "Needs attention" badge so the problem never hides.
 */
function useFold(hasError: boolean | undefined, defaultOpen: boolean | undefined) {
  const [open, setOpen] = useState(Boolean(defaultOpen || hasError))
  const had = useRef(Boolean(hasError))
  useEffect(() => {
    // A new problem opens the details; one that was already there leaves the person's choice.
    if (hasError && !had.current) setOpen(true)
    had.current = Boolean(hasError)
  }, [hasError])
  return [open, setOpen] as const
}

function Attention() {
  return (
    <span className="inline-flex items-center gap-0.5 rounded-full bg-red-50 px-1.5 py-px text-[10px] font-medium text-red-600 dark:bg-red-950/50 dark:text-red-300" data-testid="needs-attention">
      <AlertCircle className="h-3 w-3" aria-hidden /> Needs attention
    </span>
  )
}

/** A small "More options" toggle inside a row or a section, with a one-line summary of what it holds. */
export function MoreOptions({ label = 'More options', summary, hasError, defaultOpen, children }: {
  label?: string
  /** What the hidden part holds, in a few words, such as "Dropdown · Book Ticket". */
  summary?: string
  /** Something inside is missing or wrong: open it, and badge the toggle while folded. */
  hasError?: boolean
  defaultOpen?: boolean
  children: ReactNode
}) {
  const [open, setOpen] = useFold(hasError, defaultOpen)
  const id = useId()
  const Icon = open ? ChevronDown : ChevronRight
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <button type="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(v => !v)}
        className="inline-flex min-w-0 items-center gap-1 self-start text-left text-[11px] text-gray-500 hover:text-gray-800 dark:hover:text-gray-200">
        <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden />
        <span className="font-medium text-brand-600 dark:text-brand-300">{open ? `Hide ${lower(label)}` : label}</span>
        {!open && summary && <span className="min-w-0 truncate">· {summary}</span>}
        {!open && hasError && <Attention />}
      </button>
      <div id={id} hidden={!open} className={open ? 'flex flex-col gap-2 border-l-2 border-gray-100 pl-3 dark:border-gray-800' : 'hidden'}>
        {children}
      </div>
    </div>
  )
}

/** A whole section of the step that folds: its title is the toggle, with a summary beside it. */
export function FoldSection({ title, summary, hasError, defaultOpen, children }: {
  title: string; summary?: string; hasError?: boolean; defaultOpen?: boolean; children: ReactNode
}) {
  const [open, setOpen] = useFold(hasError, defaultOpen)
  const id = useId()
  const Icon = open ? ChevronDown : ChevronRight
  return (
    <section className="flex flex-col gap-2 border-t border-gray-100 pt-3 dark:border-gray-800">
      <button type="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(v => !v)}
        className="flex min-w-0 items-center gap-1 text-left">
        <Icon className="h-3.5 w-3.5 shrink-0 text-gray-400" aria-hidden />
        <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{title}</span>
        {!open && summary && <span className="min-w-0 truncate text-[11px] text-gray-400">· {summary}</span>}
        {!open && hasError && <Attention />}
      </button>
      <div id={id} hidden={!open} className={open ? 'flex flex-col gap-2' : 'hidden'}>
        {children}
      </div>
    </section>
  )
}

const lower = (s: string) => s.charAt(0).toLowerCase() + s.slice(1)

/** "3 headers", "1 header", "none": how many of something a folded part holds. */
export const countOf = (n: number, one: string, many = `${one}s`) => (n === 0 ? 'none' : `${n} ${n === 1 ? one : many}`)
