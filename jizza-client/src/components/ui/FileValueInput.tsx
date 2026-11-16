import { useRef, useState } from 'react'
import { Paperclip, X } from 'lucide-react'
import { chatApi } from '@/api/chat'

/** The largest file a run step hands to a page (the browser refuses more). */
export const MAX_RUN_FILE_BYTES = 10 * 1024 * 1024

/**
 * A run input that is a file (a photo, an ID scan, a PDF): the person adds it here, it is stored
 * with Jizza, and the input's value is the stored file's id. An "Upload a file" step hands it to
 * the browser when it runs; the file never comes from a typed message.
 */
export function FileValueInput({ id, value, onChange, label, invalid, accept }: {
  id?: string
  value: string
  onChange: (id: string) => void
  label: string
  invalid?: boolean
  accept?: string
}) {
  const picker = useRef<HTMLInputElement>(null)
  const [name, setName] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | undefined>()

  const add = async (file: File | undefined) => {
    if (!file) return
    setProblem(undefined)
    if (file.size > MAX_RUN_FILE_BYTES) {
      setProblem(`"${file.name}" is over 10 MB`)
      return
    }
    setBusy(true)
    try {
      const stored = await chatApi.uploadFile(file)
      setName(stored.name)
      onChange(stored.id)
    } catch {
      setProblem(`"${file.name}" could not be added. Try again.`)
    } finally {
      setBusy(false)
      if (picker.current) picker.current.value = ''
    }
  }

  const chosen = value ? name ?? 'File added' : undefined
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <button type="button" id={id} onClick={() => picker.current?.click()} disabled={busy}
          aria-invalid={invalid || !!problem}
          className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-60 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200 dark:hover:bg-gray-800">
          <Paperclip className="h-4 w-4" aria-hidden />
          {busy ? 'Adding…' : chosen ? 'Change file' : 'Add a file'}
        </button>
        {chosen && (
          <span className="flex min-w-0 items-center gap-1 text-sm text-gray-700 dark:text-gray-300">
            <span className="truncate" title={chosen}>{chosen}</span>
            <button type="button" aria-label={`Remove ${chosen}`} onClick={() => { setName(undefined); onChange('') }}
              className="rounded p-0.5 text-gray-400 hover:text-gray-700 dark:hover:text-gray-200">
              <X className="h-3.5 w-3.5" />
            </button>
          </span>
        )}
      </div>
      <input ref={picker} type="file" className="hidden" aria-label={label} accept={accept}
        onChange={e => void add(e.target.files?.[0])} />
      {problem && <p role="alert" className="text-xs text-red-600 dark:text-red-400">{problem}</p>}
    </div>
  )
}
