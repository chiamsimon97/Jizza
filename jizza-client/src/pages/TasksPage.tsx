import { useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { Bot, GitBranch, GraduationCap, ListTodo, Plus, Sparkles } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { TaskForm } from '@/components/forms/TaskForm'
import { useFormStore } from '@/jizza/formStore'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { agentsApi } from '@/api/agents'
import { tasksApi } from '@/api/tasks'
import { proceduresApi } from '@/api/procedures'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { TASK_STATUS, describeSchedule, linkOf, type TaskLink } from '@/components/workflow/taskModel'
import type { TaskDto } from '@/types'

const LINK_STYLE: Record<TaskLink['kind'], { icon: typeof Bot; tone: string; badge?: string }> = {
  follows: { icon: GitBranch, tone: 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-800 dark:bg-emerald-500/10 dark:text-emerald-200', badge: 'Follows a procedure' },
  review: { icon: GitBranch, tone: 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-800 dark:bg-amber-500/10 dark:text-amber-200', badge: 'Procedure to review' },
  learning: { icon: GraduationCap, tone: 'border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-800 dark:bg-sky-500/10 dark:text-sky-200', badge: 'Learning the steps' },
  agent: { icon: Bot, tone: 'border-gray-200 bg-gray-50 text-gray-700 dark:border-gray-800 dark:bg-gray-800/50 dark:text-gray-300' },
}

/**
 * Tasks: work an agent does on a schedule. A repeating one can be learned as a procedure, and each
 * card says which: the agent decides each time, it is learning, or it follows an approved procedure.
 */
export function TasksPage() {
  const openForm = useFormStore(s => s.open)
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['tasks'], queryFn: () => tasksApi.list() })
  const { data: procedures } = useQuery({ queryKey: ['procedures'], queryFn: () => proceduresApi.list() })
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['tasks'] })
    qc.invalidateQueries({ queryKey: ['procedures'] })
  }
  const example = useMutation({ mutationFn: () => proceduresApi.addExample(), onSuccess: refresh })

  // The Procedures page links here with ?focus=<task id>.
  const [params] = useSearchParams()
  const focus = params.get('focus')
  useEffect(() => {
    if (focus && data) document.getElementById(`task-${focus}`)?.scrollIntoView({ block: 'center' })
  }, [focus, data])

  const live = data?.filter(t => t.status !== 'CANCELLED') ?? []
  const cancelled = data?.filter(t => t.status === 'CANCELLED') ?? []

  return (
    <div className="page">
      <TaskForm />
      <PageHeader icon={ListTodo} title="Tasks"
        description="Work an agent does for you, once or on a schedule."
        actions={<>
          <Button size="sm" variant="secondary" loading={example.isPending} onClick={() => example.mutate()}><Sparkles className="h-4 w-4" />Add an example</Button>
          <Button size="sm" onClick={() => openForm('new_task')}><Plus className="h-4 w-4" />New task</Button>
        </>} />

      <div className="mb-6 rounded-xl border border-gray-100 p-3 text-xs text-gray-600 dark:border-gray-800 dark:text-gray-300">
        <p><span className="font-semibold text-gray-800 dark:text-gray-200">A task</span> says <em>what</em> to do and <em>when</em>. By default its agent works out <em>how</em> each time it runs.</p>
        <p className="mt-1">Turn on <span className="font-semibold">Learn the steps</span> for a repeating task and, after its next run, the agent writes down what it did as a{' '}
          <Link to="/procedures" className="font-semibold text-brand-600 hover:underline dark:text-brand-400">procedure</Link>. Once you approve it, the task follows those exact steps every time instead of improvising.</p>
      </div>

      {example.error && <p className="mb-3 text-sm text-red-600">{(example.error as Error).message}</p>}
      {example.data && (
        <p className="mb-3 text-sm text-emerald-700 dark:text-emerald-300">
          Added the task "{example.data.task.title}" and its procedure. <Link to={`/procedures?open=${example.data.procedure.id}`} className="font-medium underline">Review the procedure</Link> to see how they connect.
        </p>
      )}

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && live.length === 0 && (
        <EmptyState icon={ListTodo} title="No tasks yet"
          description="Create one, ask Jizza to schedule something, or add an example to see how a task and its procedure work together."
          action={{ label: 'Add an example', onClick: () => example.mutate() }} />
      )}

      {live.length > 0 && (
        <div className="stagger space-y-2">
          {live.map(task => <TaskCard key={task.id} task={task} focused={task.id === focus}
            agentKey={agents?.find(a => a.id === task.agentId)?.key ?? 'other'}
            link={linkOf(task, procedures)} onChanged={refresh} />)}
        </div>
      )}

      {cancelled.length > 0 && (
        <details className="mt-6">
          <summary className="cursor-pointer text-xs text-gray-500">Cancelled ({cancelled.length})</summary>
          <ul className="mt-2 space-y-1 text-xs text-gray-500">{cancelled.map(t => <li key={t.id}>{t.title}</li>)}</ul>
        </details>
      )}
    </div>
  )
}

function TaskCard({ task, agentKey, link, focused, onChanged }: {
  task: TaskDto; agentKey: string; link: TaskLink; focused: boolean; onChanged: () => void
}) {
  const cancel = useMutation({ mutationFn: () => tasksApi.cancel(task.id), onSuccess: onChanged })
  const learn = useMutation({ mutationFn: (on: boolean) => tasksApi.setRepetitive(task.id, on), onSuccess: onChanged })
  const status = TASK_STATUS[task.status] ?? { label: task.status, tone: 'default' as const }
  const style = LINK_STYLE[link.kind]
  const recurring = task.scheduleType === 'RECURRING'
  const failure = (cancel.error ?? learn.error) as Error | null

  return (
    <Card padding="md" className={focused ? 'ring-2 ring-brand-400' : ''}>
      <div id={`task-${task.id}`} className="flex items-start justify-between gap-3">
        <AgentLogo agentKey={agentKey} size={36} state={task.status === 'RUNNING' ? 'working' : 'idle'} />
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex flex-wrap items-center gap-2">
            <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{task.title}</p>
            <Badge variant={status.tone}>{status.label}</Badge>
            {style.badge && <Badge variant={link.kind === 'follows' ? 'success' : link.kind === 'review' ? 'warning' : 'info'}>{style.badge}</Badge>}
          </div>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {describeSchedule(task)}{task.agentName && ` · done by ${task.agentName}`}
            {task.nextRunAt && task.status !== 'CANCELLED' && ` · next run ${new Date(task.nextRunAt).toLocaleString()}`}
          </p>
          {task.lastRunAt && (
            <p className="mt-0.5 truncate text-xs text-gray-400" title={task.lastResult}>
              Last ran {new Date(task.lastRunAt).toLocaleString()}{task.lastResult && `: ${task.lastResult}`}
            </p>
          )}

          <div className={`mt-2 flex items-start gap-2 rounded-lg border px-2.5 py-2 text-xs ${style.tone}`}>
            <style.icon className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <div className="min-w-0 flex-1">
              <p>{link.text}</p>
              {link.procedure && (
                <Link to={`/procedures?open=${link.procedure.workflow.id}`} className="mt-0.5 inline-block font-semibold underline">
                  {link.kind === 'review' ? 'Review the procedure' : 'See the procedure'}
                </Link>
              )}
            </div>
          </div>
          {failure && <p className="mt-1 text-xs text-red-600">{failure.message}</p>}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          {recurring && task.status !== 'CANCELLED' && !task.boundWorkflowId && (
            <Button size="sm" variant="ghost" loading={learn.isPending} onClick={() => learn.mutate(!task.repetitive)}
              title={task.repetitive ? 'Stop learning: the agent will not write down its steps' : 'After the next run, the agent writes down its steps for you to review'}>
              <GraduationCap className="h-4 w-4" />{task.repetitive ? 'Stop learning' : 'Learn the steps'}
            </Button>
          )}
          {task.status !== 'CANCELLED' && task.status !== 'DONE' && (
            <Button size="sm" variant="danger" loading={cancel.isPending} onClick={() => cancel.mutate()}>Cancel</Button>
          )}
        </div>
      </div>
    </Card>
  )
}
