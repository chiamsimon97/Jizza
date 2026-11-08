import { useEffect, useRef, useState } from 'react'
import { Bold, Italic, List as ListIcon, Plus, QrCode, Trash2, Underline } from 'lucide-react'
import type { FormField } from './formModel'

/** The widest a file can be: the server allows about 3 MB. */
export const MAX_FILE_BYTES = 3 * 1024 * 1024

export const inputClass = 'w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 shadow-sm placeholder-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 disabled:cursor-not-allowed disabled:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:disabled:bg-gray-900/60'

type Value = unknown
interface Props {
  field: FormField
  value: Value
  onChange: (value: Value) => void
  /** In the builder: shows the field as it will look, without taking input. */
  preview?: boolean
  error?: string
}

const ACCEPT: Partial<Record<FormField['type'], string>> = { image: 'image/png,image/jpeg,image/gif,image/webp', audio: 'audio/*', video: 'video/*' }

/** One field as the person filling in the form sees it, label included. */
export function FieldBlock({ field, value, onChange, preview, error }: Props) {
  if (field.type === 'section') {
    return (
      <div className="border-b border-gray-200 pb-1 pt-2 dark:border-gray-800">
        <h3 className="text-base font-semibold text-gray-900 dark:text-gray-100">{field.label}</h3>
        {field.content && <p className="mt-0.5 text-sm text-gray-500">{field.content}</p>}
      </div>
    )
  }
  if (field.type === 'note') {
    return <p className="whitespace-pre-wrap rounded-lg bg-gray-50 px-3 py-2 text-sm text-gray-600 dark:bg-gray-900 dark:text-gray-300">{field.content}</p>
  }
  const id = `field-${field.id}`
  const decision = field.type === 'decision'
  return (
    <div className="flex flex-col gap-1">
      {!decision && (
        <label htmlFor={id} className="text-sm font-medium text-gray-800 dark:text-gray-200">
          {field.label}{field.required && <span className="ml-0.5 text-red-500">*</span>}
        </label>
      )}
      <FieldInput field={field} value={value} onChange={onChange} preview={preview} id={id} />
      {field.help && <p className="text-xs text-gray-500">{field.help}</p>}
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  )
}

function FieldInput({ field, value, onChange, preview, id }: Props & { id: string }) {
  const text = typeof value === 'string' ? value : value == null ? '' : String(value)
  const common = { id, disabled: preview, placeholder: field.placeholder, className: inputClass }
  switch (field.type) {
    case 'name': {
      const v = (value ?? {}) as { first?: string; last?: string }
      return (
        <div className="grid grid-cols-2 gap-2">
          <input {...common} aria-label={`${field.label}: first name`} placeholder="First name" value={v.first ?? ''} onChange={e => onChange({ ...v, first: e.target.value })} />
          <input disabled={preview} className={inputClass} aria-label={`${field.label}: last name`} placeholder="Last name" value={v.last ?? ''} onChange={e => onChange({ ...v, last: e.target.value })} />
        </div>
      )
    }
    case 'address': {
      const v = (value ?? {}) as Record<string, string>
      const part = (key: string, label: string, wide = false) => (
        <input key={key} disabled={preview} className={`${inputClass} ${wide ? 'col-span-2' : ''}`} aria-label={`${field.label}: ${label}`} placeholder={label}
          value={v[key] ?? ''} onChange={e => onChange({ ...v, [key]: e.target.value })} />
      )
      return (
        <div id={id} className="grid grid-cols-2 gap-2">
          {part('line1', 'Address line 1', true)}{part('line2', 'Address line 2', true)}
          {part('city', 'City')}{part('state', 'State / Region')}{part('postalCode', 'Postal code')}{part('country', 'Country')}
        </div>
      )
    }
    case 'email': return <input {...common} type="email" value={text} onChange={e => onChange(e.target.value)} />
    case 'phone': return <input {...common} type="tel" value={text} onChange={e => onChange(e.target.value)} />
    case 'url': return <input {...common} type="url" placeholder={field.placeholder ?? 'https://'} value={text} onChange={e => onChange(e.target.value)} />
    case 'single_line': return <input {...common} maxLength={field.maxLength || undefined} value={text} onChange={e => onChange(e.target.value)} />
    case 'qr_code':
      return (
        <div className="relative">
          <QrCode className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-gray-400" />
          <input {...common} className={`${inputClass} pl-9 font-mono`} placeholder={field.placeholder ?? 'Type or paste the code'} value={text} onChange={e => onChange(e.target.value)} />
        </div>
      )
    case 'multi_line': return <textarea {...common} rows={4} value={text} onChange={e => onChange(e.target.value)} />
    case 'rich_text': return <RichText id={id} value={text} onChange={onChange} disabled={preview} />
    case 'number': case 'decimal': case 'percent': case 'currency': {
      const step = field.type === 'number' ? 1 : field.type === 'decimal' ? Math.pow(10, -(field.decimals ?? 2)) : 'any'
      const prefix = field.type === 'currency' ? currencySymbol(field.currency) : undefined
      const suffix = field.type === 'percent' ? '%' : undefined
      return (
        <div className="relative">
          {prefix && <span className="pointer-events-none absolute left-3 top-2 text-sm text-gray-500">{prefix}</span>}
          <input {...common} type="number" step={step} min={field.min} max={field.max}
            className={`${inputClass} ${prefix ? 'pl-8' : ''} ${suffix ? 'pr-8' : ''}`}
            value={text} onChange={e => onChange(e.target.value === '' ? '' : Number(e.target.value))} />
          {suffix && <span className="pointer-events-none absolute right-3 top-2 text-sm text-gray-500">{suffix}</span>}
        </div>
      )
    }
    case 'date': return <input {...common} type="date" value={text} onChange={e => onChange(e.target.value)} />
    case 'time': return <input {...common} type="time" value={text} onChange={e => onChange(e.target.value)} />
    case 'datetime': return <input {...common} type="datetime-local" value={text} onChange={e => onChange(e.target.value)} />
    case 'dropdown': case 'lookup':
      return (
        <select {...common} value={text} onChange={e => onChange(e.target.value)}>
          <option value="">{field.type === 'lookup' && !(field.choices ?? []).length ? 'No choices yet' : 'Choose…'}</option>
          {(field.choices ?? []).map(c => <option key={c} value={c}>{c}</option>)}
        </select>
      )
    case 'radio':
      return (
        <div id={id} role="radiogroup" aria-label={field.label} className="flex flex-col gap-1.5">
          {(field.choices ?? []).map(c => (
            <label key={c} className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
              <input type="radio" name={id} disabled={preview} checked={text === c} onChange={() => onChange(c)} />{c}
            </label>
          ))}
        </div>
      )
    case 'multi_select': {
      const picked = Array.isArray(value) ? (value as string[]) : []
      return (
        <div id={id} role="group" aria-label={field.label} className="flex flex-wrap gap-1.5">
          {(field.choices ?? []).map(c => {
            const on = picked.includes(c)
            return (
              <button key={c} type="button" disabled={preview} aria-pressed={on}
                onClick={() => onChange(on ? picked.filter(p => p !== c) : [...picked, c])}
                className={`rounded-full border px-3 py-1 text-xs transition-colors ${on ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300' : 'border-gray-300 text-gray-700 hover:border-gray-400 dark:border-gray-700 dark:text-gray-300'}`}>
                {c}
              </button>
            )
          })}
        </div>
      )
    }
    case 'checkbox': {
      const picked = Array.isArray(value) ? (value as string[]) : []
      return (
        <div id={id} role="group" aria-label={field.label} className="flex flex-col gap-1.5">
          {(field.choices ?? []).map(c => (
            <label key={c} className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
              <input type="checkbox" disabled={preview} checked={picked.includes(c)}
                onChange={e => onChange(e.target.checked ? [...picked, c] : picked.filter(p => p !== c))} />{c}
            </label>
          ))}
        </div>
      )
    }
    case 'decision':
      return (
        <label className="flex items-start gap-2 text-sm text-gray-800 dark:text-gray-200">
          <input id={id} type="checkbox" className="mt-0.5" disabled={preview} checked={value === true} onChange={e => onChange(e.target.checked)} />
          <span>{field.label}{field.required && <span className="ml-0.5 text-red-500">*</span>}</span>
        </label>
      )
    case 'image': case 'file': case 'audio': case 'video':
      return <FilePicker id={id} accept={ACCEPT[field.type]} value={value as FileValue | undefined} onChange={onChange} disabled={preview} />
    case 'signature': return <SignaturePad id={id} onChange={onChange} disabled={preview} />
    case 'subform': return <Subform field={field} value={value} onChange={onChange} preview={preview} />
    case 'auto_number':
      return <p id={id} className="rounded-lg border border-dashed border-gray-300 px-3 py-2 text-sm text-gray-500 dark:border-gray-700">Numbered when the form is sent{field.startAt && field.startAt !== 1 ? `, from ${field.startAt}` : ''}</p>
    case 'formula':
      return <p id={id} className="rounded-lg border border-dashed border-gray-300 px-3 py-2 text-sm text-gray-500 dark:border-gray-700">Worked out when the form is sent</p>
    default:
      return null
  }
}

export function currencySymbol(code?: string): string {
  try {
    return (0).toLocaleString(undefined, { style: 'currency', currency: code || 'USD', maximumFractionDigits: 0 }).replace(/[\d\s.,]/g, '') || '$'
  } catch {
    return '$'
  }
}

export interface FileValue { name: string; type: string; size: number; dataUrl: string }

function FilePicker({ id, accept, value, onChange, disabled }: { id: string; accept?: string; value?: FileValue; onChange: (v: Value) => void; disabled?: boolean }) {
  const [problem, setProblem] = useState<string | null>(null)
  const pick = (file?: File) => {
    setProblem(null)
    if (!file) return onChange(undefined)
    if (file.size > MAX_FILE_BYTES) return setProblem('That file is larger than 3 MB.')
    const reader = new FileReader()
    reader.onload = () => onChange({ name: file.name, type: file.type || 'application/octet-stream', size: file.size, dataUrl: String(reader.result) })
    reader.readAsDataURL(file)
  }
  return (
    <div className="flex flex-col gap-1">
      <input id={id} type="file" accept={accept} disabled={disabled} onChange={e => pick(e.target.files?.[0])}
        className="text-sm text-gray-700 file:mr-3 file:rounded-lg file:border-0 file:bg-gray-100 file:px-3 file:py-1.5 file:text-sm file:font-medium hover:file:bg-gray-200 dark:text-gray-300 dark:file:bg-gray-800" />
      {value?.dataUrl && value.type.startsWith('image/') && <img src={value.dataUrl} alt="" className="max-h-32 w-fit rounded-lg border border-gray-200" />}
      {problem && <p className="text-xs text-red-600">{problem}</p>}
    </div>
  )
}

function SignaturePad({ id, onChange, disabled }: { id: string; onChange: (v: Value) => void; disabled?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const drawing = useRef(false)
  const [signed, setSigned] = useState(false)
  const at = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    return { x: (e.clientX - r.left) * (e.currentTarget.width / r.width), y: (e.clientY - r.top) * (e.currentTarget.height / r.height) }
  }
  const save = () => {
    const c = canvas.current
    if (!c) return
    onChange({ name: 'signature.png', type: 'image/png', size: 0, dataUrl: c.toDataURL('image/png') })
  }
  const clear = () => {
    const c = canvas.current
    c?.getContext('2d')?.clearRect(0, 0, c.width, c.height)
    setSigned(false)
    onChange(undefined)
  }
  return (
    <div className="flex flex-col gap-1">
      <canvas id={id} ref={canvas} width={600} height={160} aria-label="Sign here"
        className={`h-32 w-full touch-none rounded-lg border border-gray-300 bg-white dark:border-gray-700 ${disabled ? 'cursor-not-allowed opacity-60' : 'cursor-crosshair'}`}
        onPointerDown={e => {
          if (disabled) return
          drawing.current = true
          e.currentTarget.setPointerCapture(e.pointerId)
          const ctx = e.currentTarget.getContext('2d')!
          const p = at(e)
          ctx.lineWidth = 2.5; ctx.lineCap = 'round'; ctx.strokeStyle = '#111827'
          ctx.beginPath(); ctx.moveTo(p.x, p.y)
        }}
        onPointerMove={e => {
          if (!drawing.current) return
          const ctx = e.currentTarget.getContext('2d')!
          const p = at(e)
          ctx.lineTo(p.x, p.y); ctx.stroke()
          setSigned(true)
        }}
        onPointerUp={() => { if (drawing.current) { drawing.current = false; save() } }} />
      {signed && !disabled && <button type="button" onClick={clear} className="w-fit text-xs text-gray-500 underline">Clear signature</button>}
    </div>
  )
}

function RichText({ id, value, onChange, disabled }: { id: string; value: string; onChange: (v: Value) => void; disabled?: boolean }) {
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    // Set once from outside; after that the box owns its content.
    if (box.current && box.current.innerHTML !== value && document.activeElement !== box.current) box.current.innerHTML = value
  }, [value])
  const run = (command: string) => {
    box.current?.focus()
    document.execCommand(command)
    onChange(box.current?.innerHTML ?? '')
  }
  const tool = (command: string, Icon: typeof Bold, label: string) => (
    <button type="button" disabled={disabled} aria-label={label} onMouseDown={e => e.preventDefault()} onClick={() => run(command)}
      className="rounded p-1 text-gray-600 hover:bg-gray-100 disabled:opacity-50 dark:text-gray-300 dark:hover:bg-gray-800"><Icon className="h-3.5 w-3.5" /></button>
  )
  return (
    <div className="rounded-lg border border-gray-300 bg-white shadow-sm focus-within:border-brand-500 focus-within:ring-4 focus-within:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900">
      <div className="flex gap-0.5 border-b border-gray-200 px-1.5 py-1 dark:border-gray-800">
        {tool('bold', Bold, 'Bold')}{tool('italic', Italic, 'Italic')}{tool('underline', Underline, 'Underline')}{tool('insertUnorderedList', ListIcon, 'Bulleted list')}
      </div>
      <div id={id} ref={box} role="textbox" aria-multiline="true" contentEditable={!disabled} suppressContentEditableWarning
        className="min-h-[96px] px-3 py-2 text-sm text-gray-900 focus:outline-none dark:text-gray-100 [&_ul]:list-disc [&_ul]:pl-5"
        onInput={e => onChange((e.target as HTMLDivElement).innerHTML)} />
    </div>
  )
}

function Subform({ field, value, onChange, preview }: { field: FormField; value: Value; onChange: (v: Value) => void; preview?: boolean }) {
  const rows = Array.isArray(value) ? (value as Array<Record<string, unknown>>) : [{}]
  const columns = field.columns ?? []
  const set = (i: number, key: string, v: unknown) => onChange(rows.map((r, j) => (j === i ? { ...r, [key]: v } : r)))
  return (
    <div className="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-left text-xs text-gray-500 dark:bg-gray-900">
          <tr>{columns.map(c => <th key={c.name} className="px-2 py-1.5 font-medium">{c.label}</th>)}<th className="w-8" /></tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className="border-t border-gray-100 dark:border-gray-800">
              {columns.map(c => (
                <td key={c.name} className="p-1.5">
                  {c.type === 'decision'
                    ? <input type="checkbox" aria-label={`${c.label}, row ${i + 1}`} disabled={preview} checked={row[c.name] === true} onChange={e => set(i, c.name, e.target.checked)} />
                    : <input aria-label={`${c.label}, row ${i + 1}`} disabled={preview} className={`${inputClass} py-1`}
                        type={c.type === 'number' ? 'number' : c.type === 'email' ? 'email' : c.type === 'date' ? 'date' : 'text'}
                        value={row[c.name] == null ? '' : String(row[c.name])}
                        onChange={e => set(i, c.name, c.type === 'number' && e.target.value !== '' ? Number(e.target.value) : e.target.value)} />}
                </td>
              ))}
              <td className="p-1">
                {rows.length > 1 && !preview && (
                  <button type="button" aria-label={`Remove row ${i + 1}`} onClick={() => onChange(rows.filter((_, j) => j !== i))}
                    className="rounded p-1 text-gray-400 hover:text-red-600"><Trash2 className="h-3.5 w-3.5" /></button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!preview && rows.length < 50 && (
        <button type="button" onClick={() => onChange([...rows, {}])}
          className="flex w-full items-center justify-center gap-1 border-t border-gray-100 py-1.5 text-xs font-medium text-brand-600 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-900">
          <Plus className="h-3.5 w-3.5" />Add row
        </button>
      )}
    </div>
  )
}
