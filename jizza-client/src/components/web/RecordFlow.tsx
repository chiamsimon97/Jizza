import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Circle, CircleDashed, PictureInPicture2, Square } from 'lucide-react'
import { Link } from 'react-router-dom'
import { webApi, type EditOp, type LinkedWorkflow, type Recording } from '@/api/web'
import { workflowsApi } from '@/api/workflows'
import { useWebSocket } from '@/hooks/useWebSocket'
import { useFloatingWindow } from '@/hooks/useFloatingWindow'
import { SlideOver } from '@/components/ui/SlideOver'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { RecordingReview } from './RecordingReview'
import { LinkedWorkflowsDialog } from './EditRecording'
import { hostOf, liveSteps, missingLogins, siteAllowed } from './recordingModel'
import type { BrowserAgent } from '@/api/web'

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
  // Edit recording: after its new version is saved, the workflows that run it, to offer updating.
  const [linked, setLinked] = useState<{ workflowId: string; linked: LinkedWorkflow[] } | null>(null)
  const save = useMutation({
    mutationFn: (asNew: boolean) => webApi.saveRecording(id!, { name: name.trim() || r!.name, description: description.trim() || undefined, ...(asNew ? { asNew } : {}) }),
    onSuccess: res => {
      void client.invalidateQueries({ queryKey: ['web-recordings'] }); void client.invalidateQueries({ queryKey: ['workflows'] })
      if (res.updated && res.linked?.length) { setLinked({ workflowId: res.workflowId, linked: res.linked }); return }
      onSaved?.(res.workflowId); close()
    }, onError: fail })
  const insert = useMutation({ mutationFn: () => webApi.insertRecording(id!),
    onSuccess: graph => { onInsert?.(graph); close() }, onError: fail })

  const close = () => { setId(recordingId); setError(null); float.close(); onClose() }
  const r = recording.data
  const editing = !!r?.sourceWorkflowId
  // Only a draft takes a new version here; one past draft is saved as a new recording.
  const source = useQuery({ queryKey: ['workflow', r?.sourceWorkflowId], queryFn: () => workflowsApi.get(r!.sourceWorkflowId!), enabled: open && editing })
  const sourceDraft = !source.data || source.data.status === 'DRAFT'
  const float = useFloatingWindow()
  const recordingNow = r?.status === 'recording'
  // The floating controls are only for while it records: they close once it stops.
  useEffect(() => { if (!recordingNow) float.close() }, [recordingNow, float.close])
  const online = (browsers.data ?? []).filter(b => b.online)
  const offline = (browsers.data ?? []).filter(b => !b.online)
  const missing = missingLogins(r?.steps).length
  const canSave = !!r && r.status === 'review' && liveSteps(r.steps).length > 0 && missing === 0

  if (linked) {
    return <LinkedWorkflowsDialog recordingId={linked.workflowId} linked={linked.linked}
      onClose={() => { const saved = linked.workflowId; setLinked(null); onSaved?.(saved); close() }} />
  }

  return (
    <SlideOver open={open} title={editing ? `Editing ${r!.name}` : mode === 'insert' ? 'Record steps here' : r?.kind === 'desktop' ? 'Recorded on your PC' : 'Record a task in Chrome'} onClose={close}
      description={editing ? 'Step through it in the browser\'s side panel and change what you need. Then review and save the changes here.' : 'Do the task once in your own Chrome. Jizza turns it into steps you can review.'}
      footer={r?.status === 'review' ? (
        <div className="flex items-center justify-between gap-2">
          <Button variant="ghost" size="sm" onClick={() => discard.mutate()} loading={discard.isPending}>Discard</Button>
          {mode === 'insert'
            ? <Button disabled={!canSave} loading={insert.isPending} onClick={() => insert.mutate()}>Add steps to workflow</Button>
            : editing
              ? (
                <div className="flex items-center gap-2">
                  <Button variant="secondary" disabled={!canSave || !(name.trim() || r.name)} loading={save.isPending && save.variables} onClick={() => save.mutate(true)}>Save as new recording</Button>
                  <Button disabled={!canSave || !sourceDraft} loading={save.isPending && !save.variables} onClick={() => save.mutate(false)}
                    title={sourceDraft ? 'Saves a new version of the recording' : 'It is past draft, so changes go through review: save it as a new recording'}>Save changes</Button>
                </div>
              )
              : <Button disabled={!canSave || !(name.trim() || r.name)} loading={save.isPending} onClick={() => save.mutate(false)}>Save as module</Button>}
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
          {browsers.isSuccess && <SetupChecklist browsers={browsers.data} chosen={browsers.data.find(b => b.id === browserId)} url={url} />}
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
          {editing && (
            <p className="rounded-lg bg-brand-50 px-3 py-2 text-xs text-brand-800 dark:bg-brand-950/40 dark:text-brand-200">
              Open the Jizza side panel in {r.deviceName ?? 'the browser'}. ▶ Next runs the next step, ● New action records new steps where the marker is,
              and “Run these only if shown…” puts the selected steps in a condition. Press Finish editing there, or Finish editing below, when you are done.
            </p>
          )}
          <p className="flex items-center gap-2 text-sm font-medium"><span className="h-2.5 w-2.5 animate-pulse rounded-full bg-red-500" />{editing ? `Editing in ${r.deviceName ?? 'your browser'}` : r.kind === 'desktop' ? `Recording in Windows apps on ${r.deviceName ?? 'your PC'}. Press Ctrl+Alt+R on the PC to stop.` : `Recording in Chrome on ${r.deviceName ?? 'your browser'}`}</p>
          <ol className="space-y-1 text-sm" aria-label="Captured steps">
            {liveSteps(r.steps).map((s, i) => <li key={s.seq} className="truncate">{i + 1}. {s.step.label ?? s.step.action}</li>)}
          </ol>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" loading={stop.isPending} onClick={() => stop.mutate()}><Square className="h-4 w-4" />{editing ? 'Finish editing' : 'Stop'}</Button>
            {r.kind !== 'desktop' && float.supported && !float.floating && (
              <Button variant="ghost" onClick={() => { float.open({ width: 340, height: 420 }).catch(() => undefined) }}>
                <PictureInPicture2 className="h-4 w-4" />Float controls over the site
              </Button>
            )}
          </div>
          {float.floating && <p className="text-xs text-gray-500">The controls float in a small window that stays on top. Close it any time.</p>}
          {float.floating && createPortal(
            <FloatingControls browser={r.deviceName ?? 'your browser'} steps={liveSteps(r.steps).map(s => s.step.label ?? s.step.action)}
              stopping={stop.isPending} onStop={() => stop.mutate()} />,
            float.floating.document.body)}
        </div>
      )}

      {r?.status === 'failed' && <p className="text-sm text-gray-600">{r.kind === 'desktop' ? 'Nothing was captured. Press Ctrl+Alt+R on the PC and try again.' : 'Nothing was captured. Try again, and check Chrome is open on the page.'}</p>}

      {r?.status === 'review' && (
        <div className="space-y-4">
          {mode === 'module' && (
            <div className="space-y-2 text-sm">
              {editing && !sourceDraft && (
                <p className="text-xs text-amber-700 dark:text-amber-300">“{source.data?.name}” is past draft, so its changes go through review. Save these as a new recording.</p>
              )}
              <label className="block">Module name
                <input aria-label="Module name" value={name || r.name} onChange={e => setName(e.target.value)} className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 dark:border-gray-600" />
              </label>
              <label className="block">Description
                <input aria-label="Description" value={description} onChange={e => setDescription(e.target.value)} className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 dark:border-gray-600" />
              </label>
            </div>
          )}
          {missing > 0 && <p className="text-xs text-amber-700 dark:text-amber-300">{r.kind === 'desktop' ? 'Name the secret saved on the PC (Jizza Agent › Secrets) for each password step before saving.' : 'Choose a saved login for each password step before saving.'}</p>}
          <RecordingReview recording={r} busy={edit.isPending} onEdit={ops => edit.mutate(ops)} />
        </div>
      )}
    </SlideOver>
  )
}

/** What the floating window shows while Chrome records: the live steps and Stop. */
function FloatingControls({ browser, steps, stopping, onStop }: {
  browser: string; steps: string[]; stopping: boolean; onStop: () => void
}) {
  const end = useRef<HTMLLIElement>(null)
  useEffect(() => { end.current?.scrollIntoView?.({ block: 'end' }) }, [steps.length])
  return (
    <div className="flex h-screen flex-col gap-3 p-4 text-sm">
      <p className="flex items-center gap-2 font-medium"><span className="h-2.5 w-2.5 animate-pulse rounded-full bg-red-500" />Recording in {browser}</p>
      <ol className="min-h-0 flex-1 space-y-1 overflow-y-auto" aria-label="Captured steps">
        {steps.length === 0 && <li className="text-gray-500">Do the task in the site's window. Each step shows up here.</li>}
        {steps.map((label, i) => <li key={i} ref={i === steps.length - 1 ? end : undefined} className="truncate">{i + 1}. {label}</li>)}
      </ol>
      <Button variant="secondary" loading={stopping} onClick={onStop}><Square className="h-4 w-4" />Stop recording</Button>
    </div>
  )
}

/**
 * The one-time setup a browser recording needs, shown until it is all done: the extension, a
 * paired browser that is online, and the start page's site on that browser's allowed list.
 */
export function SetupChecklist({ browsers, chosen, url }: { browsers: BrowserAgent[]; chosen?: BrowserAgent; url: string }) {
  const paired = browsers.length > 0
  const online = browsers.some(b => b.online)
  const host = hostOf(url)
  const allowed = !!chosen && !!host && siteAllowed(host, chosen.allowedSites ?? [])
  const items: { done: boolean; title: string; how: React.ReactNode }[] = [
    { done: paired, title: 'Install the Jizza Browser Agent',
      how: <>Load the extension in Chrome or another Chromium browser. <Link className="text-brand-600 underline" to="/help/browser-recording">How to install it</Link></> },
    { done: paired, title: 'Pair the browser',
      how: <>In <Link className="text-brand-600 underline" to="/settings/devices">Settings › Devices</Link>, press Pair a device, open the Browser tab, and paste the key into the extension.</> },
    { done: online, title: 'Open the browser', how: 'Jizza can record once the paired browser is open and shows as online.' },
    { done: allowed, title: host ? `Allow ${host}` : 'Allow the start page’s site',
      how: chosen
        ? <>In the extension’s Options on {chosen.name}, add {host ? <code>{host}</code> : 'the site'} under Allowed sites and accept Chrome’s prompt.</>
        : 'Choose the browser and type the start page, then add its site in the extension’s Options.' },
  ]
  if (items.every(i => i.done)) return null
  return (
    <section aria-label="Before you record" className="rounded-lg border border-gray-200 p-3 dark:border-gray-700">
      <p className="text-xs font-semibold text-gray-700 dark:text-gray-200">Before you record</p>
      <ol className="mt-2 space-y-2">
        {items.map(item => (
          <li key={item.title} className="flex gap-2 text-xs">
            {item.done
              ? <CheckCircle2 aria-label="Done" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-green-600" />
              : <CircleDashed aria-label="To do" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />}
            <span className={item.done ? 'text-gray-500 line-through decoration-gray-300' : 'text-gray-800 dark:text-gray-200'}>
              <span className="font-medium">{item.title}</span>
              {!item.done && <span className="block text-gray-500 dark:text-gray-400">{item.how}</span>}
            </span>
          </li>
        ))}
      </ol>
    </section>
  )
}
