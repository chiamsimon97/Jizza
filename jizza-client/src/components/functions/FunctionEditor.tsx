import { useEffect, useMemo, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Play, Plus, Trash2, Wand2 } from 'lucide-react'
import { functionsApi, type FunctionInput, type ScriptFunction, type ScriptFunctionDraft } from '@/api/functions'
import type { FormulaPreview } from '@/api/formula'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { fieldClass } from '@/components/ui/SlideOver'
import { ScriptEditor } from '@/components/workflow/formula/ScriptEditor'
import type { ValueType } from '@/components/workflow/formula/functions'
import { FIELD_TYPES, MAX_FIELDS } from '@/components/workflow/moduleContract'
import { argOf, draftIssues, infoOf, keysOf, mergeKeys } from './functionModel'

const NARROW = fieldClass.replace('w-full', '')

/**
 * Makes or changes one function: its link name, the inputs a call passes in order, the script,
 * what it returns (with a map's keys, which a test can fill in), and a test run with sample inputs.
 */
export function FunctionEditor({ draft, onChange, others, saving, error, onSave, onDelete, saved }: {
  draft: ScriptFunctionDraft
  onChange: (d: ScriptFunctionDraft) => void
  /** The person's other functions and the public ones, which this one can call. */
  others: ScriptFunction[]
  saving: boolean
  error?: string | null
  onSave: () => void
  onDelete?: () => void
  /** The saved function this draft edits, if any. */
  saved?: ScriptFunction
}) {
  const problems = useMemo(() => draftIssues(draft, others.filter(o => o.scope !== 'public').map(o => o.name),
    others.filter(o => o.scope === 'public').map(o => o.name)), [draft, others])
  const [touched, setTouched] = useState(false)
  const show = (key: string) => (touched ? problems[key] : undefined)
  const set = (patch: Partial<ScriptFunctionDraft>) => onChange({ ...draft, ...patch })
  const editInput = (i: number, patch: Partial<FunctionInput>) => set({ inputs: draft.inputs.map((r, j) => (j === i ? { ...r, ...patch } : r)) })
  const callable = useMemo(() => others.map(infoOf), [others])

  const save = () => {
    setTouched(true)
    if (Object.keys(problems).length === 0) onSave()
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Card className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex min-w-0 flex-col gap-1 text-xs font-medium text-gray-700 dark:text-gray-300">
            Link name
            <div className="flex min-w-0 items-center gap-1">
              <span className="font-mono text-sm text-gray-400">fn.</span>
              <input aria-label="Link name" placeholder="orderTotal" className={`${fieldClass} min-w-0 font-mono ${show('name') ? '!border-red-400' : ''}`}
                value={draft.name} onChange={e => set({ name: e.target.value.replace(/\s+/g, '_') })} />
            </div>
            <span className={`text-[11px] font-normal ${show('name') ? 'text-red-600 dark:text-red-400' : 'text-gray-500'}`}>
              {show('name') ?? 'Other functions call it as fn.name(…). Renaming it updates those calls.'}
            </span>
          </label>
          <label className="flex min-w-0 flex-col gap-1 text-xs font-medium text-gray-700 dark:text-gray-300">
            Description
            <input aria-label="Description" placeholder="What it works out (optional)" className={fieldClass}
              value={draft.description ?? ''} onChange={e => set({ description: e.target.value })} />
          </label>
        </div>
        {saved && saved.usedBy.length > 0 && (
          <p className="text-[11px] text-gray-500">Used by {saved.usedBy.length === 1 ? 'the workflow' : 'the workflows'} {saved.usedBy.map(n => `"${n}"`).join(', ')}.</p>
        )}
      </Card>

      <Card className="flex flex-col gap-2">
        <Heading title="Inputs" hint="What a call passes, in this order. The script reads each one by its name." />
        {draft.inputs.map((r, i) => (
          <div key={i} className="flex flex-col gap-1.5 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
            <div className="flex flex-wrap items-center gap-1.5">
              <input aria-label={`Name of input ${i + 1}`} placeholder="name"
                className={`${NARROW} min-w-0 flex-1 font-mono text-xs ${show(`inputs.${i}.name`) ? '!border-red-400' : ''}`}
                value={r.name} onChange={e => editInput(i, { name: e.target.value.replace(/\s+/g, '_') })} />
              <select aria-label={`Type of input ${i + 1}`} className={`${NARROW} w-32 shrink-0`} value={r.type}
                onChange={e => editInput(i, { type: e.target.value as ValueType })}>
                {FIELD_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
              <label className="flex items-center gap-1 text-xs text-gray-700 dark:text-gray-300">
                <input type="checkbox" checked={r.required === true} onChange={e => editInput(i, { required: e.target.checked || undefined })} />
                Required
              </label>
              <button type="button" aria-label={`Remove input ${i + 1}`} onClick={() => set({ inputs: draft.inputs.filter((_, j) => j !== i) })}
                className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
            </div>
            {show(`inputs.${i}.name`) && <p className="text-[11px] text-red-600 dark:text-red-400">{show(`inputs.${i}.name`)}</p>}
            <div className="flex flex-wrap gap-1.5">
              <input aria-label={`Description of input ${i + 1}`} placeholder="What it is (optional)" className={`${NARROW} min-w-0 flex-[2] text-xs`}
                value={r.description ?? ''} onChange={e => editInput(i, { description: e.target.value || undefined })} />
              <input aria-label={`Default for input ${i + 1}`} placeholder="Default (optional)" className={`${NARROW} min-w-0 flex-1 font-mono text-xs`}
                value={r.default === undefined ? '' : typeof r.default === 'string' ? r.default : JSON.stringify(r.default)}
                onChange={e => editInput(i, { default: e.target.value === '' ? undefined : e.target.value })} />
            </div>
          </div>
        ))}
        {draft.inputs.length < MAX_FIELDS && (
          <button type="button" onClick={() => set({ inputs: [...draft.inputs, { name: '', type: 'text' }] })}
            className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300">
            <Plus className="h-3.5 w-3.5" /> Add an input
          </button>
        )}
      </Card>

      <Card className="flex flex-col gap-2">
        <Heading title="Script"
          hint="var, if/else and for (var item : list) { } with every formula function. Call your other functions as fn.name(…). End with return. Ctrl+Space suggests, Ctrl+/ comments a line." />
        <ScriptEditor aria-label="Script" rows={14} value={draft.script} invalid={!!show('script')}
          inputs={draft.inputs.filter(i => i.name).map(i => ({ name: i.name, type: i.type }))} functions={callable}
          onChange={v => set({ script: v })} />
        {show('script') && <p className="text-[11px] text-red-600 dark:text-red-400">{show('script')}</p>}
      </Card>

      <TestPanel draft={draft} onUseKeys={keys => set({ returnType: 'map', returnFields: mergeKeys(draft.returnFields, keys) })} />

      <Card className="flex flex-col gap-2">
        <Heading title="Returns" hint="What the function gives. Workflow steps after it map a map's keys one by one; anything else as result." />
        <select aria-label="Return type" className={`${NARROW} w-48`} value={draft.returnType}
          onChange={e => set({ returnType: e.target.value as ValueType })}>
          {FIELD_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
        {draft.returnType === 'map' && <>
          <p className="text-[11px] text-gray-500">Its keys. Nested keys are dotted, such as customer.email. Run a test and press Use these keys to fill them in.</p>
          {draft.returnFields.map((f, i) => (
            <div key={i} className="flex items-center gap-1.5">
              <input aria-label={`Key ${i + 1}`} placeholder="key or parent.key"
                className={`${NARROW} min-w-0 flex-1 font-mono text-xs ${show(`returnFields.${i}.path`) ? '!border-red-400' : ''}`}
                style={{ paddingLeft: `${0.75 + (f.path.split('.').length - 1) * 0.75}rem` }}
                value={f.path} onChange={e => set({ returnFields: draft.returnFields.map((x, j) => (j === i ? { ...x, path: e.target.value.replace(/\s+/g, '') } : x)) })} />
              <select aria-label={`Type of key ${i + 1}`} className={`${NARROW} w-32 shrink-0`} value={f.type}
                onChange={e => set({ returnFields: draft.returnFields.map((x, j) => (j === i ? { ...x, type: e.target.value as ValueType } : x)) })}>
                {FIELD_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
              <button type="button" aria-label={`Remove key ${i + 1}`} onClick={() => set({ returnFields: draft.returnFields.filter((_, j) => j !== i) })}
                className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
            </div>
          ))}
          <button type="button" onClick={() => set({ returnFields: [...draft.returnFields, { path: '', type: 'any' }] })}
            className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300">
            <Plus className="h-3.5 w-3.5" /> Add a key
          </button>
        </>}
      </Card>

      {error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{error}</p>}
      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={save} loading={saving}>{saved ? 'Save changes' : 'Create function'}</Button>
        {onDelete && <Button variant="danger" onClick={onDelete}><Trash2 className="h-4 w-4" />Delete</Button>}
        {touched && Object.keys(problems).length > 0 && <p className="text-xs text-red-600 dark:text-red-400">Fix the fields marked in red to save.</p>}
      </div>
    </div>
  )
}

function Heading({ title, hint }: { title: string; hint: string }) {
  return (
    <div>
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
      <p className="text-[11px] text-gray-500">{hint}</p>
    </div>
  )
}

/** Runs the draft as it stands with a value typed for each input, and offers a map's keys as the return keys. */
export function TestPanel({ draft, onUseKeys }: { draft: ScriptFunctionDraft; onUseKeys?: (keys: ReturnType<typeof keysOf>) => void }) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [result, setResult] = useState<FormulaPreview | null>(null)
  const run = useMutation({
    mutationFn: () => {
      const args: Record<string, unknown> = {}
      for (const input of draft.inputs) {
        const v = argOf(values[input.name] ?? '', input)
        if (v !== undefined && input.name) args[input.name] = v
      }
      return functionsApi.test(draft, args)
    },
    onSuccess: setResult,
    onError: (e: Error) => setResult({ value: null, type: null, error: e.message }),
  })
  useEffect(() => setResult(null), [draft.script])
  const keys = result && !result.error ? keysOf(result.value) : []

  return (
    <Card className="flex flex-col gap-2">
      <Heading title="Test" hint="Run it with sample inputs. Lists and maps are typed as JSON, such as [1, 2] or {&quot;price&quot;: 10}." />
      {draft.inputs.filter(i => i.name).map(i => (
        <label key={i.name} className="flex min-w-0 items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
          <span className="w-28 shrink-0 truncate font-mono" title={i.name}>{i.name}</span>
          <input aria-label={`Test value for ${i.name}`} className={`${NARROW} min-w-0 flex-1 font-mono text-xs`}
            placeholder={i.default !== undefined ? `default ${JSON.stringify(i.default)}` : i.type}
            value={values[i.name] ?? ''} onChange={e => setValues(v => ({ ...v, [i.name]: e.target.value }))} />
        </label>
      ))}
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" variant="secondary" loading={run.isPending} disabled={!draft.script.trim()} onClick={() => run.mutate()}>
          <Play className="h-3.5 w-3.5" />Run test
        </Button>
        {onUseKeys && keys.length > 0 && (
          <Button type="button" size="sm" variant="ghost" onClick={() => onUseKeys(keys)}>
            <Wand2 className="h-3.5 w-3.5" />Use these keys
          </Button>
        )}
      </div>
      {result && (
        <div role="status" className={`rounded-lg border p-2 text-[11px] ${result.error ? 'border-red-200 bg-red-50 text-red-700 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300' : 'border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50'}`}>
          {result.error ?? <>
            <span className="mb-1 inline-block rounded-full bg-gray-200 px-1.5 text-[10px] text-gray-600 dark:bg-gray-700 dark:text-gray-300">{result.type}</span>
            <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all font-mono text-gray-700 dark:text-gray-200">{JSON.stringify(result.value, null, 2)}</pre>
          </>}
        </div>
      )}
    </Card>
  )
}
