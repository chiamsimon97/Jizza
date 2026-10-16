import type { GuardrailMode } from '@/types'
import { MODE_HINTS, MODE_LABELS, MODES } from './labels'

interface ModeSelectProps {
  value: GuardrailMode
  onChange: (mode: GuardrailMode) => void
  label: string
  disabled?: boolean
  /** Modes that make no sense here, such as Let Jizza decide for Jizza itself. */
  exclude?: GuardrailMode[]
}

const tone: Record<GuardrailMode, string> = {
  ALLOW: 'bg-emerald-600 text-white',
  ASK: 'bg-amber-500 text-white',
  JIZZA: 'bg-brand-600 text-white',
  DENY: 'bg-red-600 text-white',
}

/** Auto / Ask me / Let Jizza decide / Never, as one row of buttons. */
export function ModeSelect({ value, onChange, label, disabled, exclude = [] }: ModeSelectProps) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap gap-1 rounded-lg bg-gray-100 p-1 dark:bg-gray-800">
      {MODES.filter(m => !exclude.includes(m)).map(mode => {
        const selected = mode === value
        return (
          <button
            key={mode}
            type="button"
            role="radio"
            aria-checked={selected}
            title={MODE_HINTS[mode]}
            disabled={disabled}
            onClick={() => !selected && onChange(mode)}
            className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
              selected ? tone[mode] : 'text-gray-600 hover:bg-white dark:text-gray-300 dark:hover:bg-gray-700'
            }`}
          >
            {MODE_LABELS[mode]}
          </button>
        )
      })}
    </div>
  )
}
