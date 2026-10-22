import { useState } from 'react'
import { Check, ChevronRight, Copy } from 'lucide-react'

const MAX_INLINE = 60

function preview(value: unknown): string {
  if (Array.isArray(value)) return `[${value.length}]`
  if (value && typeof value === 'object') return `{${Object.keys(value).length}}`
  return String(value)
}

function Leaf({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <span className="text-gray-400">null</span>
  if (typeof value === 'string') {
    return <span className="whitespace-pre-wrap break-words text-emerald-700 dark:text-emerald-400">"{value}"</span>
  }
  if (typeof value === 'number') return <span className="text-sky-700 dark:text-sky-400">{value}</span>
  if (typeof value === 'boolean') return <span className="text-violet-700 dark:text-violet-400">{String(value)}</span>
  return <span>{String(value)}</span>
}

function Node({ name, value, depth }: { name?: string; value: unknown; depth: number }) {
  const branch = value !== null && typeof value === 'object'
  const [open, setOpen] = useState(depth < 2)
  const entries = branch ? Object.entries(value as Record<string, unknown>) : []

  return (
    <div className="leading-relaxed">
      <div className="flex items-start gap-1">
        {branch ? (
          <button type="button" onClick={() => setOpen(o => !o)} className="mt-0.5 text-gray-400 hover:text-gray-700 dark:hover:text-gray-200" aria-label={open ? 'Collapse' : 'Expand'}>
            <ChevronRight className={`h-3 w-3 transition-transform ${open ? 'rotate-90' : ''}`} />
          </button>
        ) : <span className="w-3 shrink-0" />}
        <div className="min-w-0">
          {name != null && <span className="text-gray-500 dark:text-gray-400">{name}: </span>}
          {branch
            ? <button type="button" onClick={() => setOpen(o => !o)} className="text-gray-400 hover:text-gray-600">{preview(value)}</button>
            : <Leaf value={value} />}
        </div>
      </div>
      {branch && open && (
        <div className="ml-1.5 border-l border-gray-200 pl-3 dark:border-gray-800">
          {entries.length === 0 && <span className="text-gray-400">empty</span>}
          {entries.map(([k, v]) => <Node key={k} name={k} value={v} depth={depth + 1} />)}
        </div>
      )}
    </div>
  )
}

/** Data a step took or gave, as a tree you can fold, with a copy button. */
export function JsonView({ value, empty = 'Nothing recorded' }: { value: unknown; empty?: string }) {
  const [copied, setCopied] = useState(false)
  if (value === undefined || value === null || (typeof value === 'object' && Object.keys(value as object).length === 0)) {
    return <p className="rounded-lg bg-gray-50 px-3 py-2 text-xs text-gray-400 dark:bg-gray-950/60">{empty}</p>
  }
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  const simple = typeof value !== 'object' && text.length < MAX_INLINE

  return (
    <div className="group relative rounded-lg bg-gray-50 px-3 py-2 font-mono text-xs dark:bg-gray-950/60">
      <button
        type="button"
        onClick={() => { void navigator.clipboard?.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1200) }}
        className="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-md text-gray-400 opacity-0 transition-opacity hover:bg-gray-200 hover:text-gray-700 group-hover:opacity-100 dark:hover:bg-gray-800"
        aria-label="Copy"
      >
        {copied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
      </button>
      {simple ? <Leaf value={value} /> : <div className="max-h-96 overflow-auto pr-6"><Node value={value} depth={0} /></div>}
    </div>
  )
}
