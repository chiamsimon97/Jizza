import { AlertCircle, ChevronDown, Link2, RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { webApi } from '@/api/web'
import { RowsInput } from '@/components/ui/RowsInput'
import { TapPicture } from './TapPicture'
import { fieldClass } from '@/components/ui/SlideOver'
import { formatDate, kindOf, pickerValue } from '@/lib/dateFormat'
import { type FormValues, type StepField, type StepForm, type StepLive, type StepShots, fieldEmpty, isDate, isEmpty, mapped, optionsFor, splitList } from './stepForm'

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
  // Refresh: what the page lists now for a value, read on the person's ask.
  const [liveOptions, setLiveOptions] = useState<Record<string, LiveState>>({})
  const refresh = async (key: string) => {
    const live = form.live
    const how = live?.fields[key]
    if (!live || !how) return
    const text = values[key]
    const typed = typeof text === 'string' ? text.trim() : ''
    if (how.typed && !typed) {
      setLiveOptions(o => ({ ...o, [key]: { error: 'Type a few letters first, then refresh.' } }))
      return
    }
    setLiveOptions(o => ({ ...o, [key]: { ...o[key], reading: true, error: undefined } }))
    setLiveOptions(await readLive(live, key, typed))
  }
  // A dropdown the card has no choices for (a site's own dropdown, read only while open) lists
  // what the page offers as the card opens, one at a time, so the person picks instead of typing.
  // Quietly: a page that cannot be read now leaves the field as it was, with its Refresh.
  useEffect(() => {
    const live = form.live
    if (readOnly || !live) return
    const keys = Object.entries(live.fields)
      .filter(([key, how]) => !how.typed && !how.follows
        && !form.fields.find(f => f.key === key)?.options?.length)
      .map(([key]) => key)
    if (keys.length === 0) return
    let stop = false
    void (async () => {
      for (const key of keys) {
        if (stop) return
        setLiveOptions(o => ({ ...o, [key]: { ...o[key], reading: true } }))
        const read = (await readLive(live, key, ''))({})[key]
        if (!stop) setLiveOptions(o => ({ ...o, [key]: read?.options?.length ? read : {} }))
      }
    })()
    return () => { stop = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  async function readLive(live: StepLive, key: string, typed: string) {
    const how = live.fields[key]!
    let next: LiveState
    try {
      const result = await webApi.testStep({
        deviceId: live.device, action: how.action, version: how.version,
        inputs: { ...(live.session ? { session: live.session } : {}), element: how.element, ...(how.typed ? { text: typed } : {}) },
      })
      const out = result.output as { options?: unknown[]; items?: { label?: string }[] } | undefined
      const labels = how.typed
        ? (out?.options ?? []).map(String)
        : (out?.items ?? []).map(i => i.label ?? '')
      const options = [...new Set(labels.map(l => l.trim()).filter(Boolean))]
      next = result.status === 'succeeded'
        ? options.length ? { options } : { options: [], error: how.typed ? `The page suggests nothing for "${typed}".` : 'The page lists nothing for it now.' }
        : { error: result.error?.detail ?? result.message ?? 'The page could not be read.' }
    } catch (e) {
      const problem = (e as { problem?: { detail?: string; title?: string } }).problem
      next = { error: problem?.detail ?? problem?.title ?? (e instanceof Error ? e.message : 'The page could not be read.') }
    }
    return (o: Record<string, LiveState>) => ({ ...o, [key]: next })
  }
  return (
    <div className="mt-3 space-y-3 rounded-lg border border-gray-200 bg-gray-50/70 p-3 dark:border-gray-800 dark:bg-gray-800/40">
      <p className="text-xs font-semibold text-gray-700 dark:text-gray-200">
        {form.title}: {readOnly ? 'what it sent' : 'check or change what it will send'}
      </p>
      {form.fixed && form.fixed.length > 0 && <FixedValues fixed={form.fixed} />}
      {form.fields.map((field, index) => {
        const heading = field.group && field.group !== form.fields[index - 1]?.group
          ? <p key={`group-${index}`} className="pt-1 text-[11px] font-semibold uppercase tracking-wide text-gray-500">{field.group}</p>
          : null
        if (field.type === 'note') {
          return (
            <p key={field.key} className="whitespace-pre-wrap text-xs text-gray-700 dark:text-gray-300">
              {field.label}
            </p>
          )
        }
        const options = optionsFor(field, values)
        // A field whose choices follow another one with no list for its value is typed instead.
        // Until the field it follows is chosen, it is a choice waiting on that one.
        const leader = field.dependsOn && field.optionsBy && isEmpty(values[field.dependsOn])
          ? form.fields.find(f => f.key === field.dependsOn)
          : undefined
        const read = liveOptions[field.key]
        const how = form.live?.fields[field.key]
        const shown: StepField = leader
          ? { ...field, type: 'select', options: [] }
          : read?.options?.length && how && !how.typed
            ? { ...field, type: 'select', options: read.options }
            : field.dependsOn
              ? { ...field, type: options ? 'select' : 'text', options }
              : field
        return (
          <div key={field.key} className="space-y-3">{heading}
          <FieldRow field={shown} value={values[field.key]} data={form.data} shots={form.shots}
            missing={(field.required || form.missing.includes(field.key)) && field.type !== 'checkbox' && fieldEmpty(field, values[field.key])}
            choicesAt={shown.type === 'select' && shown.options && !leader && form.choicesAt ? form.choicesAt : undefined}
            waitsOn={leader?.label}
            readOnly={readOnly}
            live={how && !readOnly && !leader ? { ...read, typed: how.typed, onRefresh: () => void refresh(field.key) } : undefined}
            onChange={v => {
              onChange(field.key, v)
              // A showtime of the theatre before is not one of the theatre chosen now.
              for (const follower of form.fields) if (follower.dependsOn === field.key) onChange(follower.key, '')
            }} />
          </div>
        )
      })}
    </div>
  )
}

interface LiveState { options?: string[]; reading?: boolean; error?: string }

function FieldRow({ field, value, data, shots, missing, choicesAt, waitsOn, readOnly, live, onChange }: {
  field: StepField
  value: FormValues[string] | undefined
  data: StepForm['data']
  shots?: StepShots
  missing: boolean
  choicesAt?: string
  /** The label of the field this one's choices follow, while that one is still empty. */
  waitsOn?: string
  readOnly?: boolean
  /** Refresh: read this value's choices from the page again (suggestions for what is typed). */
  live?: LiveState & { typed: boolean; onRefresh: () => void }
  onChange: (value: string | boolean | string[]) => void
}) {
  const [mapping, setMapping] = useState(false)
  const id = `step-field-${field.key}`
  const required = field.required || missing
  const canMap = !readOnly && data.length > 0 && field.type !== 'checkbox' && field.type !== 'rows' && field.type !== 'taps' && !(field.type === 'select' && field.options)
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
        {live && !(live.typed && field.type !== 'select' && field.type !== 'list' && !isDate(field)) && (
          <button type="button" disabled={live.reading} onClick={live.onRefresh}
            title={live.typed ? 'Type in this field on the page and list what it suggests' : 'List what this dropdown offers on the page now'}
            className="ml-auto inline-flex items-center gap-1 text-[11px] font-medium text-brand-600 hover:underline disabled:opacity-50 dark:text-brand-400">
            <RefreshCw className={`h-3 w-3 ${live.reading ? 'animate-spin' : ''}`} />{live.reading ? 'Reading the page…' : 'Refresh'}
          </button>
        )}
      </div>

      {field.type === 'taps' ? (
        field.picture ? (
          <TapPicture src={`/api/devices/${field.picture.device}/files/${field.picture.file}`} label={field.label}
            taps={Array.isArray(value) ? value : []} readOnly={readOnly} onChange={onChange} />
        ) : (
          <p className="text-xs text-gray-600 dark:text-gray-400">The picture to tap on could not be shown.</p>
        )
      ) : field.type === 'rows' ? (
        <RowsInput label={field.label} columns={field.columns ?? []} value={value} readOnly={readOnly}
          invalid={missing} onChange={onChange} />
      ) : field.type === 'select' && field.options ? (
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
      ) : isDate(field) ? (
        <DateInput id={id} field={field} value={typeof value === 'string' ? value : ''}
          className={`${fieldClass} ${border}`} readOnly={readOnly} onChange={onChange} />
      ) : (
        <div className="relative">
          <input id={id} aria-label={field.label} disabled={readOnly} className={`${fieldClass} ${border} ${live?.typed ? 'pr-9' : ''}`}
            list={live?.typed && live.options?.length ? `${id}-suggestions` : undefined}
            inputMode={field.type === 'number' ? 'decimal' : undefined}
            placeholder={field.type === 'date' ? 'e.g. 2026-11-09, 9 Nov 2026 or 2026-11-09 18:30' : undefined}
            value={typeof value === 'string' ? value : value == null ? '' : String(value)}
            onChange={e => onChange(e.target.value)}
            onKeyDown={e => { if (live?.typed && e.key === 'Enter') { e.preventDefault(); if (!live.reading) live.onRefresh() } }} />
          {live?.typed && (
            <button type="button" disabled={live.reading} onClick={live.onRefresh} aria-label="Refresh"
              title="Type in this field on the page and list what it suggests (or press Enter)"
              className="absolute inset-y-0 right-0 flex w-9 items-center justify-center text-gray-500 hover:text-brand-600 disabled:opacity-50 dark:text-gray-400 dark:hover:text-brand-400">
              <RefreshCw className={`h-4 w-4 ${live.reading ? 'animate-spin' : ''}`} />
            </button>
          )}
        </div>
      )}

      {live?.typed && live.options && live.options.length > 0 && (
        <>
          <datalist id={`${id}-suggestions`}>{live.options.map(o => <option key={o} value={o} />)}</datalist>
          <div className="flex flex-wrap gap-1" data-testid="live-suggestions">
            {live.options.slice(0, 12).map(o => (
              <button key={o} type="button" onClick={() => onChange(o)}
                className={`rounded-full border px-2 py-0.5 text-[11px] ${value === o ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-900/30 dark:text-brand-200' : 'border-gray-300 text-gray-700 hover:border-brand-400 dark:border-gray-700 dark:text-gray-300'}`}>
                {o}
              </button>
            ))}
          </div>
        </>
      )}
      {live?.error && <p className="text-[11px] text-amber-700 dark:text-amber-400">{live.error}</p>}

      {field.type === 'select' && typeof value === 'string' && shots?.byOption[value] && (
        <img src={`/api/devices/${shots.device}/files/${shots.byOption[value]}`} alt={value}
          data-testid="option-shot"
          className="mt-1 max-h-64 w-full rounded-lg border border-gray-200 bg-white object-contain dark:border-gray-700 dark:bg-gray-900" />
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

/** A device layer's fixed values, folded into one line that opens to list them. */
function FixedValues({ fixed }: { fixed: { label: string; value: string }[] }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="text-xs text-gray-600 dark:text-gray-300">
      <button type="button" aria-expanded={open} onClick={() => setOpen(v => !v)}
        className="inline-flex items-center gap-1 text-gray-700 hover:underline dark:text-gray-200">
        <ChevronDown className={`h-3.5 w-3.5 transition-transform ${open ? '' : '-rotate-90'}`} />
        Fixed values ({fixed.length})
      </button>
      {open && <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 pl-5">
        {fixed.map((f, i) => <div key={i} className="contents"><dt className="text-gray-500">{f.label}</dt><dd>{f.value || '(left as it is)'}</dd></div>)}
      </dl>}
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

/**
 * A date (or date and time) picker. The date picked goes to the site in its own format, shown
 * under the picker; text that is not a date (typed earlier, or "next Friday") can still be typed.
 */
function DateInput({ id, field, value, className, readOnly, onChange }: {
  id: string
  field: StepField
  value: string
  className: string
  readOnly?: boolean
  onChange: (value: string) => void
}) {
  const kind = kindOf(field.type, field.format)
  const picked = pickerValue(value, kind, field.format)
  const [typing, setTyping] = useState(value !== '' && picked === '')
  return (
    <div className="space-y-1">
      {typing ? (
        <input id={id} aria-label={field.label} disabled={readOnly} className={className}
          placeholder={field.format ? `e.g. ${formatDate(kind === 'datetime' ? '2026-11-20T18:30' : '2026-11-20', field.format)}`
            : 'e.g. 2026-11-09, 9 Nov 2026 or 2026-11-09 18:30'}
          value={value} onChange={e => onChange(e.target.value)} />
      ) : (
        <input id={id} aria-label={field.label} disabled={readOnly} className={className}
          type={kind === 'datetime' ? 'datetime-local' : 'date'}
          value={picked} onChange={e => onChange(e.target.value)} />
      )}
      <p className="flex flex-wrap items-center gap-x-2 text-[11px] text-gray-500 dark:text-gray-400">
        {field.format && picked && (
          <span data-testid="date-as-sent">The site gets {formatDate(picked, field.format)}</span>
        )}
        {field.format && !picked && <span>The site writes it as {field.format}</span>}
        {!readOnly && (
          <button type="button" className="font-medium text-brand-600 hover:underline dark:text-brand-400"
            onClick={() => setTyping(t => !t)}>
            {typing ? 'Use the picker' : 'Type it instead'}
          </button>
        )}
      </p>
    </div>
  )
}
