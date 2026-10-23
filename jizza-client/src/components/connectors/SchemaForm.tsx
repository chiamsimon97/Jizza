import { fieldClass } from '@/components/ui/SlideOver'

interface Property { type?: string | string[]; description?: string; enum?: unknown[]; default?: unknown; title?: string }

const typeOf = (p: Property) => (Array.isArray(p.type) ? p.type.find(t => t !== 'null') : p.type) ?? 'string'

/** The properties and required names of a tool's input schema. */
export function inputFields(schema: unknown): { properties: Record<string, Property>; required: string[] } {
  const s = (schema ?? {}) as { properties?: Record<string, Property>; required?: string[] }
  return { properties: s.properties ?? {}, required: Array.isArray(s.required) ? s.required : [] }
}

/**
 * A form for a tool's arguments, built from its input schema, as Claude and n8n show a tool: a
 * box per field, a switch for yes/no, a list for a fixed set of values. Lists and objects are
 * typed as JSON. Empty fields are left out of the arguments.
 */
export function SchemaForm({ schema, value, onChange }: { schema: unknown; value: Record<string, unknown>; onChange: (v: Record<string, unknown>) => void }) {
  const { properties, required } = inputFields(schema)
  const set = (k: string, v: unknown) => {
    const next = { ...value }
    if (v === '' || v === undefined) delete next[k]
    else next[k] = v
    onChange(next)
  }
  return (
    <div className="flex flex-col gap-3">
      {Object.entries(properties).map(([k, p]) => {
        const type = typeOf(p)
        const current = value[k]
        const label = (
          <span className="flex items-baseline gap-2 text-sm font-medium text-gray-700 dark:text-gray-300">
            {p.title || k}{required.includes(k) && <span className="text-red-500">*</span>}
            <span className="font-mono text-[11px] font-normal text-gray-400">{type}</span>
          </span>
        )
        const hint = p.description && <span className="text-xs text-gray-400">{p.description}</span>
        if (type === 'boolean') {
          return (
            <label key={k} className="flex items-start gap-2">
              <input type="checkbox" className="mt-1" checked={current === true} onChange={e => set(k, e.target.checked ? true : undefined)} />
              <span className="flex flex-col">{label}{hint}</span>
            </label>
          )
        }
        let input
        if (Array.isArray(p.enum)) {
          input = (
            <select className={fieldClass} value={current === undefined ? '' : String(current)} onChange={e => set(k, e.target.value === '' ? undefined : p.enum!.find(o => String(o) === e.target.value))}>
              <option value="">{p.default !== undefined ? `Default (${String(p.default)})` : 'Not set'}</option>
              {p.enum.map(o => <option key={String(o)} value={String(o)}>{String(o)}</option>)}
            </select>
          )
        } else if (type === 'number' || type === 'integer') {
          input = <input type="number" step={type === 'integer' ? 1 : 'any'} className={fieldClass} placeholder={p.default !== undefined ? String(p.default) : ''}
            value={current === undefined ? '' : String(current)} onChange={e => set(k, e.target.value === '' ? undefined : Number(e.target.value))} />
        } else if (type === 'array' || type === 'object') {
          input = <JsonInput value={current} placeholder={type === 'array' ? '["…"]' : '{ … }'} onChange={v => set(k, v)} />
        } else {
          input = <input className={fieldClass} placeholder={p.default !== undefined ? String(p.default) : ''}
            value={current === undefined ? '' : String(current)} onChange={e => set(k, e.target.value)} />
        }
        return <label key={k} className="flex flex-col gap-1">{label}{input}{hint}</label>
      })}
      {Object.keys(properties).length === 0 && <p className="text-sm text-gray-500">This tool takes no arguments.</p>}
    </div>
  )
}

/** A JSON value typed by hand; it is kept only once it parses. */
function JsonInput({ value, placeholder, onChange }: { value: unknown; placeholder: string; onChange: (v: unknown) => void }) {
  return (
    <textarea className={`${fieldClass} font-mono`} rows={2} placeholder={placeholder}
      defaultValue={value === undefined ? '' : JSON.stringify(value)}
      onChange={e => {
        const text = e.target.value.trim()
        if (!text) { onChange(undefined); return }
        try { onChange(JSON.parse(text)) } catch { /* still typing */ }
      }} />
  )
}
