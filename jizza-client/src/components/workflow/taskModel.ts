import type { ProcedureSummary, TaskDto } from '@/types'
import { stateOf } from './procedureModel'

const DAYS: Record<string, string> = {
  '0': 'Sunday', '1': 'Monday', '2': 'Tuesday', '3': 'Wednesday', '4': 'Thursday', '5': 'Friday', '6': 'Saturday', '7': 'Sunday',
  SUN: 'Sunday', MON: 'Monday', TUE: 'Tuesday', WED: 'Wednesday', THU: 'Thursday', FRI: 'Friday', SAT: 'Saturday',
}

/**
 * The schedule's cron as the engine reads it (Spring: second minute hour day month weekday). People
 * write the usual five fields, so a five-field one gets "0" seconds in front.
 */
export function toEngineCron(cron: string): string {
  const parts = cron.trim().split(/\s+/)
  return parts.length === 5 ? `0 ${parts.join(' ')}` : cron.trim()
}

function clock(hour: string, minute: string): string | null {
  const h = Number(hour), m = Number(minute)
  if (!Number.isInteger(h) || !Number.isInteger(m)) return null
  const suffix = h < 12 ? 'am' : 'pm'
  const h12 = h % 12 === 0 ? 12 : h % 12
  return m === 0 ? `${h12}${suffix}` : `${h12}:${String(m).padStart(2, '0')}${suffix}`
}

/** "Every Monday at 9am", or the raw schedule when it is not one of the common shapes. */
export function describeSchedule(task: Pick<TaskDto, 'scheduleType' | 'cronOrRrule' | 'runAt'>): string {
  if (task.scheduleType === 'ONE_TIME') {
    return task.runAt ? `Once, on ${new Date(task.runAt).toLocaleString()}` : 'Once'
  }
  const raw = task.cronOrRrule?.trim() ?? ''
  const parts = toEngineCron(raw).split(/\s+/)
  if (parts.length !== 6) return `Repeats (${raw})`
  const [sec, min, hour, dom, month, dow] = parts
  const at = sec === '0' ? clock(hour, min) : null
  if (!at || month !== '*') return `Repeats (${raw})`
  if (dom === '*' && dow === '*') return `Every day at ${at}`
  if (dom === '*' && (dow === '1-5' || dow.toUpperCase() === 'MON-FRI')) return `Every weekday at ${at}`
  if (dom === '*' && DAYS[dow.toUpperCase()]) return `Every ${DAYS[dow.toUpperCase()]} at ${at}`
  if (dow === '*' && /^\d+$/.test(dom)) return `Every month on day ${dom} at ${at}`
  return `Repeats (${raw})`
}

export const TASK_STATUS: Record<string, { label: string; tone: 'default' | 'info' | 'success' | 'warning' | 'danger' }> = {
  PENDING: { label: 'Scheduled', tone: 'default' },
  RUNNING: { label: 'Running', tone: 'info' },
  WAITING_APPROVAL: { label: 'Waiting for you', tone: 'warning' },
  DONE: { label: 'Done', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  CANCELLED: { label: 'Cancelled', tone: 'default' },
}

/** How a task is carried out, and the procedure behind that when there is one. */
export interface TaskLink {
  kind: 'follows' | 'review' | 'learning' | 'agent'
  /** One sentence for the task card. */
  text: string
  procedure?: ProcedureSummary
}

export function linkOf(task: TaskDto, procedures: ProcedureSummary[] | undefined): TaskLink {
  const agent = task.agentName ?? 'Its agent'
  const mine = (procedures ?? []).filter(p => p.workflow.sourceTaskId === task.id)
  const bound = task.boundWorkflowId ? mine.find(p => p.workflow.id === task.boundWorkflowId)
    ?? (procedures ?? []).find(p => p.workflow.id === task.boundWorkflowId) : undefined
  if (task.boundWorkflowId) {
    const name = bound ? `"${bound.workflow.name}"` : 'an approved procedure'
    return { kind: 'follows', procedure: bound,
      text: `Follows the procedure ${name}${task.boundVersion ? ` (version ${task.boundVersion})` : ''} step by step, exactly as you approved it.` }
  }
  const pending = mine.find(p => stateOf(p).state === 'review')
  if (pending) {
    return { kind: 'review', procedure: pending,
      text: `${agent} wrote down its steps as the procedure "${pending.workflow.name}". Approve it and this task follows those steps every time; until then ${agent} decides how to do it.` }
  }
  if (task.repetitive) {
    const off = mine[0]
    return { kind: 'learning', procedure: off,
      text: off
        ? `${agent} decides how to do it each run. Its procedure "${off.workflow.name}" is not in use.`
        : `After its next run, ${agent} will write down the steps it took as a procedure for you to review.` }
  }
  return { kind: 'agent', text: `${agent} decides how to do it each time it runs.` }
}
