import { Plus, Trash2, Workflow as WorkflowIcon } from 'lucide-react'
import type { FormDto } from '@/api/forms'
import { inputClass } from './FieldInput'
import {
  CHOICE_TYPES, COMPUTED_TYPES, LAYOUT_TYPES, SPEC_BY_TYPE, flatten, linkNameOf,
  type FormField, type SubformColumn,
} from './formModel'

const NUMBER_TYPES = new Set(['number', 'decimal', 'percent', 'currency'])
const PLACEHOLDER_TYPES = new Set(['single_line', 'multi_line', 'email', 'phone', 'url', 'number', 'decimal', 'percent', 'currency', 'qr_code'])
const CURRENCIES = ['USD', 'EUR', 'GBP', 'INR', 'SGD', 'MYR', 'AUD', 'CAD', 'JPY', 'AED']

function Row({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-gray-700 dark:text-gray-300">{label}</span>
      {children}
      {hint && <span className="text-[11px] text-gray-500">{hint}</span>}
    </label>
  )
}

interface Props {
  field: FormField
  fields: FormField[]
  forms: FormDto[] | undefined
  currentFormId?: string
  problem?: string
  onChange: (patch: Partial<FormField>) => void
}

/** The selected field's settings, in the builder's right panel. */
export function FieldSettings({ field, fields, forms, currentFormId, problem, onChange }: Props) {
  const spec = SPEC_BY_TYPE[field.type]
  const input = !LAYOUT_TYPES.has(field.type)
  const startInput = field.binding?.mode === 'input'
  const nameLocked = startInput
  const renameWithLabel = (label: string) => {
    // A new field's link name follows its label until the person sets one themselves.
    const follows = field.name === linkNameOf(field.label) || !field.name
    onChange(follows && !nameLocked && input ? { label, name: linkNameOf(label) } : { label })
  }
  return (
    <div className="space-y-4">
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{spec?.label ?? field.type}</p>
        {field.pending && <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">Suggested from a workflow step. Accept it on the form to keep it.</p>}
      </div>
      {field.binding && (
        <p className="flex items-start gap-1.5 rounded-lg bg-brand-50 px-2.5 py-2 text-xs text-brand-800 dark:bg-brand-500/10 dark:text-brand-200">
          <WorkflowIcon className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          {startInput
            ? <>Fills the workflow&apos;s start input <code className="font-mono">{field.name}</code>. Its link name stays the same so the workflow can read it.</>
            : <>Fills the step setting “{field.binding.path.join(' › ')}” when this form starts the workflow.{field.stepValue ? <> It holds “{field.stepValue}” now.</> : null}</>}
        </p>
      )}
      <Row label={field.type === 'decision' ? 'Text beside the box' : field.type === 'section' ? 'Heading' : 'Label'}>
        <input className={inputClass} value={field.label} onChange={e => renameWithLabel(e.target.value)} />
      </Row>
      {input && (
        <Row label="Link name" hint={nameLocked ? undefined : 'The answer is saved under this name, and workflows read it as input.' + (field.name || 'name') + '.'}>
          <input className={`${inputClass} font-mono ${problem ? '!border-red-400' : ''}`} value={field.name ?? ''} disabled={nameLocked}
            onChange={e => onChange({ name: e.target.value.replace(/[^A-Za-z0-9_]/g, '') })} />
          {problem && <span className="text-[11px] text-red-600">{problem}</span>}
        </Row>
      )}
      {(field.type === 'section' || field.type === 'note') && (
        <Row label={field.type === 'note' ? 'Text' : 'Subtitle'}>
          <textarea className={inputClass} rows={field.type === 'note' ? 5 : 2} value={field.content ?? ''} onChange={e => onChange({ content: e.target.value || undefined })} />
        </Row>
      )}
      {PLACEHOLDER_TYPES.has(field.type) && (
        <Row label="Placeholder"><input className={inputClass} value={field.placeholder ?? ''} onChange={e => onChange({ placeholder: e.target.value || undefined })} /></Row>
      )}
      {CHOICE_TYPES.has(field.type) && (
        <Row label="Choices" hint="One per line.">
          <textarea className={inputClass} rows={5} value={(field.choices ?? []).join('\n')} onChange={e => onChange({ choices: e.target.value.split('\n') })} />
        </Row>
      )}
      {NUMBER_TYPES.has(field.type) && (
        <div className="grid grid-cols-2 gap-2">
          <Row label="Smallest"><input type="number" className={inputClass} value={field.min ?? ''} onChange={e => onChange({ min: e.target.value === '' ? undefined : Number(e.target.value) })} /></Row>
          <Row label="Largest"><input type="number" className={inputClass} value={field.max ?? ''} onChange={e => onChange({ max: e.target.value === '' ? undefined : Number(e.target.value) })} /></Row>
        </div>
      )}
      {field.type === 'currency' && (
        <Row label="Currency">
          <select className={inputClass} value={field.currency ?? 'USD'} onChange={e => onChange({ currency: e.target.value })}>
            {CURRENCIES.map(c => <option key={c}>{c}</option>)}
          </select>
        </Row>
      )}
      {field.type === 'decimal' && (
        <Row label="Decimal places"><input type="number" min={0} max={6} className={inputClass} value={field.decimals ?? 2} onChange={e => onChange({ decimals: Math.max(0, Math.min(6, Number(e.target.value) || 0)) })} /></Row>
      )}
      {field.type === 'single_line' && (
        <Row label="Most characters"><input type="number" min={1} max={10000} className={inputClass} value={field.maxLength ?? ''} onChange={e => onChange({ maxLength: e.target.value === '' ? undefined : Number(e.target.value) })} /></Row>
      )}
      {field.type === 'auto_number' && (
        <Row label="Start at"><input type="number" min={0} className={inputClass} value={field.startAt ?? 1} onChange={e => onChange({ startAt: Number(e.target.value) || 1 })} /></Row>
      )}
      {field.type === 'formula' && (
        <Row label="Formula" hint={`Uses the other fields by link name, as in a workflow: e.g. ${exampleFormula(fields)}`}>
          <textarea className={`${inputClass} font-mono`} rows={3} value={field.formula ?? ''} onChange={e => onChange({ formula: e.target.value || undefined })} />
        </Row>
      )}
      {field.type === 'subform' && <Columns columns={field.columns ?? []} onChange={columns => onChange({ columns })} />}
      {field.type === 'lookup' && <LookupSettings field={field} forms={forms?.filter(f => f.id !== currentFormId)} onChange={onChange} />}
      {input && !COMPUTED_TYPES.has(field.type) && (
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={field.required === true} onChange={e => onChange({ required: e.target.checked || undefined })} />
          Required
        </label>
      )}
      {input && (
        <Row label="Help text"><textarea className={inputClass} rows={2} value={field.help ?? ''} onChange={e => onChange({ help: e.target.value || undefined })} /></Row>
      )}
    </div>
  )
}

function exampleFormula(fields: FormField[]): string {
  const numbers = flatten(fields).filter(f => NUMBER_TYPES.has(f.type) && f.name).map(f => f.name!)
  return numbers.length >= 2 ? `input.${numbers[0]} * input.${numbers[1]}` : 'input.price * input.quantity'
}

function Columns({ columns, onChange }: { columns: SubformColumn[]; onChange: (c: SubformColumn[]) => void }) {
  const edit = (i: number, patch: Partial<SubformColumn>) => onChange(columns.map((c, j) => (j === i ? { ...c, ...patch } : c)))
  return (
    <div className="space-y-2">
      <p className="text-xs font-medium text-gray-700 dark:text-gray-300">Columns</p>
      {columns.map((c, i) => (
        <div key={i} className="flex gap-1.5">
          <input aria-label={`Column ${i + 1} label`} className={`${inputClass} py-1`} value={c.label}
            onChange={e => edit(i, { label: e.target.value, ...(c.name === linkNameOf(c.label) ? { name: linkNameOf(e.target.value) } : {}) })} />
          <select aria-label={`Column ${i + 1} type`} className={`${inputClass} w-28 py-1`} value={c.type} onChange={e => edit(i, { type: e.target.value as SubformColumn['type'] })}>
            <option value="single_line">Text</option><option value="multi_line">Long text</option><option value="number">Number</option>
            <option value="email">Email</option><option value="date">Date</option><option value="decision">Yes/no</option>
          </select>
          <button type="button" aria-label={`Remove column ${i + 1}`} onClick={() => onChange(columns.filter((_, j) => j !== i))} className="rounded p-1 text-gray-400 hover:text-red-600"><Trash2 className="h-4 w-4" /></button>
        </div>
      ))}
      {columns.length < 20 && (
        <button type="button" onClick={() => onChange([...columns, { name: `column${columns.length + 1}`, label: `Column ${columns.length + 1}`, type: 'single_line' }])}
          className="inline-flex items-center gap-1 text-xs font-medium text-brand-600"><Plus className="h-3.5 w-3.5" />Add column</button>
      )}
    </div>
  )
}

function LookupSettings({ field, forms, onChange }: { field: FormField; forms?: FormDto[]; onChange: (p: Partial<FormField>) => void }) {
  const source = forms?.find(f => f.id === field.lookup?.formId)
  const sourceFields = source ? flatten(source.fields).filter(f => f.name && !LAYOUT_TYPES.has(f.type)) : []
  return (
    <div className="space-y-2">
      <Row label="Look up answers from" hint="People pick from what was sent to that form. Those answers are shown on this form's public page.">
        <select className={inputClass} value={field.lookup?.formId ?? ''} onChange={e => onChange({ lookup: e.target.value ? { formId: e.target.value, field: '' } : undefined })}>
          <option value="">Choose a form…</option>
          {forms?.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      </Row>
      {source && (
        <Row label="Field">
          <select className={inputClass} value={field.lookup?.field ?? ''} onChange={e => onChange({ lookup: { formId: source.id, field: e.target.value } })}>
            <option value="">Choose a field…</option>
            {sourceFields.map(f => <option key={f.id} value={f.name}>{f.label}</option>)}
          </select>
        </Row>
      )}
    </div>
  )
}

export function FormSettingsPanel({ name, description, submitLabel, successMessage, onChange }: {
  name: string; description: string; submitLabel: string; successMessage: string
  onChange: (patch: { name?: string; description?: string; submitLabel?: string; successMessage?: string }) => void
}) {
  return (
    <div className="space-y-4">
      <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">Form</p>
      <Row label="Name"><input className={inputClass} value={name} onChange={e => onChange({ name: e.target.value })} /></Row>
      <Row label="Description"><textarea className={inputClass} rows={3} value={description} onChange={e => onChange({ description: e.target.value })} /></Row>
      <Row label="Submit button"><input className={inputClass} placeholder="Submit" value={submitLabel} onChange={e => onChange({ submitLabel: e.target.value })} /></Row>
      <Row label="Thank-you message"><textarea className={inputClass} rows={2} placeholder="Thank you. Your response was sent." value={successMessage} onChange={e => onChange({ successMessage: e.target.value })} /></Row>
      <p className="text-xs text-gray-500">Select a field on the form to change it.</p>
    </div>
  )
}
