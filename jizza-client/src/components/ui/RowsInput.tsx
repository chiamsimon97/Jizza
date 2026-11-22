import { Plus, X } from 'lucide-react'

/** One column of a repeated section, such as a passenger's name or age; a dropdown on the
 *  page (gender, berth) brings its choices, and the table offers them. */
export interface RowsColumn { key: string; label: string; options?: string[] }

/** A column as a run input or a step card gives it, read leniently. */
export function rowsColumnOf(raw: { key?: unknown; name?: unknown; label?: unknown; options?: unknown }): RowsColumn | null {
  const key = typeof raw.key === 'string' && raw.key ? raw.key : typeof raw.name === 'string' ? raw.name : ''
  if (!key) return null
  const options = Array.isArray(raw.options) ? raw.options.map(String).filter(Boolean) : []
  return { key, label: typeof raw.label === 'string' && raw.label ? raw.label : key, ...(options.length ? { options } : {}) }
}

type Entry = Record<string, string>

/** The entries in a rows value: its JSON text, or a list, read leniently. */
export function rowsOf(value: unknown): Entry[] {
  let raw = value
  if (typeof raw === 'string') {
    try {
      raw = raw.trim() ? JSON.parse(raw) : []
    } catch {
      return []
    }
  }
  if (!Array.isArray(raw)) return []
  return raw.filter(r => r && typeof r === 'object' && !Array.isArray(r)).map(r => {
    const out: Entry = {}
    for (const [k, v] of Object.entries(r as Record<string, unknown>)) out[k] = v == null ? '' : String(v)
    return out
  })
}

/** Whether no entry has anything filled in. */
export function rowsEmpty(value: unknown): boolean {
  return !rowsOf(value).some(r => Object.values(r).some(v => v.trim() !== ''))
}

/**
 * A table to fill in a section the page repeats, one row per entry (one per passenger): a column
 * per field, a row per entry, and a button to add another. Its value is the entries as JSON text.
 */
export function RowsInput({ label, columns, value, onChange, readOnly, invalid }: {
  label: string
  columns: RowsColumn[]
  value: unknown
  onChange: (json: string) => void
  readOnly?: boolean
  invalid?: boolean
}) {
  const rows = rowsOf(value)
  const shown = rows.length ? rows : [{}]
  const write = (next: Entry[]) => onChange(JSON.stringify(next))
  const set = (at: number, key: string, text: string) =>
    write(shown.map((r, i) => (i === at ? { ...r, [key]: text } : r)))
  const cell = 'w-full min-w-[6rem] rounded-md border border-gray-300 bg-white px-2 py-1 text-sm dark:border-gray-700 dark:bg-gray-900'
  return (
    <div className={`space-y-2 overflow-x-auto rounded-lg border p-2 ${invalid ? 'border-red-400 dark:border-red-700' : 'border-gray-200 dark:border-gray-800'}`}
      data-testid="rows-input">
      <table className="w-full border-separate border-spacing-1 text-left">
        <thead>
          <tr>
            <th className="w-6 text-[11px] font-medium text-gray-500">#</th>
            {columns.map(c => <th key={c.key} className="text-[11px] font-medium text-gray-600 dark:text-gray-400">{c.label}</th>)}
            <th className="w-6" />
          </tr>
        </thead>
        <tbody>
          {shown.map((row, at) => (
            <tr key={at}>
              <td className="text-xs text-gray-500">{at + 1}</td>
              {columns.map(c => (
                <td key={c.key}>
                  {c.options?.length ? (
                    <select className={cell} aria-label={`${c.label} ${at + 1}`} disabled={readOnly}
                      value={row[c.key] ?? ''} onChange={e => set(at, c.key, e.target.value)}>
                      <option value="">Choose…</option>
                      {/* A value the page no longer lists stays visible instead of being lost. */}
                      {row[c.key] && !c.options.includes(row[c.key]) && <option value={row[c.key]}>{row[c.key]}</option>}
                      {c.options.map(o => <option key={o} value={o}>{o}</option>)}
                    </select>
                  ) : (
                    <input className={cell} aria-label={`${c.label} ${at + 1}`} disabled={readOnly}
                      value={row[c.key] ?? ''} onChange={e => set(at, c.key, e.target.value)} />
                  )}
                </td>
              ))}
              <td>
                {!readOnly && shown.length > 1 && (
                  <button type="button" aria-label={`Remove row ${at + 1}`}
                    className="rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
                    onClick={() => write(shown.filter((_, i) => i !== at))}>
                    <X className="h-3.5 w-3.5" />
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!readOnly && (
        <button type="button" onClick={() => write([...shown, {}])}
          className="inline-flex items-center gap-1 text-xs font-medium text-brand-600 hover:underline dark:text-brand-400">
          <Plus className="h-3.5 w-3.5" />Add {label.replace(/s$/i, '').toLowerCase() || 'row'}
        </button>
      )}
    </div>
  )
}
