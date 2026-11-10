import { AlertCircle, ChevronDown, Link2, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { fieldClass } from '@/components/ui/SlideOver'
import { type FormValues, type StepField, type StepForm, isEmpty, mapped, optionsFor, splitList } from './stepForm'

/**
 * The step's settings as they will be sent, on its approval card: each value as it resolved from
 * earlier steps, ready to change. A mandatory value that is still empty is marked, and can be
 * typed in or taken from a value earlier in the run; a choice made at run time lists what the
 * page offers now.
 */
export function StepApprovalForm({ form, values, onChange, readOnly }: {
  form: StepForm
  values: FormValues
  onChange: (key: string, value: string | boolean | string[]) => void
  readOnly?: boolean
}) {
  return (
    <div className="mt-3 space-y-3 rounded-lg border border-gray-200 bg-gray-50/70 p-3 dark:border-gray-800 dark:bg-gray-800/40">
      <p className="text-xs font-semibold text-gray-700 dark:text-gray-200">
        {form.title}: {readOnly ? 'what it sent' : 'check or change what it will send'}
      </p>
      {form.fields.map(field => {
        const options = optionsFor(field, values)
        // A field whose choices follow another one with no list for its value is typed instead.
        // Until the field it follows is chosen, it is a choice waiting on that one.
        const leader = field.dependsOn && field.optionsBy && isEmpty(values[field.dependsOn])
          ? form.fields.find(f => f.key === field.dependsOn)
          : undefined
        const shown: StepField = leader
          ? { ...field, type: 'select', options: [] }
          : field.dependsOn
            ? { ...field, type: options ? 'select' : 'text', options }
            : field
        return (
          <FieldRow key={field.key} field={shown} value={values[field.key]} data={form.data}
            missing={(field.required || form.missing.includes(field.key)) && field.type !== 'checkbox' && isEmpty(values[field.key])}
            choicesAt={shown.type === 'select' && shown.options && !leader && form.choicesAt ? form.choicesAt : undefined}
            waitsOn={leader?.label}
            readOnly={readOnly}
            onChange={v => {
              onChange(field.key, v)
              // A showtime of the theatre before is not one of the theatre chosen now.
              for (const follower of form.fields) if (follower.dependsOn === field.key) onChange(follower.key, '')
            }} />
        )
      })}
    </div>
  )
}

function FieldRow({ field, value, data, missing, choicesAt, waitsOn, readOnly, onChange }: {
  field: StepField
  value: FormValues[string] | undefined
  data: StepForm['data']
  missing: boolean
  choicesAt?: string
  /** The label of the field this one's choices follow, while that one is still empty. */
  waitsOn?: string
  readOnly?: boolean
  onChange: (value: string | boolean | string[]) => void
}) {
  const [mapping, setMapping] = useState(false)
  const id = `step-field-${field.key}`
  const required = field.required || missing
  const canMap = !readOnly && data.length > 0 && field.type !== 'checkbox' && !(field.type === 'select' && field.options)
  const border = missing || field.error ? 'border-red-400 dark:border-red-700' : ''

  if (field.type === 'checkbox') {
    return (
      <label className="flex items-center gap-2 text-sm text-gray-800 dark:text-gray-200">
        <input type="checkbox" disabled={readOnly} checked={value === true} onChange={e => onChange(e.target.checked)} />
        {field.label}
        {field.source && <SourceHint source={field.source} />}
      </label>
    )
  }

  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <label htmlFor={id} className="text-xs font-medium text-gray-700 dark:text-gray-300">
          {field.label}{required && <span className="ml-0.5 text-red-500" aria-hidden>*</span>}
        </label>
        {field.source && <SourceHint source={field.source} />}
        {choicesAt && (
          <span className="inline-flex items-center gap-1 text-[11px] text-gray-500 dark:text-gray-400">
            <RefreshCw className="h-3 w-3" />on the page at {new Date(choicesAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </span>
        )}
      </div>

      {field.type === 'select' && field.options ? (
        <select id={id} aria-label={field.label} disabled={readOnly || waitsOn !== undefined} className={`${fieldClass} ${border}`}
          value={waitsOn !== undefined ? '' : typeof value === 'string' ? value : ''} onChange={e => onChange(e.target.value)}>
          <option value="">{waitsOn !== undefined ? `Choose ${waitsOn} first` : field.options.length ? 'Choose one…' : 'Nothing to choose from'}</option>
          {field.options.map(o => <option key={o} value={o}>{o}</option>)}
        </select>
      ) : field.type === 'list' ? (
        <input id={id} aria-label={field.label} disabled={readOnly} className={`${fieldClass} ${border}`}
          placeholder="Comma-separated, e.g. Music, Travel"
          value={Array.isArray(value) ? value.join(', ') : ''}
          onChange={e => onChange(splitList(e.target.value))} />
      ) : (
        <input id={id} aria-label={field.label} disabled={readOnly} className={`${fieldClass} ${border}`}
          inputMode={field.type === 'number' ? 'decimal' : undefined}
          placeholder={field.type === 'date' ? 'e.g. 2026-11-09, 9 Nov 2026 or 2026-11-09 18:30' : undefined}
          value={typeof value === 'string' ? value : value == null ? '' : String(value)}
          onChange={e => onChange(e.target.value)} />
      )}

      {field.error && (
        <p className="inline-flex items-center gap-1 text-[11px] font-medium text-red-600 dark:text-red-400">
          <AlertCircle className="h-3 w-3" />The site said: {field.error}
        </p>
      )}

      {missing && (
        <p className="inline-flex items-center gap-1 text-[11px] font-medium text-red-600 dark:text-red-400">
          <AlertCircle className="h-3 w-3" />
          {field.type === 'select' && field.options ? 'Choose one to approve.' : 'This needs a value before you can approve.'}
        </p>
      )}

      {canMap && (
        <div>
          <button type="button" onClick={() => setMapping(m => !m)}
            className="inline-flex items-center gap-1 text-[11px] font-medium text-brand-600 hover:underline dark:text-brand-400">
            <Link2 className="h-3 w-3" />Use a value from this run
            <ChevronDown className={`h-3 w-3 transition-transform ${mapping ? 'rotate-180' : ''}`} />
          </button>
          {mapping && (
            <select aria-label={`Map ${field.label}`} className={`${fieldClass} mt-1`} value=""
              onChange={e => {
                const picked = data.find(d => d.path === e.target.value)
                if (picked) onChange(mapped(field, picked.value))
                setMapping(false)
              }}>
              <option value="">Choose a value…</option>
              {data.map(d => <option key={d.path} value={d.path}>{d.path}: {d.value}</option>)}
            </select>
          )}
        </div>
      )}
    </div>
  )
}

function SourceHint({ source }: { source: string }) {
  return (
    <span className="rounded bg-gray-200/70 px-1.5 py-0.5 font-mono text-[10px] text-gray-600 dark:bg-gray-700/60 dark:text-gray-300" title="Mapped from">
      from {source}
    </span>
  )
}
