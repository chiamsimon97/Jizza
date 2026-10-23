import { useMemo, useState } from 'react'
import { ChevronDown, ChevronRight, Copy } from 'lucide-react'

/** A tool's result as data: text that holds JSON is read as JSON. */
export function asData(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const t = value.trim()
  if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
    try { return JSON.parse(t) } catch { /* text that only looks like JSON */ }
  }
  return value
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

/** A list whose items are objects reads best as a table, as in n8n and Make. */
function tableOf(value: unknown): { rows: Record<string, unknown>[]; columns: string[] } | null {
  const rows = Array.isArray(value) ? value : null
  if (!rows || rows.length === 0 || !rows.every(isRecord)) return null
  const columns: string[] = []
  for (const row of rows.slice(0, 50)) for (const k of Object.keys(row)) if (!columns.includes(k)) columns.push(k)
  return { rows, columns }
}

function cell(v: unknown): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

type View = 'table' | 'tree' | 'raw'

/**
 * A tool's result three ways: a table for a list of records, a tree to unfold, and the raw JSON.
 * Clicking a field in the tree copies its path, which is what a workflow step maps.
 */
export function ResultView({ value, onPath }: { value: unknown; onPath?: (path: string) => void }) {
  const data = useMemo(() => asData(value), [value])
  const table = useMemo(() => tableOf(data), [data])
  const structured = typeof data === 'object' && data !== null
  const [view, setView] = useState<View>(table ? 'table' : structured ? 'tree' : 'raw')
  const raw = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
  const views: View[] = [...(table ? ['table' as const] : []), ...(structured ? ['tree' as const] : []), 'raw']
  const summary = Array.isArray(data) ? `${data.length} item${data.length === 1 ? '' : 's'}`
    : isRecord(data) ? `${Object.keys(data).length} fields` : 'Text'
  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-800">
      <div className="flex items-center gap-1 border-b border-gray-200 px-2 py-1.5 text-xs dark:border-gray-800">
        {views.map(v => (
          <button key={v} type="button" onClick={() => setView(v)}
            className={`rounded px-2 py-1 capitalize ${view === v ? 'bg-brand-50 font-medium text-brand-700 dark:bg-brand-500/10 dark:text-brand-200' : 'text-gray-500 hover:text-gray-800 dark:hover:text-gray-200'}`}>
            {v}
          </button>
        ))}
        <span className="ml-2 text-gray-400">{summary}</span>
        <button type="button" className="ml-auto inline-flex items-center gap-1 rounded px-2 py-1 text-gray-500 hover:text-gray-800 dark:hover:text-gray-200"
          onClick={() => void navigator.clipboard?.writeText(raw)}>
          <Copy className="h-3.5 w-3.5" /> Copy
        </button>
      </div>
      <div className="max-h-[28rem] overflow-auto">
        {view === 'table' && table && (
          <table className="min-w-full text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-gray-900">
              <tr>
                <th className="px-2 py-1.5 text-left font-medium text-gray-400">#</th>
                {table.columns.map(c => <th key={c} className="whitespace-nowrap px-2 py-1.5 text-left font-medium text-gray-600 dark:text-gray-300">{c}</th>)}
              </tr>
            </thead>
            <tbody>
              {table.rows.slice(0, 200).map((row, i) => (
                <tr key={i} className="border-t border-gray-100 dark:border-gray-800">
                  <td className="px-2 py-1 text-gray-400">{i + 1}</td>
                  {table.columns.map(c => <td key={c} className="max-w-[16rem] truncate whitespace-nowrap px-2 py-1 text-gray-800 dark:text-gray-200" title={cell(row[c])}>{cell(row[c])}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {view === 'tree' && <div className="p-2 font-mono text-xs"><Tree value={data} path="" depth={0} onPath={onPath} /></div>}
        {view === 'raw' && <pre className="whitespace-pre-wrap break-words p-3 text-xs text-gray-800 dark:text-gray-200">{raw}</pre>}
      </div>
    </div>
  )
}

function Tree({ value, path, depth, name, onPath }: { value: unknown; path: string; depth: number; name?: string; onPath?: (path: string) => void }) {
  const [open, setOpen] = useState(depth < 1)
  const label = name !== undefined && (
    <button type="button" className="text-violet-700 hover:underline dark:text-violet-300" title={onPath ? `Use ${path || 'the whole result'}` : path}
      onClick={() => onPath?.(path)}>{name}</button>
  )
  if (value === null || typeof value !== 'object') {
    const shown = typeof value === 'string' ? `"${value}"` : String(value)
    const colour = typeof value === 'string' ? 'text-emerald-700 dark:text-emerald-300' : typeof value === 'number' ? 'text-sky-700 dark:text-sky-300' : 'text-amber-700 dark:text-amber-300'
    return <div className="flex gap-1 py-0.5 pl-4">{label}{label && <span className="text-gray-400">:</span>}<span className={`break-all ${colour}`}>{shown}</span></div>
  }
  const entries: Array<[string, unknown, string]> = Array.isArray(value)
    ? value.slice(0, 200).map((v, i) => [`[${i}]`, v, `${path}[${i}]`])
    : Object.entries(value).map(([k, v]) => [k, v, path ? `${path}.${k}` : k])
  const size = Array.isArray(value) ? `[${value.length}]` : `{${entries.length}}`
  return (
    <div>
      <div className="flex items-center gap-1 py-0.5">
        <button type="button" aria-label={open ? 'Fold' : 'Unfold'} onClick={() => setOpen(!open)} className="text-gray-400">
          {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        </button>
        {label}<span className="text-gray-400">{size}</span>
      </div>
      {open && <div className="ml-3 border-l border-gray-100 pl-1 dark:border-gray-800">
        {entries.map(([k, v, p]) => <Tree key={k} name={k} value={v} path={p} depth={depth + 1} onPath={onPath} />)}
      </div>}
    </div>
  )
}

/** One field of a JSON Schema, as a workflow step can map it. */
export interface SchemaField { path: string; type: string }

/**
 * The fields a JSON Schema describes, as paths a later step maps: holdings[0].symbol for the first
 * item of a list. Three levels deep, as far as anyone maps by hand.
 */
export function schemaFields(schema: unknown, prefix = '', depth = 3): SchemaField[] {
  if (!isRecord(schema) || depth === 0) return []
  if (schema.type === 'array' && isRecord(schema.items)) {
    const first = `${prefix}[0]`
    return [{ path: first, type: String(schema.items.type ?? 'value') }, ...schemaFields(schema.items, first, depth - 1)]
  }
  if (!isRecord(schema.properties)) return []
  return Object.entries(schema.properties).slice(0, 60).flatMap(([k, v]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) return []
    const path = prefix ? `${prefix}.${k}` : k
    const type = isRecord(v) ? String(v.type ?? 'value') : 'value'
    return [{ path, type }, ...schemaFields(v, path, depth - 1)]
  })
}
