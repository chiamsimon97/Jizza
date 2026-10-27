import {
  Bell, Bot, Brain, Circle, Database, ListTodo, MessageSquare, Plug, Shield, ShieldCheck, Users,
  Workflow, type LucideIcon,
} from 'lucide-react'
import type { ActivitySeverity, RunStatus, TriggerKind } from '@/types'

/** Icons a category may name in the backend catalog; anything else falls back to a dot. */
const ICONS: Record<string, LucideIcon> = {
  Bell, Bot, Brain, Circle, Database, ListTodo, MessageSquare, Plug, Shield, ShieldCheck, Users, Workflow,
}

export const iconFor = (name?: string): LucideIcon => (name && ICONS[name]) || Circle

const CATEGORY_COLORS: Record<string, string> = {
  security: '#f43f5e',
  agents: '#8b5cf6',
  workflows: '#6366f1',
  tasks: '#0ea5e9',
  tools: '#06b6d4',
  guardrails: '#f59e0b',
  memory: '#ec4899',
  chat: '#10b981',
  notifications: '#eab308',
  workspace: '#14b8a6',
  data: '#f97316',
  other: '#94a3b8',
}
const SPARE = ['#a855f7', '#22c55e', '#3b82f6', '#d946ef', '#84cc16', '#fb7185']

/** A stable colour per category, so a section reads the same in the list, filters and timeline. */
export function categoryColor(key: string): string {
  if (CATEGORY_COLORS[key]) return CATEGORY_COLORS[key]
  let h = 0
  for (const c of key) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return SPARE[h % SPARE.length]
}

export const SEVERITY: Record<ActivitySeverity, { label: string; color: string; ring: string }> = {
  INFO: { label: 'Info', color: '#94a3b8', ring: 'ring-gray-300 dark:ring-gray-700' },
  NOTICE: { label: 'Notice', color: '#3b82f6', ring: 'ring-blue-400/60' },
  WARNING: { label: 'Warning', color: '#f59e0b', ring: 'ring-amber-400/70' },
  CRITICAL: { label: 'Critical', color: '#ef4444', ring: 'ring-red-500/70' },
}

export const ACTOR_LABEL: Record<string, string> = { USER: 'You', AGENT: 'Agents', SYSTEM: 'Jizza' }

export const RUN_STATUS: Record<RunStatus, { label: string; color: string; text: string }> = {
  DONE: { label: 'Succeeded', color: '#10b981', text: 'text-emerald-600 dark:text-emerald-400' },
  FAILED: { label: 'Failed', color: '#ef4444', text: 'text-red-600 dark:text-red-400' },
  RUNNING: { label: 'Running', color: '#3b82f6', text: 'text-blue-600 dark:text-blue-400' },
  WAITING: { label: 'Waiting', color: '#a855f7', text: 'text-purple-600 dark:text-purple-400' },
  PAUSED_WAITING_INSTRUCTION: { label: 'Needs you', color: '#f59e0b', text: 'text-amber-600 dark:text-amber-400' },
  CANCELLED: { label: 'Cancelled', color: '#94a3b8', text: 'text-gray-500 dark:text-gray-400' },
}

export const TRIGGER_LABEL: Record<TriggerKind, string> = {
  manual: 'Manual', webhook: 'Webhook', schedule: 'Schedule', agent: 'Agent', event: 'Event', device: 'Device',
  subworkflow: 'Sub-workflow', other: 'Other',
}
