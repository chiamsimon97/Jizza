import { FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { tasksApi } from '@/api/tasks'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Field, SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { useFormStore } from '@/jizza/formStore'
import { useAssistantStore } from '@/jizza/assistantStore'
import { matchAgent, str } from './agentMatch'

const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone

/** ISO date-time to the value a datetime-local input wants, in the user's local time. */
function toLocalInput(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function TaskForm() {
  const request = useFormStore(s => (s.request?.form === 'new_task' ? s.request : null))
  const close = useFormStore(s => s.close)
  const note = useAssistantStore(s => s.note)
  const qc = useQueryClient()
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })

  const [title, setTitle] = useState('')
  const [agentId, setAgentId] = useState('')
  const [schedule, setSchedule] = useState<'once' | 'recurring'>('once')
  const [runAt, setRunAt] = useState('')
  const [cron, setCron] = useState('')
  const [timezone, setTimezone] = useState(localZone)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!request) return
    const f = request.fields
    setTitle(str(f.title))
    setAgentId(matchAgent(agents, f.agent_key ?? f.agent)?.id ?? agents?.find(a => a.orchestrator)?.id ?? '')
    setSchedule(f.schedule === 'recurring' || (!f.schedule && str(f.cron)) ? 'recurring' : 'once')
    setRunAt(str(f.run_at) ? toLocalInput(str(f.run_at)) : '')
    setCron(str(f.cron))
    setTimezone(str(f.timezone) || localZone)
    setError('')
  }, [request?.nonce, agents]) // eslint-disable-line react-hooks/exhaustive-deps

  const create = useMutation({
    mutationFn: () => tasksApi.create({
      title: title.trim(),
      agentId,
      scheduleType: schedule === 'once' ? 'ONE_TIME' : 'RECURRING',
      runAt: schedule === 'once' ? new Date(runAt).toISOString() : undefined,
      cronOrRrule: schedule === 'recurring' ? cron.trim() : undefined,
      timezone,
      repetitive: false,
    }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['tasks'] })
      if (request?.byJizza) note(`Scheduled "${title.trim()}". I'll take it from here.`)
      close()
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not create the task'),
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!title.trim()) return setError('Say what the task is.')
    if (!agentId) return setError('Choose who does it.')
    if (schedule === 'once' && !runAt) return setError('Pick when it runs.')
    if (schedule === 'recurring' && !cron.trim()) return setError('Give a schedule, e.g. 0 8 * * * for 8am daily.')
    setError('')
    create.mutate()
  }

  const hi = !!request?.byJizza

  return (
    <SlideOver
      open={!!request}
      title="New task"
      description="Work an agent does for you, once or on a schedule."
      onClose={close}
      footer={<>
        <Button variant="secondary" onClick={close}>Cancel</Button>
        <Button type="submit" form="task-form" loading={create.isPending}>Create task</Button>
      </>}
    >
      <form id="task-form" onSubmit={submit} className="flex flex-col gap-5">
        {hi && (
          <div className="flex items-start gap-2.5 rounded-xl border border-brand-300 bg-brand-50 px-3 py-2.5 text-sm text-brand-800 animate-fade-in dark:border-brand-700 dark:bg-brand-500/10 dark:text-brand-200">
            <Sparkles className="mt-0.5 h-4 w-4 shrink-0" />
            <span>Jizza filled this in. Check it over and create it, or ask Jizza to change something.</span>
          </div>
        )}
        <div className={hi && title ? 'rounded-lg animate-jizza-filled' : ''}>
          <Input label="What should happen" value={title} onChange={e => setTitle(e.target.value)} placeholder="Summarise my inbox" />
        </div>
        <Field label="Who does it" highlight={hi && !!agentId}>
          <select className={fieldClass} value={agentId} onChange={e => setAgentId(e.target.value)}>
            <option value="">Choose an agent…</option>
            {agents?.map(a => <option key={a.id} value={a.id}>{a.name}{a.domain ? ` · ${a.domain}` : ''}</option>)}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2 rounded-xl bg-gray-100 p-1 dark:bg-gray-800">
          {(['once', 'recurring'] as const).map(kind => (
            <button key={kind} type="button" onClick={() => setSchedule(kind)}
              className={`rounded-lg py-1.5 text-sm font-medium transition-all ${schedule === kind
                ? 'bg-white text-brand-700 shadow-sm dark:bg-gray-700 dark:text-brand-300'
                : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'}`}>
              {kind === 'once' ? 'Once' : 'Repeats'}
            </button>
          ))}
        </div>
        {schedule === 'once' ? (
          <Field label="When" highlight={hi && !!runAt}>
            <input type="datetime-local" className={fieldClass} value={runAt} onChange={e => setRunAt(e.target.value)} />
          </Field>
        ) : (
          <Field label="Schedule (cron)" hint="Minute hour day month weekday, e.g. 0 8 * * 1-5 for 8am on weekdays" highlight={hi && !!cron}>
            <input className={`${fieldClass} font-mono`} value={cron} onChange={e => setCron(e.target.value)} placeholder="0 8 * * *" />
          </Field>
        )}
        <Field label="Time zone" highlight={hi && !!request?.fields.timezone}>
          <input className={fieldClass} value={timezone} onChange={e => setTimezone(e.target.value)} />
        </Field>
        {error && <p className="text-sm text-red-500">{error}</p>}
      </form>
    </SlideOver>
  )
}
