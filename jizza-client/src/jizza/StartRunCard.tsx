import { useMemo, useState } from 'react'
import { AlertCircle, Globe, Play, ShieldAlert, Workflow } from 'lucide-react'
import { chatApi } from '@/api/chat'
import { Button } from '@/components/ui/Button'
import { DateValueInput } from '@/components/ui/DateValueInput'
import { FileValueInput } from '@/components/ui/FileValueInput'
import { RowsInput, rowsEmpty, rowsOf } from '@/components/ui/RowsInput'
import type { StartRunCard as Card, StartRunField } from '@/types'
import { useAssistantStore } from './assistantStore'
import { RunInChat } from './RunInChat'

type Values = Record<string, unknown>

const box = 'w-full rounded-md border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:border-brand-500 focus:outline-none dark:border-gray-700 dark:bg-gray-950 dark:text-gray-100'

/** Each field's starting value: what Jizza read from the message, else the input's default. */
export function initialValues(card: Card): Values {
  const out: Values = {}
  for (const f of card.fields) {
    const v = f.value !== undefined ? f.value : f.default
    if (v === undefined || v === null) continue
    out[f.name] = f.type === 'list' && f.columns?.length ? JSON.stringify(v) : v
  }
  return out
}

function empty(f: StartRunField, v: unknown): boolean {
  if (v === undefined || v === null) return true
  if (f.type === 'list' && f.columns?.length) return rowsEmpty(v)
  if (Array.isArray(v)) return v.length === 0
  return typeof v === 'string' && v.trim() === ''
}

/** The inputs that must be filled before Start: needed now, not asked during the run. */
export function stillNeeded(card: Card, values: Values): StartRunField[] {
  return card.fields.filter(f => f.required && f.ask !== 'atStep' && empty(f, values[f.name]))
}

/** The values as the run takes them: rows as a list, numbers as numbers, empty ones left out. */
export function runInputs(card: Card, values: Values): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of card.fields) {
    const v = values[f.name]
    if (empty(f, v)) continue
    if (f.type === 'list' && f.columns?.length) {
      out[f.name] = rowsOf(v).filter(r => Object.values(r).some(x => x.trim() !== ''))
    } else if (f.type === 'number' && typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) {
      out[f.name] = Number(v)
    } else {
      out[f.name] = v
    }
  }
  return out
}

/**
 * Jizza's reply when the person asks, in their own words, to run one of their saved workflows: the
 * values it read from the message (to check and change), what is still needed, what the run will
 * ask for when the page shows it, and which browser runs it. Nothing runs until Start. Once started
 * the card follows the run, with its cards, right here.
 */
export function StartRunCard({ card }: { card: Card }) {
  const conversationId = useAssistantStore(s => s.conversationId)
  const [values, setValues] = useState<Values>(() => initialValues(card))
  const [browserId, setBrowserId] = useState(card.browserId ?? card.browsers?.[0]?.id ?? '')
  const [runId, setRunId] = useState(card.runId)
  const [dismissed, setDismissed] = useState(Boolean(card.dismissed))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // A problem with a value Jizza read is shown until the person changes that value.
  const [edited, setEdited] = useState<Set<string>>(() => new Set())

  const needed = useMemo(() => stillNeeded(card, values), [card, values])
  const later = card.fields.filter(f => f.ask === 'atStep' && empty(f, values[f.name]))
  const browsers = card.browsers ?? []
  const chosen = browsers.find(b => b.id === browserId)
  const set = (name: string, v: unknown) => {
    setValues(prev => ({ ...prev, [name]: v }))
    setEdited(prev => (prev.has(name) ? prev : new Set(prev).add(name)))
  }

  if (runId) return <RunInChat runId={runId} name={card.name} />

  if (dismissed) {
    return <p className="text-xs text-gray-500 dark:text-gray-400">Not run: {card.name}</p>
  }

  async function start() {
    if (!conversationId) return
    setBusy(true)
    setError('')
    try {
      const run = await chatApi.startRun({
        conversationId, planId: card.planId, workflowId: card.workflowId,
        inputs: runInputs(card, values), ...(card.needsBrowser && browserId ? { browserId } : {}),
      })
      setRunId(run.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start it')
    } finally {
      setBusy(false)
    }
  }

  async function dismiss() {
    setDismissed(true)
    if (conversationId) void chatApi.dismissRun({ conversationId, planId: card.planId }).catch(() => {})
  }

  return (
    <div data-testid="start-run-card" className="rounded-2xl border border-brand-300 bg-white p-4 shadow-sm animate-fade-in-up dark:border-brand-700 dark:bg-gray-900">
      <div className="flex items-start gap-2.5">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300">
          <Workflow className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{card.name}</p>
          {card.summary && card.summary !== card.name && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{card.summary}</p>}
        </div>
      </div>

      {card.fields.length > 0 && (
        <div className="mt-3 flex flex-col gap-2.5">
          {card.fields.map(f => (
            <FieldRow key={f.name} field={edited.has(f.name) ? { ...f, problem: undefined } : f} value={values[f.name]}
              onChange={v => set(f.name, v)} missing={needed.includes(f)} />
          ))}
        </div>
      )}

      {later.length > 0 && (
        <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
          Asked while it runs, when the page shows the choices: {later.map(f => f.label).join(', ')}.
        </p>
      )}

      {card.cannotBeUndone && (
        <p className="mt-3 inline-flex items-start gap-1 text-xs text-amber-700 dark:text-amber-300" data-testid="cannot-be-undone">
          <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          "{card.cannotBeUndone}" can't be undone. Check the details first: Jizza never does it twice.
        </p>
      )}

      {card.needsBrowser && (
        <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
          <Globe className="h-3.5 w-3.5 text-gray-400" />
          {browsers.length === 0 ? (
            <span>No browser with the Jizza extension is paired yet. Pair one under Devices.</span>
          ) : (
            <>
              <label htmlFor={`browser-${card.planId}`}>Runs in</label>
              <select id={`browser-${card.planId}`} value={browserId} onChange={e => setBrowserId(e.target.value)}
                className="rounded-md border border-gray-300 bg-white px-2 py-1 text-xs dark:border-gray-700 dark:bg-gray-950">
                {browsers.map(b => <option key={b.id} value={b.id}>{b.name}{b.online ? '' : ' (not connected)'}</option>)}
              </select>
              {chosen && !chosen.online && <span className="text-amber-700 dark:text-amber-300">Open it with the extension before you start.</span>}
            </>
          )}
        </div>
      )}

      {error && (
        <p className="mt-3 inline-flex items-start gap-1 text-xs text-red-600 dark:text-red-400">
          <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />{error}
        </p>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button size="sm" onClick={() => void start()} loading={busy}
          disabled={busy || needed.length > 0 || !conversationId || (card.needsBrowser && browsers.length === 0)}>
          <Play className="h-3.5 w-3.5" />Start
        </Button>
        <Button size="sm" variant="ghost" onClick={() => void dismiss()} disabled={busy}>No thanks</Button>
        {needed.length > 0 && (
          <span className="text-xs text-gray-500 dark:text-gray-400">Fill in {needed.map(f => f.label).join(', ')} first.</span>
        )}
      </div>
    </div>
  )
}

function FieldRow({ field: f, value, onChange, missing }: {
  field: StartRunField
  value: unknown
  onChange: (v: unknown) => void
  missing: boolean
}) {
  const id = `run-field-${f.name}`
  const text = value === undefined || value === null ? '' : typeof value === 'string' ? value : String(value)
  const invalid = missing || !!f.problem
  const hint = f.problem ?? (f.ask === 'atStep' ? 'Leave empty to be asked while it runs' : f.patternHint ?? f.description)
  let control
  if (f.type === 'list' && f.columns?.length) {
    const columns = f.columns.map(c => ({ key: c.key ?? c.name ?? '', label: c.label ?? c.key ?? c.name ?? '' })).filter(c => c.key)
    control = <RowsInput label={f.label} columns={columns} value={value ?? ''} onChange={onChange} invalid={invalid} />
  } else if (f.options?.length) {
    control = (
      <select id={id} value={text} onChange={e => onChange(e.target.value)} className={box} aria-invalid={invalid}>
        <option value="">{f.ask === 'atStep' ? 'Ask me while it runs' : 'Choose…'}</option>
        {f.options.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
    )
  } else if (f.type === 'boolean') {
    control = (
      <input id={id} type="checkbox" checked={value === true || value === 'true'} onChange={e => onChange(e.target.checked)}
        className="h-4 w-4 rounded border-gray-300" />
    )
  } else if (f.type === 'file') {
    control = <FileValueInput id={id} label={f.label} value={text} onChange={onChange} invalid={invalid} />
  } else if (f.type === 'date' || f.type === 'datetime') {
    control = <DateValueInput id={id} type={f.type} format={f.format} value={text} onChange={onChange} className={box} aria-label={f.label} />
  } else {
    control = (
      <input id={id} type={f.type === 'number' ? 'number' : 'text'} value={text} onChange={e => onChange(e.target.value)}
        className={box} aria-invalid={invalid} />
    )
  }
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium text-gray-700 dark:text-gray-300">
        {f.label}{f.required && f.ask !== 'atStep' && <span className="text-red-500"> *</span>}
      </label>
      {control}
      {hint && <p className={`text-[11px] ${f.problem || missing ? 'text-red-600 dark:text-red-400' : 'text-gray-500 dark:text-gray-400'}`}>{missing && !f.problem ? 'Needed before it starts' : hint}</p>}
    </div>
  )
}
