import { memo, useEffect, useRef, useState } from 'react'
import { Handle, Position, type NodeProps } from 'reactflow'
import { AlertCircle, ChevronDown, ChevronRight, Lock, LockOpen, MoreVertical, Pencil } from 'lucide-react'
import type { StepGroup } from './groupModel'
import type { MenuAt, StepRun } from './canvasParts'

export interface GroupData {
  group: StepGroup
  /** How many of its steps have something to fix. */
  issues: number
  /** How its steps did in the run shown on the canvas, for the folded card. */
  run?: StepRun
  readOnly: boolean
  /** Whether it is folded right now (on a read-only canvas this is only for the viewer). */
  collapsed: boolean
  onToggleFold: () => void
  onToggleLock?: () => void
  onRename?: (title: string) => void
  /** Changes whenever the title should open for typing (Rename in a menu, or a group just made). */
  renameAsk?: number
  onMenu?: (at: MenuAt) => void
}

/** The class that makes a group's title bar the part you drag it by. */
export const GROUP_DRAG = 'jizza-group-drag'

const RUN_TEXT: Record<StepRun, string> = {
  done: 'Done in the last run', failed: 'A step failed in the last run', skipped: 'Skipped in the last run',
  running: 'Running now', waiting: 'Waiting for you',
}

/**
 * A step group on the canvas: a titled box around its steps, or, folded, one card that stands in
 * for them. Only the title bar takes the pointer, so wires and steps inside stay clickable.
 */
export const GroupNode = memo(function GroupNode({ data }: NodeProps<GroupData>) {
  const { group, issues, run, readOnly, collapsed, onToggleFold, onToggleLock, onRename, renameAsk, onMenu } = data
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(group.title)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => { if (!editing) setTitle(group.title) }, [group.title, editing])
  useEffect(() => { if (editing) input.current?.select() }, [editing])
  useEffect(() => { if (renameAsk && onRename) setEditing(true) }, [renameAsk]) // eslint-disable-line react-hooks/exhaustive-deps
  const finish = (keep: boolean) => {
    setEditing(false)
    if (keep && onRename && title.trim() !== group.title) onRename(title.trim())
  }
  const count = `${group.nodes.length} ${group.nodes.length === 1 ? 'step' : 'steps'}`
  const frame = group.locked
    ? 'border-amber-500/70 bg-amber-500/[0.04] dark:border-amber-400/60'
    : 'border-dashed border-brand-500/60 bg-brand-500/[0.04]'
  const bar = group.locked
    ? 'bg-amber-100 text-amber-900 dark:bg-amber-500/20 dark:text-amber-100'
    : 'bg-brand-50 text-brand-900 dark:bg-brand-500/15 dark:text-brand-100'

  return (
    <div data-testid={`group-${group.id}`} className={`relative h-full w-full rounded-2xl border-2 ${frame} ${collapsed ? 'bg-white shadow-sm dark:bg-gray-900' : ''}`}
      style={{ pointerEvents: collapsed ? 'all' : 'none' }}>
      {collapsed && (
        <Handle type="target" position={Position.Left} id="in" isConnectable={false}
          className="!h-3.5 !w-3.5 !rounded-full !border-2 !border-white !bg-gray-400 dark:!border-gray-900" />
      )}
      <div className={`${GROUP_DRAG} flex h-[34px] cursor-grab items-center gap-1 rounded-t-[14px] px-1.5 text-xs font-semibold active:cursor-grabbing ${bar}`}
        style={{ pointerEvents: 'all' }}
        onDoubleClick={e => { if (onRename) { e.stopPropagation(); setEditing(true) } }}>
        <button type="button" onClick={e => { e.stopPropagation(); onToggleFold() }}
          aria-label={collapsed ? `Unfold ${group.title || 'group'}` : `Fold ${group.title || 'group'}`} title={collapsed ? 'Show its steps' : 'Fold into one card'}
          className="nodrag grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10">
          {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </button>
        {editing ? (
          <input ref={input} value={title} aria-label="Group title" maxLength={80}
            onChange={e => setTitle(e.target.value)} onBlur={() => finish(true)}
            onKeyDown={e => { if (e.key === 'Enter') finish(true); else if (e.key === 'Escape') { e.stopPropagation(); finish(false) } }}
            className="nodrag min-w-0 flex-1 rounded bg-white px-1.5 py-0.5 text-xs font-semibold text-gray-900 outline-none ring-1 ring-brand-500 dark:bg-gray-900 dark:text-gray-100" />
        ) : (
          <span className="min-w-0 flex-1 truncate" title={onRename ? `${group.title || 'Untitled group'} (double-click to rename)` : group.title}>
            {group.title || 'Untitled group'}
          </span>
        )}
        {onRename && !editing && (
          <button type="button" onClick={e => { e.stopPropagation(); setEditing(true) }}
            aria-label={`Rename ${group.title || 'group'}`} title="Rename"
            className="nodrag grid h-6 w-6 shrink-0 place-items-center rounded opacity-70 hover:bg-black/10 hover:opacity-100">
            <Pencil className="h-3.5 w-3.5" />
          </button>
        )}
        {!collapsed && <span className="shrink-0 font-normal opacity-70">{count}</span>}
        {issues > 0 && collapsed && (
          <span title={`${issues} ${issues === 1 ? 'step has' : 'steps have'} something to fix`} className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-red-500 text-white">
            <AlertCircle className="h-3.5 w-3.5" />
          </span>
        )}
        {onToggleLock ? (
          <button type="button" onClick={e => { e.stopPropagation(); onToggleLock() }}
            aria-label={group.locked ? 'Unlock group' : 'Lock group'} aria-pressed={group.locked}
            title={group.locked ? 'Locked: its steps, settings and wiring can’t be changed. Click to unlock.' : 'Lock, so its steps and wiring can’t be changed by mistake'}
            className="nodrag grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10">
            {group.locked ? <Lock className="h-3.5 w-3.5" /> : <LockOpen className="h-3.5 w-3.5 opacity-70" />}
          </button>
        ) : group.locked && <Lock className="mx-1 h-3.5 w-3.5 shrink-0" aria-label="Locked" />}
        {onMenu && !readOnly && (
          <button type="button" aria-label={`More options for ${group.title || 'group'}`} title="More options" aria-haspopup="menu"
            onClick={e => {
              e.stopPropagation()
              const r = e.currentTarget.getBoundingClientRect()
              onMenu({ x: r.right + 6, y: r.top, flipX: r.left - 6 })
            }}
            className="nodrag grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10">
            <MoreVertical className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      {collapsed && (
        <div className="flex items-center gap-1.5 px-3 py-2 text-xs text-gray-600 dark:text-gray-300">
          <span>{count}</span>
          {group.locked && <span className="text-amber-700 dark:text-amber-300">· locked</span>}
          {run && <span className={`ml-auto rounded-full px-1.5 text-[10px] font-medium ${run === 'failed' ? 'bg-red-100 text-red-700' : run === 'done' ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-600'}`}
            title={RUN_TEXT[run]}>{run}</span>}
        </div>
      )}
      {collapsed && (
        <Handle type="source" position={Position.Right} id="out" isConnectable={false}
          className="!h-3.5 !w-3.5 !rounded-full !border-2 !border-white !bg-gray-500 dark:!border-gray-900" />
      )}
    </div>
  )
})

/** One state for a folded group from its steps' states: a failure or a wait shows first. */
export function groupRun(states: Array<StepRun | undefined>): StepRun | undefined {
  const s = states.filter((x): x is StepRun => !!x)
  if (!s.length) return undefined
  for (const k of ['failed', 'running', 'waiting'] as const) if (s.includes(k)) return k
  if (s.every(x => x === 'skipped')) return 'skipped'
  return 'done'
}
