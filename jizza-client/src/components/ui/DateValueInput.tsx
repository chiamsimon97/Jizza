import { formatDate, kindOf, pickerValue } from '@/lib/dateFormat'

/**
 * A date (or date and time) picker for a value a web step types into a site. The picker gives
 * ISO (2026-11-20, 2026-11-20T18:30); the browser writes it the site's way, which is shown under
 * it when the site's format is known ("The site gets 20/11/2026").
 */
export function DateValueInput({ type, format, value, onChange, className, 'aria-label': ariaLabel, id }: {
  type: string
  format?: string
  value: string
  onChange: (value: string) => void
  className?: string
  'aria-label'?: string
  id?: string
}) {
  const kind = kindOf(type, format)
  const picked = pickerValue(value, kind, format)
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-0.5">
      <input id={id} aria-label={ariaLabel} className={className} type={kind === 'datetime' ? 'datetime-local' : 'date'}
        value={picked} onChange={e => onChange(e.target.value)} />
      {format && (
        <span className="text-[11px] text-gray-500 dark:text-gray-400">
          {picked ? `The site gets ${formatDate(picked, format)}` : `The site writes it as ${format}`}
        </span>
      )}
    </div>
  )
}
