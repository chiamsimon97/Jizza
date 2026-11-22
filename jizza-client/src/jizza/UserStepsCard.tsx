import { useState } from 'react'
import { useInRouterContext, useNavigate } from 'react-router-dom'
import { ArrowRight, Check, ExternalLink, ListChecks, Send } from 'lucide-react'
import type { UserStep, UserSteps } from '@/types'

/** Only absolute web links go on a button; anything else is shown as text. */
export function safeLink(url: string): boolean {
  try {
    const u = new URL(url)
    return u.protocol === 'https:' || u.protocol === 'http:'
  } catch {
    return false
  }
}

/** Only the app's own connector and device pages can be opened by a page button. */
export function safePage(path: string): boolean {
  return /^\/(connectors\/|settings\/devices)[^\s]*$/.test(path) && !path.includes('//')
}

/** Opens a page of the app: in place when inside the app's router, else by a normal load. */
function usePageOpener(): (path: string) => void {
  const inRouter = useInRouterContext()
  // Same branch on every render of a card, so the hook order never changes.
  // eslint-disable-next-line react-hooks/rules-of-hooks
  const navigate = inRouter ? useNavigate() : null
  return path => (navigate ? navigate(path) : window.location.assign(path))
}

/** The links on a reply's cards, so the reply's text need not show them a second time. */
export function cardLinks(cards: UserSteps[] | undefined): string[] {
  return (cards ?? []).flatMap(c => c.steps.flatMap(s => (s.button?.kind === 'link' ? [s.button.url] : [])))
}

const saved = (key?: string): number[] => {
  if (!key) return []
  try {
    const v = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(v) ? v.filter((n): n is number => typeof n === 'number') : []
  } catch {
    return []
  }
}

/**
 * Steps only the user can take, such as logging in to a service before an agent can carry on.
 * Each step has its button: a link opens in a new tab, a reply sends the user's "done" for them.
 * The step to do next is highlighted and each one is ticked as its button is used.
 */
export function UserStepsCard({ card, onSend, storageKey, disabled }: {
  card: UserSteps
  onSend: (text: string) => void
  /** Where the ticks are remembered, so a reopened conversation shows what was done. */
  storageKey?: string
  /** True while a reply is running: a reply button waits for it. */
  disabled?: boolean
}) {
  const [done, setDone] = useState<number[]>(() => saved(storageKey))
  const openPage = usePageOpener()
  const mark = (i: number) => setDone(d => {
    if (d.includes(i)) return d
    const next = [...d, i]
    if (storageKey) { try { localStorage.setItem(storageKey, JSON.stringify(next)) } catch { /* ticks are a convenience */ } }
    return next
  })
  const current = card.steps.findIndex((_, i) => !done.includes(i))

  return (
    <div className="rounded-2xl border border-brand-300 bg-brand-50/60 p-3 animate-fade-in-up dark:border-brand-700 dark:bg-brand-500/10" role="group" aria-label={card.title}>
      <p className="flex items-center gap-2 text-sm font-semibold text-gray-900 dark:text-gray-100">
        <ListChecks className="h-4 w-4 shrink-0 text-brand-600 dark:text-brand-300" />
        {card.title}
      </p>
      {card.intro && <p className="ml-6 mt-0.5 text-xs text-gray-600 dark:text-gray-400">{card.intro}</p>}
      <ol className="mt-2.5 flex flex-col gap-2">
        {card.steps.map((step, i) => (
          <StepRow key={i} n={i + 1} step={step} done={done.includes(i)} next={i === current}
            disabled={disabled} onUse={() => {
              if (step.button?.kind === 'link') {
                if (!safeLink(step.button.url)) return
                window.open(step.button.url, '_blank', 'noopener,noreferrer')
              } else if (step.button?.kind === 'reply') {
                onSend(step.button.text)
              } else if (step.button?.kind === 'page') {
                if (!safePage(step.button.path)) return
                openPage(step.button.path)
              }
              mark(i)
            }} onTick={() => mark(i)} />
        ))}
      </ol>
    </div>
  )
}

function StepRow({ n, step, done, next, disabled, onUse, onTick }: {
  n: number; step: UserStep; done: boolean; next: boolean; disabled?: boolean; onUse: () => void; onTick: () => void
}) {
  const button = step.button
  const link = button?.kind === 'link' && safeLink(button.url)
  const reply = button?.kind === 'reply'
  const page = button?.kind === 'page' && safePage(button.path)
  return (
    <li className={`flex items-start gap-2.5 rounded-xl px-2 py-1.5 transition-colors ${next ? 'bg-white shadow-sm ring-1 ring-brand-200 dark:bg-gray-900 dark:ring-brand-800' : ''}`}>
      <span aria-hidden className={`mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full text-[11px] font-semibold ${done
        ? 'bg-brand-500 text-white'
        : next ? 'border border-brand-500 text-brand-700 dark:text-brand-300' : 'border border-gray-300 text-gray-500 dark:border-gray-700 dark:text-gray-400'}`}>
        {done ? <Check className="h-3 w-3" /> : n}
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div>
          <p className={`text-sm font-medium ${done ? 'text-gray-500 line-through decoration-gray-300 dark:text-gray-500' : 'text-gray-900 dark:text-gray-100'}`}>{step.label}</p>
          {step.detail && <p className="text-xs text-gray-600 dark:text-gray-400">{step.detail}</p>}
        </div>
        {link && (
          <button type="button" onClick={onUse}
            className={`inline-flex w-fit items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium transition-all active:scale-95 ${done
              ? 'border border-brand-300 bg-white text-brand-700 hover:border-brand-500 dark:border-brand-700 dark:bg-gray-900 dark:text-brand-300'
              : 'bg-brand-500 text-white shadow-sm hover:-translate-y-0.5 hover:bg-brand-600'}`}>
            {button.label}
            <ExternalLink className="h-3.5 w-3.5" />
          </button>
        )}
        {reply && (
          <button type="button" onClick={onUse} disabled={done || disabled}
            className={`inline-flex w-fit items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium transition-all active:scale-95 disabled:cursor-not-allowed disabled:opacity-60 ${next
              ? 'bg-brand-500 text-white shadow-sm hover:-translate-y-0.5 hover:bg-brand-600'
              : 'border border-brand-300 bg-white text-brand-700 hover:border-brand-500 dark:border-brand-700 dark:bg-gray-900 dark:text-brand-300'}`}>
            <Send className="h-3.5 w-3.5" />
            {done ? 'Sent' : button.label}
          </button>
        )}
        {page && (
          <button type="button" onClick={onUse}
            className={`inline-flex w-fit items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium transition-all active:scale-95 ${done
              ? 'border border-brand-300 bg-white text-brand-700 hover:border-brand-500 dark:border-brand-700 dark:bg-gray-900 dark:text-brand-300'
              : 'bg-brand-500 text-white shadow-sm hover:-translate-y-0.5 hover:bg-brand-600'}`}>
            {button.label}
            <ArrowRight className="h-3.5 w-3.5" />
          </button>
        )}
        {!link && !reply && !page && !done && (
          <button type="button" onClick={onTick}
            className="inline-flex w-fit items-center gap-1 text-xs font-medium text-gray-500 hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300">
            <Check className="h-3 w-3" /> Mark as done
          </button>
        )}
      </div>
    </li>
  )
}
