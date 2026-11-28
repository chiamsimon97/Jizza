import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/portfolio/components/Fields'
import { Panel } from '@/portfolio/components/Panel'
import { errorText } from '../data'
import { vitaKeys } from '../queryKeys'

/** Saves a JSON file in the browser; nothing is logged. */
function saveJson(name: string, data: unknown) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'record'

/**
 * Export or delete one person's health record (5.12, 5.13), for whoever keeps it. Both ask for the
 * password again. Export works on a closed record too, which a family needs for claims. Delete
 * removes every health entry and document of the person and ends every share; the person stays in
 * the household list that Kai shares.
 */
export function RecordActions({ personId, name, self }: { personId: string; name: string; self: boolean }) {
  const qc = useQueryClient()
  const whose = self ? 'your' : `${name}’s`
  const [confirming, setConfirming] = useState(false)
  const [typed, setTyped] = useState('')
  const [done, setDone] = useState<string | null>(null)
  const exp = useMutation({
    mutationFn: () => vitaApi.exportRecord(personId),
    onSuccess: data => { saveJson(`health-record-${slug(name)}-${new Date().toISOString().slice(0, 10)}.json`, data); setDone('The file is saved to your downloads.') },
  })
  const del = useMutation({
    mutationFn: () => vitaApi.deleteRecord(personId),
    onSuccess: r => {
      setConfirming(false)
      setTyped('')
      setDone(`Deleted ${r.deleted} ${r.deleted === 1 ? 'entry' : 'entries'} from ${whose} health record.`)
      qc.invalidateQueries({ queryKey: vitaKeys.all })
    },
  })
  const confirmWord = self ? 'delete' : name

  return (
    <Panel title="This record">
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Both ask for your password again.</p>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" loading={exp.isPending} onClick={() => { setDone(null); exp.mutate() }}>
          <Download className="h-3.5 w-3.5" aria-hidden />Export {whose} record
        </Button>
        {!confirming && (
          <Button size="sm" variant="danger" onClick={() => { setDone(null); setConfirming(true) }}>
            <Trash2 className="h-3.5 w-3.5" aria-hidden />Delete {whose} record
          </Button>
        )}
      </div>
      {confirming && (
        <form className="flex flex-col gap-2 rounded-xl border border-red-200 p-3 dark:border-red-900/60" onSubmit={e => { e.preventDefault(); del.mutate() }}>
          <p className="text-sm text-gray-800 dark:text-gray-100">
            This deletes every reading, medicine, report, document and note in {whose} health record for good, and ends every share of it.
            {self ? '' : ` ${name} stays in your list of people.`} Export it first if you may need it.
          </p>
          <TextField label={`Type ${self ? '“delete”' : name} to confirm`} value={typed} maxLength={80} onChange={setTyped} />
          <div className="flex gap-2">
            <Button size="sm" variant="danger" type="submit" loading={del.isPending} disabled={typed.trim().toLowerCase() !== confirmWord.toLowerCase()}>Delete for good</Button>
            <Button size="sm" variant="ghost" type="button" onClick={() => { setConfirming(false); setTyped('') }}>Cancel</Button>
          </div>
        </form>
      )}
      {done && <p role="status" className="text-sm text-gray-700 dark:text-gray-200">{done}</p>}
      {(exp.error || del.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(exp.error ?? del.error, 'That did not work.')}</p>}
    </Panel>
  )
}
