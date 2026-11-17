import { useState } from 'react'
import type { ReadAsRule } from '@/api/web'

const box = 'rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600'

/**
 * How a step that reads text takes it: as it is, a number ("1,234"), an amount ("₹2,345.00"), a
 * date ("20-Dec-2025"), or the part a pattern picks out ("PNR: 4512345678"). The step keeps its
 * text and gains a value a later step or the result can use.
 */
export function ReadAsField({ rule, busy, onChange }: {
  rule?: ReadAsRule
  busy?: boolean
  onChange: (next: ReadAsRule | null) => void
}) {
  const [as, setAs] = useState<ReadAsRule['as']>(rule?.as ?? 'text')
  const [pattern, setPattern] = useState(rule?.pattern ?? '')
  const [format, setFormat] = useState(rule?.format ?? '')
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
      Read as
      <select aria-label="Read as" value={as} disabled={busy} className={box}
        onChange={e => {
          const next = e.target.value as ReadAsRule['as']
          setAs(next)
          if (next === 'text') onChange(null)
          // A pattern is sent once it is written.
          else if (next === 'pattern') { if (pattern.trim()) onChange({ as: next, pattern: pattern.trim() }) }
          else onChange({ as: next, ...(next === 'date' && format.trim() ? { format: format.trim() } : {}) })
        }}>
        <option value="text">Text</option>
        <option value="number">Number</option>
        <option value="money">Amount of money</option>
        <option value="date">Date</option>
        <option value="pattern">Part of it (pattern)</option>
      </select>
      {as === 'pattern' && (
        <input aria-label="Pattern" value={pattern} disabled={busy} maxLength={300} placeholder="e.g. PNR:\s*(\d{10})"
          onChange={e => setPattern(e.target.value)}
          onBlur={() => { if (pattern.trim() && pattern.trim() !== rule?.pattern) onChange({ as: 'pattern', pattern: pattern.trim() }) }}
          className={`${box} w-44 font-mono`} />
      )}
      {as === 'date' && (
        <input aria-label="Date format" value={format} disabled={busy} maxLength={40} placeholder="Format, e.g. dd-MMM-yyyy (optional)"
          onChange={e => setFormat(e.target.value)}
          onBlur={() => { if (format.trim() !== (rule?.format ?? '')) onChange({ as: 'date', ...(format.trim() ? { format: format.trim() } : {}) }) }}
          className={`${box} w-48`} />
      )}
    </span>
  )
}
