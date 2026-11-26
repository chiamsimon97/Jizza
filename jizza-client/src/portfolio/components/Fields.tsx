import { useId, type ReactNode } from 'react'
import { fieldClass } from '@/components/ui/SlideOver'
import { formatINR, formatINRShort } from '@/lib/money'

/** Digits only, so "1,20,000" and "120000" read the same; empty is null. */
export function parseAmount(text: string): number | null {
  const digits = text.replace(/[^\d.]/g, '')
  if (!digits) return null
  const n = Number(digits)
  return Number.isFinite(n) ? n : null
}

function Labelled({ label, hint, children, id, className = '' }: { label: string; hint?: ReactNode; children: ReactNode; id: string; className?: string }) {
  return (
    <div className={`flex min-w-0 flex-col gap-1 ${className}`}>
      <label htmlFor={id} className="text-sm font-medium text-gray-700 dark:text-gray-300">{label}</label>
      {children}
      {hint && <span className="text-xs text-gray-500 dark:text-gray-400">{hint}</span>}
    </div>
  )
}

/** A rupee amount: typed freely, shown back in Indian grouping underneath. */
export function AmountField({ label, value, onChange, hint, className, placeholder }: {
  label: string
  value: number | null | undefined
  onChange: (v: number | null) => void
  hint?: string
  className?: string
  placeholder?: string
}) {
  const id = useId()
  const echo = value != null && value >= 1000 ? `${formatINR(value)}${value >= 100_000 ? ` (${formatINRShort(value)})` : ''}` : undefined
  return (
    <Labelled id={id} label={label} className={className} hint={[echo, hint].filter(Boolean).join(' · ') || undefined}>
      <div className="relative">
        <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-gray-400">₹</span>
        <input id={id} inputMode="numeric" autoComplete="off" className={`${fieldClass} pl-7 tabular-nums`} placeholder={placeholder}
          value={value == null ? '' : String(value)} onChange={e => onChange(parseAmount(e.target.value))} />
      </div>
    </Labelled>
  )
}

export function TextField({ label, value, onChange, hint, className, placeholder, maxLength = 120, type = 'text' }: {
  label: string
  value: string | null | undefined
  onChange: (v: string) => void
  hint?: string
  className?: string
  placeholder?: string
  maxLength?: number
  type?: 'text' | 'date' | 'number' | 'password'
}) {
  const id = useId()
  return (
    <Labelled id={id} label={label} className={className} hint={hint}>
      <input id={id} type={type} className={fieldClass} placeholder={placeholder} maxLength={maxLength}
        autoComplete={type === 'password' ? 'off' : undefined}
        value={value ?? ''} onChange={e => onChange(e.target.value)} />
    </Labelled>
  )
}

export function SelectField<T extends string>({ label, value, onChange, options, className, hint }: {
  label: string
  value: T
  onChange: (v: T) => void
  options: { value: T; label: string }[]
  className?: string
  hint?: string
}) {
  const id = useId()
  return (
    <Labelled id={id} label={label} className={className} hint={hint}>
      <select id={id} className={fieldClass} value={value} onChange={e => onChange(e.target.value as T)}>
        {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </Labelled>
  )
}

export function CheckField({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} /> {label}
    </label>
  )
}
