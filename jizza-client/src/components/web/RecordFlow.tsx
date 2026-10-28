import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Circle, Square } from 'lucide-react'
import { webApi, type EditOp, type Recording } from '@/api/web'
import { useWebSocket } from '@/hooks/useWebSocket'
import { SlideOver } from '@/components/ui/SlideOver'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { RecordingReview } from './RecordingReview'
import { liveSteps, missingLogins } from './recordingModel'

/**
 * Record in Chrome, review, then either save as a module or (from the builder) put the steps into
 * the workflow being built. A recording that was already started can be reopened with {@code recordingId}.
 */
export function RecordFlow({ open, mode, recordingId, onClose, onSaved, onInsert }: {
  open: boolean
  mode: 'module' | 'insert'
  recordingId?: string
  onClose: () => void
  onSaved?: (workflowId: string) => void
  onInsert?: (graph: { nodes: unknown[]; edges: unknown[] }) => void
}) {
  const client = useQueryClient()
  const [id, setId] = useState<string | undefined>(recordingId)
  const [browserId, setBrowserId] = useState('')
  const [url, setUrl] = useState('https://')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)

  const browsers = useQuery({ queryKey: ['web-browsers'], queryFn: webApi.browsers, enabled: open && !id })
  const recording = useQuery({
    queryKey: ['web-recording', id], queryFn: () => webApi.getRecording(id!), enabled: open && !!id,
    // The socket is the fast path; the poll covers a dropped connection while Chrome is recording.
    refetchInterval: q => (q.state.data?.status === 'recording' ? 3000 : false),
  })
  useWebSocket({ onWebRecording: e => { if (e.recordingId === id) void recording.refetch() } })

  const set = (r: Recording) => client.setQueryData(['web-recording', r.id], r)
  const fail = (e: unknown) => setError(e instanceof Error ? e.message : 'Something went wrong')
  const start = useMutation({ mutationFn: () => webApi.startRecording({ deviceId: browserId, url: url.trim(), name: name.trim() }),
    onSuccess: r => { setError(null); set(r); setId(r.id) }, onError: fail })
  const stop = useMutation({ mutationFn: () => webApi.stopRecording(id!), onSuccess: set, onError: fail })
  const edit = useMutation({ mutationFn: (ops: EditOp[]) => webApi.editRecording(id!, ops), onSuccess: r => { setError(null); set(r) }, onError: fail })
  const discard = useMutation({ mutationFn: () => webApi.discardRecording(id!), onSuccess: () => { void client.invalidateQueries({ queryKey: ['web-recordings'] }); close() }, onError: fail })
  const save = useMutation({ mutationFn: () => webApi.saveRecording(id!, { name: name.trim() || r!.name, description: description.trim() || undefined }),
    onSuccess: res => { void client.invalidateQueries({ queryKey: ['web-recordings'] }); void client.invalidateQueries({ queryKey: ['workflows'] }); onSaved?.(res.workflowId); close() }, onError: fail })
  const insert = useMutation({ mutationFn: () => webApi.insertRecording(id!),
    onSuccess: graph => { onInsert?.(graph); close() }, onError: fail })

  const close = () => { setId(recordingId); setError(null); onClose() }
  const r = recording.data
  const online = (browsers.data ?? []).filter(b => b.online)
  const offline = (browsers.data ?? []).filter(b => !b.online)
  const missing = missingLogins(r?.steps).length
  const canSave = !!r && r.status === 'review' && liveSteps(r.steps).length > 0 && missing === 0

  return (
    <SlideOver open={open} title={mode === 'insert' ? 'Record steps here' : 'Record a task in Chrome'} onClose={close}
      description="Do the task once in your own Chrome. Jizza turns it into steps you can review."
      footer={r?.status === 'review' ? (
        <div className="flex items-center justify-between gap-2">
          <Button variant="ghost" size="sm" onClick={() => discard.mutate()} loading={discard.isPending}>Discard</Button>
          {mode === 'insert'
            ? <Button disabled={!canSave} loading={insert.isPending} onClick={() => insert.mutate()}>Add steps to workflow</Button>
            : <Button disabled={!canSave || !(name.trim() || r.name)} loading={save.isPending} onClick={() => save.mutate()}>Save as module</Button>}
        </div>
      ) : undefined}>
      {error && <p role="alert" className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-300">{error}</p>}

      {!id && (
        <form className="space-y-3 text-sm" onSubmit={e => { e.preventDefault(); start.mutate() }}>
          <label className="block">Browser agent
            <select aria-label="Browser agent" value={browserId} onChange={e => setBrowserId(e.target.value)}
              className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-2 py-2 dark:border-gray-600">
              <option value="">Choose a browser</option>
              {online.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
              {offline.map(b => <option key={b.id} value={b.id} disabled>{b.name}: open Chrome on {b.name}</option>)}
            </select>
          </label>
          {browsers.isSuccess && online.length === 0 && <p className="text-xs text-gray-500">No browser is online. Open Chrome with the Jizza Browser Agent, or add one in Settings › Agents.</p>}
          <label className="block">Start page
            <input aria-label="Start page" value={url} onChange={e => setUrl(e.target.value)} className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 dark:border-gray-600" />
          </label>
          <label className="block">Name
            <input aria-label="Recording name" value={name} onChange={e => setName(e.target.value)} className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 dark:border-gray-600" />
          </label>
          <Button type="submit" loading={start.isPending} disabled={!browserId || !/^https?:\/\/\S+/.test(url.trim()) || !name.trim()}><Circle className="h-4 w-4 fill-current" />Record</Button>
        </form>
      )}

      {id && !r && <div className="grid place-items-center py-10"><Spinner /></div>}

      {r?.status === 'recording' && (
        <div className="space-y-3">
          <p className="flex items-center gap-2 text-sm font-medium"><span className="h-2.5 w-2.5 animate-pulse rounded-full bg-red-500" />Recording in Chrome on {r.deviceName ?? 'your browser'}</p>
          <ol className="space-y-1 text-sm" aria-label="Captured steps">
            {liveSteps(r.steps).map((s, i) => <li key={s.seq} className="truncate">{i + 1}. {s.step.label ?? s.step.action}</li>)}
          </ol>
          <Button variant="secondary" loading={stop.isPending} onClick={() => stop.mutate()}><Square className="h-4 w-4" />Stop</Button>
        </div>
      )}

      {r?.status === 'failed' && <p className="text-sm text-gray-600">Nothing was captured. Try again, and check Chrome is open on the page.</p>}

      {r?.status === 'review' && (
        <div className="space-y-4">
          {mode === 'module' && (
            <div className="space-y-2 text-sm">
              <label className="block">Module name
                <input aria-label="Module name" value={name || r.name} onChange={e => setName(e.target.value)} className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 dark:border-gray-600" />
              </label>
              <label className="block">Description
                <input aria-label="Description" value={description} onChange={e => setDescription(e.target.value)} className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 dark:border-gray-600" />
              </label>
            </div>
          )}
          {missing > 0 && <p className="text-xs text-amber-700 dark:text-amber-300">Choose a saved login for each password step before saving.</p>}
          <RecordingReview recording={r} busy={edit.isPending} onEdit={ops => edit.mutate(ops)} />
        </div>
      )}
    </SlideOver>
  )
}
