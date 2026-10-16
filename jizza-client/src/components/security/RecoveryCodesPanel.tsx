import { useState } from 'react'
import { Copy, Download, Check } from 'lucide-react'
import { Button } from '@/components/ui/Button'

/**
 * Shows freshly issued recovery codes. They are displayed this once and never again, so the user
 * is asked to save them before moving on.
 */
export function RecoveryCodesPanel({ codes, onDone, doneLabel = 'I saved them' }: {
  codes: string[]
  onDone: () => void
  doneLabel?: string
}) {
  const [copied, setCopied] = useState(false)
  const [saved, setSaved] = useState(false)
  const text = `Jizza recovery codes\nEach code works once. Keep them somewhere safe and offline.\n\n${codes.join('\n')}\n`

  async function copy() {
    try {
      await navigator.clipboard.writeText(codes.join('\n'))
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch { /* clipboard blocked; the codes are still on screen */ }
  }

  function download() {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'jizza-recovery-codes.txt'
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h3 className="font-semibold text-gray-900 dark:text-gray-100">Save your recovery codes</h3>
        <p className="text-sm text-gray-500 dark:text-gray-400">
          If you lose your phone, each of these lets you sign in once. This is the only time they are shown.
        </p>
      </div>
      <ul className="grid grid-cols-2 gap-2 rounded-lg bg-gray-50 p-4 font-mono text-sm text-gray-800 dark:bg-gray-800/60 dark:text-gray-200">
        {codes.map(code => <li key={code}>{code}</li>)}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" size="sm" onClick={copy}>
          {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}{copied ? 'Copied' : 'Copy'}
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={download}>
          <Download className="h-4 w-4" />Download
        </Button>
      </div>
      <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
        <input type="checkbox" checked={saved} onChange={e => setSaved(e.target.checked)} />
        I've stored these codes somewhere safe
      </label>
      <Button type="button" disabled={!saved} onClick={onDone}>{doneLabel}</Button>
    </div>
  )
}
