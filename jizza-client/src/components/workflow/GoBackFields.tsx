import { fieldClass } from '@/components/ui/SlideOver'
import type { CanvasGraph, CanvasNode } from './canvasModel'

type SetConfig = (patch: Record<string, unknown>, key?: string) => void

/** Steps whose question (an approval card, a failed-step card) can offer Back. */
export const GOES_BACK = new Set(['DEVICE_ACTION', 'APPROVAL', 'CONNECTOR_TOOL', 'MCP_TOOL'])

/** Steps a run can go back to: they do something, unlike the trigger and the path plumbing. */
const NOT_A_STEP = new Set(['TRIGGER', 'PARALLEL', 'JOIN', 'END'])

/** The steps before this one on any path into it, nearest first. */
export function stepsBefore(graph: CanvasGraph, id: string): CanvasNode[] {
  const seen = new Set<string>()
  const order: string[] = []
  let frontier = [id]
  while (frontier.length) {
    const next: string[] = []
    for (const at of frontier) {
      for (const e of graph.edges) {
        if (e.to !== at || seen.has(e.from) || e.from === id) continue
        seen.add(e.from)
        order.push(e.from)
        next.push(e.from)
      }
    }
    frontier = next
  }
  return order.map(n => graph.nodes.find(x => x.id === n))
    .filter((n): n is CanvasNode => !!n && !NOT_A_STEP.has(n.type))
}

/**
 * Allow going back: the step's question offers Back beside Approve, which runs an earlier step
 * again (asking afresh with what the page lists now) so the person can choose differently, such
 * as another train when this page shows no seats.
 */
export function GoBackFields({ node, graph, setConfig }: { node: CanvasNode; graph: CanvasGraph; setConfig: SetConfig }) {
  const on = node.config.allowBack === true
  const earlier = stepsBefore(graph, node.id)
  const backTo = typeof node.config.backTo === 'string' ? node.config.backTo : ''
  const pages = typeof node.config.backPages === 'number' ? node.config.backPages : 0
  const browser = node.type === 'DEVICE_ACTION'
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-gray-200 p-2.5 dark:border-gray-800">
      <label className="flex items-center gap-2 text-xs font-medium text-gray-700 dark:text-gray-300">
        <input type="checkbox" checked={on}
          onChange={e => setConfig({ allowBack: e.target.checked || undefined, backTo: undefined, backPages: undefined }, 'allowBack')} />
        Allow going back
      </label>
      <p className="text-[11px] text-gray-500">
        Shows Back beside Approve on this step's notification. Back runs an earlier step again and asks you there, so you can choose
        something else (another train or coach when this page shows none available). The steps after it run again too.
      </p>
      {on && <>
        <label className="flex flex-col gap-1 text-xs text-gray-700 dark:text-gray-300">
          Go back to
          <select aria-label="Go back to" className={fieldClass} value={backTo}
            onChange={e => setConfig({ backTo: e.target.value || undefined }, 'backTo')}>
            <option value="">The steps that asked me (latest first, I pick on the card)</option>
            {earlier.map(n => <option key={n.id} value={n.id}>{n.label || n.id}</option>)}
          </select>
        </label>
        {backTo && !earlier.some(n => n.id === backTo) && (
          <p className="text-[11px] text-amber-700 dark:text-amber-300">That step is no longer before this one. Choose another.</p>
        )}
        {browser && (
          <label className="flex flex-col gap-1 text-xs text-gray-700 dark:text-gray-300">
            In the browser first
            <select aria-label="In the browser first" className={fieldClass} value={String(pages)}
              onChange={e => setConfig({ backPages: Number(e.target.value) || undefined }, 'backPages')}>
              <option value="0">Stay on this page (the list is still here)</option>
              <option value="1">Go back one page</option>
              <option value="2">Go back two pages</option>
              <option value="3">Go back three pages</option>
            </select>
          </label>
        )}
      </>}
    </div>
  )
}
