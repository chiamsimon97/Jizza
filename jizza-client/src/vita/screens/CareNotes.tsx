import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MessagesSquare } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { errorText, formatWhen } from '../data'
import { vitaKeys } from '../queryKeys'
import { useShareScope } from '../share'

const MAX = 2000

/**
 * Notes between the manager and helpers on Home (7.2, from 8a). Shown on a shared record, and on
 * one's own record once it is shared with someone or has notes; nobody else reads them.
 */
export function CareNotes({ personId, readOnly }: { personId: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const share = useShareScope()
  const notes = useQuery({ queryKey: vitaKeys.careNotes(personId), queryFn: () => vitaApi.careNotes(personId) })
  const shares = useQuery({ queryKey: vitaKeys.shares(personId), queryFn: () => vitaApi.shares(personId), enabled: !share.shared, retry: false })
  const [text, setText] = useState('')
  const add = useMutation({
    mutationFn: () => vitaApi.addCareNote(personId, text.trim()),
    onSuccess: () => { setText(''); qc.invalidateQueries({ queryKey: vitaKeys.careNotes(personId) }) },
  })
  const sharedOut = (shares.data?.links ?? []).some(l => l.personId === personId)
  if (!share.shared && !sharedOut && !notes.data?.length) return null

  return (
    <Panel title={<><MessagesSquare className="h-4 w-4 text-gray-400" aria-hidden />Notes</>}>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Between {share.shared ? 'you, the person who keeps this record' : 'you'} and the people it is shared with.</p>
      {notes.isLoading && <SkeletonRows rows={2} />}
      {notes.error && <LoadFailed text="Could not load the notes." onRetry={() => notes.refetch()} />}
      {notes.data && !notes.data.length && <p className="text-sm text-gray-500 dark:text-gray-400">No notes yet. Leave one for the others, such as “Ran out of strips, bought a pack”.</p>}
      {notes.data && notes.data.length > 0 && (
        <ul className="flex max-h-72 flex-col gap-2 overflow-y-auto">
          {notes.data.map(n => (
            <li key={n.id} className="rounded-lg bg-gray-50 px-3 py-2 text-sm dark:bg-gray-800/60">
              <p className="whitespace-pre-line text-gray-800 dark:text-gray-100">{n.text}</p>
              <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{n.writtenByName} · {formatWhen(n.writtenAt)}</p>
            </li>
          ))}
        </ul>
      )}
      {!readOnly && (
        <form className="flex flex-col gap-2" onSubmit={e => { e.preventDefault(); add.mutate() }}>
          <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
            Add a note
            <textarea className={`${fieldClass} min-h-[4rem]`} value={text} maxLength={MAX} onChange={e => setText(e.target.value)} />
          </label>
          <div className="flex items-center gap-2">
            <Button size="sm" type="submit" loading={add.isPending} disabled={!text.trim()}>Add note</Button>
            {text.length > MAX - 200 && <span className="text-xs text-gray-500 dark:text-gray-400">{MAX - text.length} characters left</span>}
          </div>
          {add.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(add.error, 'Could not add the note.')}</p>}
        </form>
      )}
    </Panel>
  )
}
