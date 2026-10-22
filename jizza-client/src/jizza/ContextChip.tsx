import { useLocation } from 'react-router-dom'
import { LayoutDashboard, Network, Settings2, Sparkles, X } from 'lucide-react'
import { useAssistantStore } from './assistantStore'
import { PLANNER_CONTEXT, pageContext, useContextStore, type JizzaContext } from './contextStore'
import { pageForPath } from './pages'

/** Where Jizza is working, kept current as pages, the Builder and step settings open and close. */
export function useCurrentContext(): { ctx: JizzaContext; token?: number } {
  const location = useLocation()
  const top = useContextStore(s => s.stack[s.stack.length - 1])
  const plannerOpen = useAssistantStore(s => s.plannerOpen)
  if (top) return { ctx: top.ctx, token: top.token }
  return { ctx: plannerOpen ? PLANNER_CONTEXT : pageContext(pageForPath(location.pathname)) }
}

const ICONS = { page: LayoutDashboard, planner: Sparkles, builder: Network, step: Settings2 }

/**
 * "Jizza is looking at: Send webhook step · Notify the bank". Shown over the message box so it is
 * always clear what Jizza's answers are about. A step can be left from here, which hands Jizza back
 * to the Builder.
 */
export function ContextChip({ className = '' }: { className?: string }) {
  const { ctx, token } = useCurrentContext()
  const Icon = ICONS[ctx.kind]
  const focused = ctx.kind === 'step' || ctx.kind === 'builder'
  return (
    <div className={`flex min-w-0 items-center gap-1.5 text-[11px] ${className}`} title="What Jizza is working with. Type / for shortcuts that fit it.">
      <span className="shrink-0 text-gray-400 dark:text-gray-500">Jizza is on</span>
      <span className={`inline-flex min-w-0 items-center gap-1 rounded-full px-2 py-0.5 font-medium ring-1 ring-inset ${
        focused
          ? 'bg-brand-50 text-brand-700 ring-brand-500/25 dark:bg-brand-500/10 dark:text-brand-300'
          : 'bg-gray-100 text-gray-600 ring-gray-500/10 dark:bg-gray-800 dark:text-gray-300'}`} data-testid="jizza-context">
        <Icon className="h-3 w-3 shrink-0" />
        <span className="truncate">{ctx.label}{ctx.detail ? <span className="font-normal opacity-80"> · {ctx.detail}</span> : null}</span>
        {ctx.kind === 'step' && token !== undefined && (
          <button type="button" aria-label="Stop working on this step" title="Stop working on this step"
            onClick={() => useContextStore.getState().pop(token)}
            className="-mr-1 grid h-4 w-4 shrink-0 place-items-center rounded-full hover:bg-brand-500/15"><X className="h-2.5 w-2.5" /></button>
        )}
      </span>
    </div>
  )
}
