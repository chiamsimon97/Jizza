import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronDown, ChevronRight, GitBranch, History, ListChecks, PenLine, PlayCircle, Power, RotateCcw, ShieldCheck, Sparkles, X } from 'lucide-react'
import { proceduresApi } from '@/api/procedures'
import type { ProcedureSummary, WorkflowDto } from '@/types'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { Dialog } from '@/components/ui/Dialog'
import { fieldClass } from '@/components/ui/SlideOver'
import { WiresBoard } from '@/components/workflow/WiresBoard'
import { fromGraph } from '@/components/workflow/canvasModel'
import { changesBetween, stateOf, stepsOf, type ProcedureState } from '@/components/workflow/procedureModel'

const SECTIONS: Array<{ state: ProcedureState; title: string; hint: string }> = [
  { state: 'review', title: 'Needs your review', hint: 'Check the steps. Approve to make the task follow them; reject to keep it running the usual way.' },
  { state: 'active', title: 'In use', hint: 'These run exactly as approved. Turn one off any time.' },
  { state: 'off', title: 'Not in use', hint: 'Rejected or turned off. You can bring back a version you approved before.' },
]

/**
 * Procedures: workflows an agent wrote down from a task you marked as repeating. Each needs your
 * approval before its task follows it, and every later change comes back here for review.
 */
export function ProceduresPage() {
  const qc = useQueryClient()
  const { data, isLoading, error } = useQuery({
    queryKey: ['procedures'],
    queryFn: () => proceduresApi.list(),
  })
  const example = useMutation({
    mutationFn: () => proceduresApi.addExample(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['procedures'] })
      qc.invalidateQueries({ queryKey: ['tasks'] })
    },
  })

  // Tasks link here with ?open=<procedure id>: that card opens and comes into view.
  const [params] = useSearchParams()
  const openId = params.get('open') ?? example.data?.procedure.id ?? null
  useEffect(() => {
    if (openId && data) document.getElementById(`procedure-${openId}`)?.scrollIntoView({ block: 'center' })
  }, [openId, data])

  return (
    <div className="page">
      <PageHeader icon={GitBranch} title="Procedures"
        description="Steps your agents learned from repeating tasks. Nothing runs until you approve it."
        actions={<Button size="sm" variant="secondary" loading={example.isPending} onClick={() => example.mutate()}><Sparkles className="h-4 w-4" />Add an example</Button>} />

      <HowItWorks />

      {example.error && <p className="mb-3 text-sm text-red-600">{(example.error as Error).message}</p>}
      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && <p className="text-sm text-red-600">Could not load procedures: {(error as Error).message}</p>}

      {!isLoading && !error && (!data || data.length === 0) && (
        <EmptyState icon={GitBranch} title="No procedures yet"
          description="On the Tasks page, turn on Learn the steps for a repeating task. After it next runs, its agent writes down the steps and they appear here for you to review."
          action={{ label: 'Add an example', onClick: () => example.mutate() }} />
      )}

      {data && data.length > 0 && SECTIONS.map(section => {
        const items = data.filter(p => stateOf(p).state === section.state)
        if (items.length === 0) return null
        return (
          <section key={section.state} className="mb-6">
            <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
              {section.title} <span className="font-normal text-gray-400">({items.length})</span>
            </h2>
            <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{section.hint}</p>
            <div className="stagger space-y-2">
              {items.map(p => <ProcedureCard key={p.workflow.id} procedure={p} focused={p.workflow.id === openId} />)}
            </div>
          </section>
        )
      })}
    </div>
  )
}

function HowItWorks() {
  const steps = [
    { icon: PenLine, title: 'An agent writes it down', text: 'After a repeating task runs, its agent saves the steps it took.' },
    { icon: ShieldCheck, title: 'You review it', text: 'You see every step in plain words and approve or reject.' },
    { icon: PlayCircle, title: 'It runs exactly', text: 'The task follows the approved steps every time. Any change comes back for review.' },
  ]
  return (
    <ol className="mb-6 grid gap-2 sm:grid-cols-3">
      {steps.map((s, i) => (
        <li key={s.title} className="flex gap-3 rounded-xl border border-gray-100 p-3 dark:border-gray-800">
          <s.icon className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
          <div>
            <p className="text-xs font-semibold text-gray-800 dark:text-gray-200">{i + 1}. {s.title}</p>
            <p className="text-xs text-gray-500 dark:text-gray-400">{s.text}</p>
          </div>
        </li>
      ))}
    </ol>
  )
}

function ProcedureCard({ procedure: p, focused }: { procedure: ProcedureSummary; focused: boolean }) {
  const qc = useQueryClient()
  const info = stateOf(p)
  const wf = p.workflow
  const [open, setOpen] = useState(info.state === 'review' || focused)
  const [diagram, setDiagram] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [notice, setNotice] = useState<string | null>(null)

  const done = (res: { message: string }) => {
    setNotice(res.message)
    qc.invalidateQueries({ queryKey: ['procedures'] })
    qc.invalidateQueries({ queryKey: ['procedure-history', wf.id] })
    qc.invalidateQueries({ queryKey: ['tasks'] })
  }
  const approve = useMutation({ mutationFn: () => proceduresApi.approve(wf.id), onSuccess: done })
  const reject = useMutation({
    mutationFn: () => proceduresApi.reject(wf.id, feedback.trim() || undefined),
    onSuccess: res => { setRejecting(false); setFeedback(''); done(res) },
  })
  const disable = useMutation({ mutationFn: () => proceduresApi.disable(wf.id), onSuccess: done })
  const failure = [approve, reject, disable].find(m => m.error)?.error as Error | undefined

  const steps = stepsOf(wf.graph)
  const source = [
    p.agentName && `Written by ${p.agentName}`,
    `version ${wf.version}`,
  ].filter(Boolean).join(' · ')

  return (
    <Card padding="md" id={`procedure-${wf.id}`} className={focused ? 'ring-2 ring-brand-400' : ''}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-1 items-start gap-2">
          <button type="button" aria-expanded={open} aria-label={open ? 'Hide the steps' : 'Show the steps'} onClick={() => setOpen(!open)}>
            {open ? <ChevronDown className="mt-0.5 h-4 w-4 shrink-0 text-gray-400" /> : <ChevronRight className="mt-0.5 h-4 w-4 shrink-0 text-gray-400" />}
          </button>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <button type="button" onClick={() => setOpen(!open)} className="text-left text-sm font-medium text-gray-900 hover:underline dark:text-gray-100">{wf.name}</button>
              <Badge variant={info.tone}>{info.badge}</Badge>
            </div>
            {wf.description && <p className="mt-0.5 text-xs text-gray-600 dark:text-gray-300">{wf.description}</p>}
            <p className="mt-0.5 text-xs text-gray-400">
              {wf.sourceTaskId && (p.sourceTaskTitle
                ? <>Learned from the task <Link to={`/tasks?focus=${wf.sourceTaskId}`} onClick={e => e.stopPropagation()} className="font-medium text-brand-600 hover:underline dark:text-brand-400">{p.sourceTaskTitle}</Link> · </>
                : <>Its task was deleted · </>)}
              {source}
            </p>
          </div>
        </div>
        <div className="flex shrink-0 gap-2">
          {info.state === 'review' && <>
            <Button size="sm" loading={approve.isPending} onClick={() => approve.mutate()}><Check className="h-4 w-4" />Approve</Button>
            <Button size="sm" variant="secondary" onClick={() => setRejecting(true)}><X className="h-4 w-4" />Reject</Button>
          </>}
          {info.state === 'active' && (
            <Button size="sm" variant="secondary" loading={disable.isPending} onClick={() => disable.mutate()}><Power className="h-4 w-4" />Turn off</Button>
          )}
        </div>
      </div>

      <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{info.explain}</p>
      {wf.status === 'REJECTED' && p.reviewFeedback && (
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Your feedback: “{p.reviewFeedback}”</p>
      )}
      {notice && <p className="mt-1 text-xs text-emerald-600 dark:text-emerald-400">{notice}</p>}
      {failure && <p className="mt-1 text-xs text-red-600">{failure.message}</p>}

      {open && (
        <div className="mt-3 space-y-3 border-t border-gray-100 pt-3 dark:border-gray-800">
          <div>
            <div className="mb-1 flex items-center justify-between">
              <p className="flex items-center gap-1.5 text-xs font-semibold text-gray-700 dark:text-gray-300"><ListChecks className="h-3.5 w-3.5" />What it does, step by step</p>
              <Button size="sm" variant="ghost" onClick={() => setDiagram(!diagram)}>{diagram ? 'Show as list' : 'Show as diagram'}</Button>
            </div>
            {diagram
              ? <div className="h-80 overflow-hidden rounded-lg border border-gray-100 dark:border-gray-800">
                  <WiresBoard graph={fromGraph(wf.graph)} onChange={() => {}} readOnly compact />
                </div>
              : <ol className="space-y-1">
                  {steps.map((s, i) => (
                    <li key={s.id} className="flex gap-2 text-xs">
                      <span className="w-5 shrink-0 text-right text-gray-400">{i + 1}.</span>
                      <div className="min-w-0">
                        {s.when && <span className="mr-1 font-medium text-amber-600 dark:text-amber-400">{s.when}:</span>}
                        <span className="font-medium text-gray-800 dark:text-gray-200">{s.kind}</span>
                        {s.label && <span className="text-gray-700 dark:text-gray-300"> · {s.label}</span>}
                        {s.detail && <p className="truncate font-mono text-[11px] text-gray-400">{s.detail}</p>}
                      </div>
                    </li>
                  ))}
                </ol>}
          </div>
          {wf.version > 1 && <VersionHistory workflowId={wf.id} current={wf.version} onRestored={done} />}
        </div>
      )}

      <Dialog open={rejecting} title="Reject this procedure?"
        description="It won't be used. Tell the agent what to change, and it can propose a better version."
        onClose={() => setRejecting(false)}>
        <textarea className={fieldClass} rows={3} value={feedback} onChange={e => setFeedback(e.target.value)}
          placeholder="For example: skip invoices under $10, and ask me before paying." aria-label="Feedback for the agent" />
        <div className="mt-3 flex justify-end gap-2">
          <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>Cancel</Button>
          <Button size="sm" variant="danger" loading={reject.isPending} onClick={() => reject.mutate()}>Reject</Button>
        </div>
      </Dialog>
    </Card>
  )
}

const STATUS_WORDS: Record<string, string> = {
  APPROVED: 'Approved', PENDING_REVIEW: 'Waiting for review', REJECTED: 'Rejected', DRAFT: 'Draft',
}

function VersionHistory({ workflowId, current, onRestored }: {
  workflowId: string; current: number; onRestored: (res: { message: string }) => void
}) {
  const { data } = useQuery({
    queryKey: ['procedure-history', workflowId],
    queryFn: () => proceduresApi.history(workflowId),
  })
  const rollback = useMutation({
    mutationFn: (version: number) => proceduresApi.rollback(workflowId, version),
    onSuccess: onRestored,
  })
  if (!data || data.length < 2) return null

  const newestFirst = [...data].reverse()
  return (
    <div>
      <p className="mb-1 flex items-center gap-1.5 text-xs font-semibold text-gray-700 dark:text-gray-300"><History className="h-3.5 w-3.5" />Versions</p>
      <ul className="space-y-1.5">
        {newestFirst.map(v => {
          const previous = data.find(o => o.version === v.version - 1)
          return (
            <li key={v.version} className="flex items-start justify-between gap-2 text-xs">
              <div className="min-w-0">
                <span className="font-medium text-gray-800 dark:text-gray-200">Version {v.version}</span>
                <span className="text-gray-400"> · {STATUS_WORDS[v.status] ?? v.status}{v.version === current ? ' · current' : ''}</span>
                {previous && <ChangeSummary before={previous} after={v} />}
              </div>
              {v.version !== current && v.status === 'APPROVED' && (
                <Button size="sm" variant="ghost" loading={rollback.isPending && rollback.variables === v.version}
                  onClick={() => rollback.mutate(v.version)}><RotateCcw className="h-3.5 w-3.5" />Use this version</Button>
              )}
            </li>
          )
        })}
      </ul>
      {rollback.error && <p className="mt-1 text-xs text-red-600">{(rollback.error as Error).message}</p>}
    </div>
  )
}

function ChangeSummary({ before, after }: { before: WorkflowDto; after: WorkflowDto }) {
  const c = changesBetween(before.graph, after.graph)
  const parts = [
    c.added.length > 0 && `added ${c.added.join(', ')}`,
    c.changed.length > 0 && `changed ${c.changed.join(', ')}`,
    c.removed.length > 0 && `removed ${c.removed.join(', ')}`,
  ].filter(Boolean)
  return <p className="text-gray-500 dark:text-gray-400">{parts.length ? `Since version ${before.version}: ${parts.join('; ')}.` : `Same steps as version ${before.version}.`}</p>
}
