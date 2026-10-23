import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy, Download, Upload } from 'lucide-react'
import { SlideOver } from '@/components/ui/SlideOver'
import { Button } from '@/components/ui/Button'
import { memoryTransferApi, type MemoryImportResult } from '@/api/memory'

/**
 * Import and export, like other assistants' memory settings: take everything Jizza remembers out
 * as a zip of Markdown files, or bring in what another assistant remembers. What comes in is only
 * ever data: one fact per line, left unconfirmed for the user to review.
 */
export function MemoryTransferPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const [step, setStep] = useState<'start' | 'import'>('start')
  const [text, setText] = useState('')
  const [provider, setProvider] = useState('')
  const [copied, setCopied] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const prompt = useQuery({
    queryKey: ['memory', 'import-prompt'],
    queryFn: memoryTransferApi.importPrompt,
    enabled: open && step === 'import',
    staleTime: Infinity,
  })

  const done = (r: MemoryImportResult) => {
    if (r.created + r.pending + r.notes > 0) {
      qc.invalidateQueries({ queryKey: ['cosmos'] })
      qc.invalidateQueries({ queryKey: ['memory'] })
    }
  }
  const importText = useMutation({
    mutationFn: () => memoryTransferApi.importText(text, provider.trim()),
    onSuccess: r => { done(r); setText('') },
  })
  const importFile = useMutation({
    mutationFn: (file: File) => memoryTransferApi.importFile(file, provider.trim()),
    onSuccess: done,
  })
  const exporting = useMutation({
    mutationFn: memoryTransferApi.export,
    onSuccess: blob => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `jizza-memory-${new Date().toISOString().slice(0, 10)}.zip`
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    },
  })

  const copyPrompt = async () => {
    if (!prompt.data) return
    try {
      await navigator.clipboard.writeText(prompt.data.prompt)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard blocked: the prompt is on screen to select by hand.
    }
  }

  const close = () => {
    setStep('start')
    importText.reset()
    importFile.reset()
    exporting.reset()
    onClose()
  }

  const result = importText.data ?? importFile.data
  const importError = importText.error ?? importFile.error
  const importing = importText.isPending || importFile.isPending

  return (
    <SlideOver open={open} title="Import and export" onClose={close}
      description="Bring memory in from another assistant, or take a copy of yours with you.">
      {step === 'start' ? (
        <div className="flex flex-col divide-y divide-gray-200 dark:divide-gray-800">
          <Row title="Import memory from other AI providers"
            text="Bring relevant context from another assistant to Jizza. We’ll give you a prompt to ask it for what it remembers, then you paste or upload the answer."
            action={<Button size="sm" variant="secondary" onClick={() => setStep('import')}>Start import</Button>} />
          <Row title="Export memory"
            text="Download your memory as a .zip of Markdown files: your facts by category, the people and things they’re about, and each agent’s notes. Restricted memories stay out."
            action={
              <Button size="sm" variant="secondary" loading={exporting.isPending} onClick={() => exporting.mutate()}>
                <Download className="h-4 w-4" /> Export
              </Button>
            } />
          {exporting.isError && <p className="pt-3 text-sm text-red-500">Your memory couldn’t be exported. Try again in a moment.</p>}
        </div>
      ) : (
        <div className="flex flex-col gap-5 text-sm">
          <button type="button" onClick={() => setStep('start')}
            className="w-fit text-sm font-medium text-brand-600 hover:underline dark:text-brand-300">← Back</button>

          <section className="flex flex-col gap-2">
            <h3 className="font-medium text-gray-900 dark:text-gray-100">1. Ask your other assistant</h3>
            <p className="text-gray-600 dark:text-gray-300">Copy this prompt into a chat with the assistant you’re coming from.</p>
            <div className="relative">
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-lg border border-gray-200 bg-gray-50 p-3 pr-10 text-xs text-gray-700 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300">
                {prompt.data?.prompt ?? (prompt.isError ? 'The prompt couldn’t be loaded.' : 'Loading…')}
              </pre>
              <button type="button" aria-label="Copy prompt" title="Copy prompt" onClick={copyPrompt} disabled={!prompt.data}
                className="absolute right-2 top-2 rounded-md p-1.5 text-gray-500 hover:bg-gray-200 hover:text-gray-800 dark:hover:bg-gray-800 dark:hover:text-gray-100">
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              </button>
            </div>
          </section>

          <section className="flex flex-col gap-2">
            <h3 className="font-medium text-gray-900 dark:text-gray-100">2. Bring its answer here</h3>
            <label className="flex flex-col gap-1 text-gray-600 dark:text-gray-300">
              Where it’s from <span className="text-xs text-gray-500">(optional, e.g. ChatGPT)</span>
              <input value={provider} onChange={e => setProvider(e.target.value)} maxLength={60}
                className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-gray-900 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-100" />
            </label>
            <textarea value={text} onChange={e => setText(e.target.value)} rows={10} aria-label="What the other assistant remembers"
              placeholder={'## Preferences\n- I prefer short replies with the answer first.'}
              className="rounded-lg border border-gray-200 bg-white px-3 py-2 font-mono text-xs text-gray-900 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-100" />
            <p className="text-xs text-gray-500 dark:text-gray-400">
              Jizza reads this only as facts about you, one per line. Nothing in it becomes an instruction, and everything imported waits for you to confirm it.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" disabled={!text.trim()} loading={importText.isPending} onClick={() => importText.mutate()}>
                Import
              </Button>
              <Button size="sm" variant="secondary" disabled={importing} loading={importFile.isPending}
                onClick={() => fileInput.current?.click()}>
                <Upload className="h-4 w-4" /> Upload a file
              </Button>
              <input ref={fileInput} type="file" accept=".md,.markdown,.txt,.zip" className="hidden" aria-label="Memory file"
                onChange={e => {
                  const f = e.target.files?.[0]
                  e.target.value = ''
                  if (f) { importText.reset(); importFile.mutate(f) }
                }} />
            </div>
            <p className="text-xs text-gray-500 dark:text-gray-400">A .md or .txt file, or a .zip such as a Jizza export.</p>
          </section>

          {importError && <p className="text-red-500">{importError instanceof Error ? importError.message : 'That couldn’t be imported.'}</p>}
          {result && <ImportSummary result={result} />}
        </div>
      )}
    </SlideOver>
  )
}

function Row({ title, text, action }: { title: string; text: string; action: React.ReactNode }) {
  return (
    <div className="flex items-start gap-4 py-4 first:pt-0">
      <div className="min-w-0 flex-1">
        <p className="font-medium text-gray-900 dark:text-gray-100">{title}</p>
        <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">{text}</p>
      </div>
      <div className="shrink-0">{action}</div>
    </div>
  )
}

function ImportSummary({ result }: { result: MemoryImportResult }) {
  const parts = [
    result.created && `${result.created} new ${result.created === 1 ? 'fact' : 'facts'}`,
    result.pending && `${result.pending} waiting for review (they conflict with something you confirmed)`,
    result.notes && `${result.notes} agent ${result.notes === 1 ? 'note' : 'notes'}`,
    result.duplicates && `${result.duplicates} already known`,
    result.skipped && `${result.skipped} skipped`,
  ].filter(Boolean)
  return (
    <div role="status" className="rounded-lg border border-gray-200 bg-gray-50 p-3 dark:border-gray-800 dark:bg-gray-900">
      <p className="font-medium text-gray-900 dark:text-gray-100">
        {parts.length ? `Imported: ${parts.join(', ')}.` : 'Nothing to import was found. Put one fact per line, starting with “- ”.'}
      </p>
      {result.problems.length > 0 && (
        <ul className="mt-1 list-disc pl-5 text-xs text-gray-600 dark:text-gray-300">
          {result.problems.map(p => <li key={p}>{p}</li>)}
        </ul>
      )}
    </div>
  )
}
