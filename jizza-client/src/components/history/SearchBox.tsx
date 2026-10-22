import { useEffect, useState } from 'react'
import { Search, X } from 'lucide-react'

/** A search field that reports what was typed once typing pauses. */
export function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  const [draft, setDraft] = useState(value)
  useEffect(() => setDraft(value), [value])
  useEffect(() => {
    if (draft === value) return
    const t = setTimeout(() => onChange(draft), 300)
    return () => clearTimeout(t)
  }, [draft, value, onChange])

  return (
    <label className="relative flex min-w-[12rem] flex-1 items-center">
      <Search className="pointer-events-none absolute left-2.5 h-4 w-4 text-gray-400" />
      <input
        value={draft}
        onChange={e => setDraft(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="w-full rounded-lg border border-gray-300 bg-white py-1.5 pl-8 pr-7 text-sm text-gray-900 shadow-sm placeholder-gray-400 transition-all hover:border-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
      />
      {draft && (
        <button type="button" onClick={() => { setDraft(''); onChange('') }} aria-label="Clear search" className="absolute right-2 text-gray-400 hover:text-gray-700 dark:hover:text-gray-200">
          <X className="h-3.5 w-3.5" />
        </button>
      )}
    </label>
  )
}
