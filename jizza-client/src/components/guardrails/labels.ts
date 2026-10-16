import type { ActionType, AutonomyRuleDto, GuardrailLimits, GuardrailMode } from '@/types'

export const MODES: GuardrailMode[] = ['ALLOW', 'ASK', 'JIZZA', 'DENY']

export const MODE_LABELS: Record<GuardrailMode, string> = {
  ALLOW: 'Auto',
  ASK: 'Ask me',
  JIZZA: 'Let Jizza decide',
  DENY: 'Never',
}

export const MODE_HINTS: Record<GuardrailMode, string> = {
  ALLOW: 'The agent goes ahead on its own.',
  ASK: 'The agent waits for your approval.',
  JIZZA: 'Jizza approves only when one of your instructions clearly covers it; otherwise you are asked.',
  DENY: 'The agent never does this, even if you approve it in the moment.',
}

export const MODE_BADGE: Record<GuardrailMode, 'success' | 'warning' | 'info' | 'danger'> = {
  ALLOW: 'success',
  ASK: 'warning',
  JIZZA: 'info',
  DENY: 'danger',
}

export const ACTION_TYPES: ActionType[] = ['READ', 'DRAFT', 'CREATE', 'UPDATE', 'DELETE', 'SEND', 'MONEY']

export const ACTION_LABELS: Record<ActionType, { label: string; hint: string }> = {
  READ: { label: 'Look things up', hint: 'Reading data, searching, checking a balance.' },
  DRAFT: { label: "Keep Jizza's notes", hint: 'Memories, plans and drafts inside Jizza only.' },
  CREATE: { label: 'Create things', hint: 'New records, events or files in your connected apps.' },
  UPDATE: { label: 'Change things', hint: 'Editing what already exists in your connected apps.' },
  DELETE: { label: 'Delete things', hint: 'Removing or cancelling something.' },
  SEND: { label: 'Send messages', hint: 'Emails, messages or posts other people will see.' },
  MONEY: { label: 'Move money', hint: 'Orders, payments and transfers.' },
}

/** The token a rule uses to cover one kind of action. */
export const tokenFor = (type: ActionType) => `*:${type.toLowerCase()}`

const PERIODS: Record<string, string> = { P1D: 'a day', P7D: 'a week', P30D: 'a month' }
export const periodLabel = (per?: string) => PERIODS[per ?? 'P1D'] ?? per ?? 'a day'

/** The conditions on a rule, in plain words. Empty when there are none. */
export function describeLimits(limits?: GuardrailLimits | null): string[] {
  if (!limits) return []
  const out: string[] = []
  if (limits.maxValue != null) out.push(`only up to ${limits.maxValue} ${limits.field ?? 'amount'} per call`)
  if (limits.budget) out.push(`at most ${limits.budget.max} in total ${periodLabel(limits.budget.per)}`)
  if (limits.maxCalls) out.push(`at most ${limits.maxCalls.count} times ${periodLabel(limits.maxCalls.per)}`)
  if (limits.activeHours) {
    const h = limits.activeHours
    const days = h.days && h.days.length > 0 ? ` on ${h.days.join(', ')}` : ''
    out.push(`only ${h.from} to ${h.to}${days} (${h.zone})`)
  }
  if (limits.condition) out.push(`only when ${limits.conditionLabel || limits.condition}`)
  return out
}

/** Who and what a rule covers, in plain words. */
export function describeScope(rule: AutonomyRuleDto, agentName: (id: string) => string): string {
  const who = rule.agentId ? agentName(rule.agentId) : 'Every agent'
  const ref = rule.toolRef ?? '*'
  const typed = ref.startsWith('*:') ? ref.slice(2).toUpperCase() as ActionType : null
  const what = ref === '*' ? 'everything'
    : ref === '*:side-effecting' ? 'anything that changes something outside Jizza'
    : typed && ACTION_LABELS[typed] ? ACTION_LABELS[typed].label.toLowerCase()
    : ref
  const where = rule.nodeId ? ` in one step of a workflow` : rule.workflowId ? ' in one workflow' : ''
  return `${who}: ${what}${where}`
}
