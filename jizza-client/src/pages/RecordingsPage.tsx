import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Circle, Play, Trash2, Video } from 'lucide-react'
import { webApi } from '@/api/web'
import { workflowsApi } from '@/api/workflows'
import { RecordFlow } from '@/components/web/RecordFlow'
import { DeleteWorkflowButton } from '@/components/workflow/DeleteWorkflowButton'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { PageHeader } from '@/components/ui/PageHeader'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState, LoadError } from '@/components/ui/EmptyState'

/**
 * Tasks recorded in Chrome or in Windows apps: the saved modules, and recordings still being made
 * or reviewed. Windows recordings start on the PC with the agent's hotkey (Ctrl+Alt+R).
 */
export function RecordingsPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  // ?review=<id>: chat's "Open review" lands straight on that recording's review.
  const [resume, setResume] = useState<string | undefined>(() => params.get('review') ?? undefined)
  const [recording, setRecording] = useState(() => params.has('review'))
  useEffect(() => {
    if (params.has('review')) setParams(p => { p.delete('review'); return p }, { replace: true })
  }, [params, setParams])
  const drafts = useQuery({ queryKey: ['web-recordings', 'drafts'], queryFn: () => webApi.listRecordings(['recording', 'review']) })
  const modules = useQuery({ queryKey: ['workflows'], queryFn: () => workflowsApi.list() })
  const saved = (modules.data ?? []).filter(w => w.origin === 'RECORDED')
  const run = useMutation({ mutationFn: (id: string) => workflowsApi.run(id) })
  // A draft is only notes until it is saved, so it goes with one confirm, no dialog.
  const discard = useMutation({
    mutationFn: (id: string) => webApi.discardRecording(id),
    onSuccess: () => void drafts.refetch(),
  })
  const loading = drafts.isLoading || modules.isLoading

  return (
    <div className="page">
      <RecordFlow key={resume ?? 'new'} open={recording} mode="module" recordingId={resume}
        onClose={() => { setRecording(false); setResume(undefined); void drafts.refetch() }}
        onSaved={id => navigate(`/workflows?open=${id}`)} />
      <PageHeader icon={Video} title="Recordings" description="Tasks you did once in Chrome or a Windows app, kept as modules. On a PC with the Jizza Windows agent, press Ctrl+Alt+R to record."
        actions={<Button size="sm" onClick={() => setRecording(true)}><Circle className="h-4 w-4 fill-current" />Record</Button>} />
      {loading && <div className="grid place-items-center py-16"><Spinner /></div>}
      {(drafts.isError || modules.isError) && <LoadError what="recordings" onRetry={() => { void drafts.refetch(); void modules.refetch() }} />}
      {!loading && !drafts.isError && !modules.isError && (
        <div className="space-y-6">
          {(drafts.data ?? []).length > 0 && (
            <section aria-label="Drafts" className="space-y-2">
              <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-300">Drafts</h2>
              {drafts.data!.map(d => (
                <Card key={d.id} className="flex items-center gap-3 p-3">
                  <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium">{d.name}</p><p className="truncate text-xs text-gray-500">{d.kind === 'desktop' ? `${d.app ?? 'Windows apps'} on ${d.deviceName ?? 'your PC'}` : d.startUrl}, {d.stepCount} steps</p></div>
                  <Badge>{d.status === 'recording' ? 'Recording' : 'Ready to review'}</Badge>
                  <Button size="sm" variant="secondary" onClick={() => { setResume(d.id); setRecording(true) }}>{d.status === 'recording' ? 'Open' : 'Review'}</Button>
                  <Button size="sm" variant="ghost" aria-label={`Discard ${d.name}`} title="Discard draft"
                    className="text-red-600 hover:bg-red-50 hover:text-red-700 dark:text-red-400 dark:hover:bg-red-950/40"
                    loading={discard.isPending && discard.variables === d.id}
                    onClick={() => { if (window.confirm(`Discard the draft "${d.name}"? Its recorded steps are thrown away.`)) discard.mutate(d.id) }}>
                    <Trash2 className="h-4 w-4" />Discard
                  </Button>
                </Card>
              ))}
            </section>
          )}
          <section aria-label="Saved recordings" className="space-y-2">
            <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-300">Saved</h2>
            {saved.length === 0
              ? <EmptyState icon={Video} title="No recordings yet" description="Press Record and do a task in Chrome, or press Ctrl+Alt+R on a Windows PC, then save it as a module." />
              : saved.map(w => (
                <Card key={w.id} className="flex items-center gap-3 p-3">
                  <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium">{w.name}</p><p className="truncate text-xs text-gray-500">{w.description}</p></div>
                  <Button size="sm" variant="secondary" loading={run.isPending && run.variables === w.id} onClick={() => run.mutate(w.id)}><Play className="h-4 w-4" />Run now</Button>
                  <Link to={`/workflows?open=${w.id}`} className="text-sm font-medium text-brand-600 hover:underline">Open in builder</Link>
                  <DeleteWorkflowButton workflowId={w.id} name={w.name} what="recording" />
                </Card>
              ))}
          </section>
        </div>
      )}
    </div>
  )
}
