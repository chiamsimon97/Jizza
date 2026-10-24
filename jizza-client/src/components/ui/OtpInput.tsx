import { type ClipboardEvent, type KeyboardEvent, useEffect, useId, useLayoutEffect, useRef } from 'react'

interface OtpInputProps {
  value: string
  onChange: (value: string) => void
  /** Called once all boxes are filled, with the full code. */
  onComplete?: (value: string) => void
  length?: number
  label?: string
  error?: string
  autoFocus?: boolean
  disabled?: boolean
}

/**
 * A one-time code entered as a row of single-digit boxes. Typing moves to the next box, Backspace on an
 * empty box goes back, and pasting (or the phone's autofill) spreads the code across the boxes.
 * The value is always a run of digits from the first box, so a box past the first empty one is never filled.
 */
export function OtpInput({
  value, onChange, onComplete, length = 6, label, error, autoFocus, disabled,
}: OtpInputProps) {
  const refs = useRef<(HTMLInputElement | null)[]>([])
  // The box to focus once the new value has rendered, so focus never lands ahead of the digits.
  const pendingFocus = useRef<number | null>(null)
  const labelId = useId()
  const errorId = useId()

  useEffect(() => {
    if (autoFocus) refs.current[Math.min(value.length, length - 1)]?.focus()
    // Only on mount: later focus follows the typing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useLayoutEffect(() => {
    if (pendingFocus.current === null) return
    const i = pendingFocus.current
    pendingFocus.current = null
    focusBox(i)
  })

  function focusAfterRender(i: number) {
    pendingFocus.current = i
  }

  function focusBox(i: number) {
    const box = refs.current[Math.max(0, Math.min(i, length - 1))]
    box?.focus()
    box?.select()
  }

  function update(next: string) {
    const code = next.replace(/\D/g, '').slice(0, length)
    if (code === value) {
      // Nothing re-renders, so move focus now (retyping the same digit still steps forward).
      if (pendingFocus.current !== null) focusBox(pendingFocus.current)
      pendingFocus.current = null
      return
    }
    onChange(code)
    if (code.length === length) onComplete?.(code)
  }

  function insertAt(i: number, digits: string) {
    const start = Math.min(i, value.length)
    const next = (value.slice(0, start) + digits + value.slice(start + digits.length)).slice(0, length)
    focusAfterRender(start + digits.length)
    update(next)
  }

  function handleChange(i: number, raw: string) {
    const digits = raw.replace(/\D/g, '')
    if (!digits) {
      if (raw === '') update(value.slice(0, i) + value.slice(i + 1))
      return
    }
    // Autofill drops the whole code into one box; otherwise keep the digit just typed.
    if (digits.length >= length) insertAt(0, digits.slice(0, length))
    else insertAt(i, digits.slice(-1))
  }

  function handleKeyDown(i: number, e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace' && !value[i] && i > 0) {
      e.preventDefault()
      focusAfterRender(i - 1)
      update(value.slice(0, i - 1) + value.slice(i))
    } else if (e.key === 'ArrowLeft' && i > 0) {
      e.preventDefault()
      focusBox(i - 1)
    } else if (e.key === 'ArrowRight' && i < value.length) {
      e.preventDefault()
      focusBox(i + 1)
    } else if (e.key.length === 1 && !/\d/.test(e.key) && !e.metaKey && !e.ctrlKey) {
      e.preventDefault()
    }
  }

  function handlePaste(i: number, e: ClipboardEvent<HTMLInputElement>) {
    e.preventDefault()
    const digits = e.clipboardData.getData('text').replace(/\D/g, '')
    if (!digits) return
    // A whole code always starts from the first box, wherever it was pasted.
    insertAt(digits.length >= length ? 0 : i, digits.slice(0, length))
  }

  return (
    <div className="flex flex-col gap-1">
      {label && <span id={labelId} className="text-sm font-medium text-gray-700 dark:text-gray-300">{label}</span>}
      <div role="group" aria-labelledby={label ? labelId : undefined} aria-describedby={error ? errorId : undefined}
        className="grid gap-2 sm:gap-3" style={{ gridTemplateColumns: `repeat(${length}, minmax(0, 1fr))` }}>
        {Array.from({ length }, (_, i) => (
          <input
            key={i}
            ref={el => { refs.current[i] = el }}
            type="text"
            inputMode="numeric"
            autoComplete={i === 0 ? 'one-time-code' : 'off'}
            aria-label={`Digit ${i + 1} of ${length}`}
            aria-invalid={error ? true : undefined}
            disabled={disabled}
            value={value[i] ?? ''}
            onChange={e => handleChange(i, e.target.value)}
            onKeyDown={e => handleKeyDown(i, e)}
            onPaste={e => handlePaste(i, e)}
            onFocus={e => {
              // Keep the code contiguous: a click past the first empty box lands on that box.
              if (i > value.length) focusBox(value.length)
              else e.target.select()
            }}
            className={`h-12 w-full min-w-0 rounded-lg border bg-white text-center font-mono text-xl font-semibold text-gray-900 caret-brand-500 shadow-sm transition-all hover:border-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-gray-900 dark:text-gray-100 dark:hover:border-gray-600 sm:h-14 ${
              error
                ? 'border-red-400 focus:border-red-500 focus:ring-red-500/15'
                : value[i] ? 'border-brand-400 dark:border-brand-500/70' : 'border-gray-300 dark:border-gray-700'
            }`}
          />
        ))}
      </div>
      {error && <p id={errorId} className="text-xs text-red-500">{error}</p>}
    </div>
  )
}
