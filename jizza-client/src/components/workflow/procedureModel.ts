import type { ProcedureSummary } from '@/types'
import { NODE_META, casesOf, fromGraph, type CanvasNode } from './canvasModel'
import { fieldsOf } from './moduleContract'

/** Where a procedure stands, in the words the Procedures page uses. */
export type ProcedureState = 'review' | 'active' | 'off'

export interface StateInfo {
  state: ProcedureState
  badge: string
  tone: 'warning' | 'success' | 'default' | 'danger'
  /** One sentence on what this means for the task it came from. */
  explain: string
}

export function stateOf(p: ProcedureSummary): StateInfo {
  const task = p.sourceTaskTitle ? `"${p.sourceTaskTitle}"` : 'its task'
  const status = p.workflow.status
  if (status === 'PENDING_REVIEW') {
    return { state: 'review', badge: 'Needs your review', tone: 'warning',
      explain: `Nothing runs until you approve it. Until then, ${task} keeps running the usual way.` }
  }
  if (status === 'REJECTED') {
    return { state: 'off', badge: 'Rejected', tone: 'danger',
      explain: `Not used. ${task.charAt(0).toUpperCase() + task.slice(1)} keeps running the usual way.` }
  }
  if (p.disabled) {
    return { state: 'off', badge: 'Turned off', tone: 'default',
      explain: `Not used. ${task.charAt(0).toUpperCase() + task.slice(1)} keeps running the usual way.` }
  }
  if (p.boundVersion != null) {
    return { state: 'active', badge: 'In use', tone: 'success',
      explain: `Every run of ${task} follows these exact steps.` }
  }
  return { state: 'active', badge: 'Approved', tone: 'success',
    explain: 'Approved, but not attached to a scheduled task.' }
}

export interface ProcedureStep {
  id: string
  /** What kind of step, e.g. "Filter". */
  kind: string
  /** The agent's own label for it, e.g. "Only unpaid invoices". */
  label: string
  /** The key setting, e.g. the condition or message; empty when there is none worth showing. */
  detail: string
  /** Which way out of the step before leads here, when it is not the only way, e.g. "If yes". */
  when?: string
}

const EXIT_WORDS: Record<string, string> = {
  true: 'If yes', false: 'If no', approved: 'If approved', rejected: 'If rejected', else: 'Otherwise',
  a: 'At the same time', b: 'At the same time', c: 'At the same time',
}

const clip = (text: string, max = 120) => (text.length > max ? `${text.slice(0, max - 1)}…` : text)

function detailOf(node: CanvasNode): string {
  const c = node.config
  const pick = (...keys: string[]) => {
    for (const key of keys) {
      const value = c[key]
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
    return ''
  }
  switch (node.type) {
    case 'CONNECTOR_TOOL': return pick('toolLabel', 'toolName')
    case 'MCP_TOOL': return pick('tool')
    case 'DEVICE_ACTION': return pick('actionTitle', 'action')
    case 'AGENT_STEP': return pick('instruction', 'instructionExpr')
    case 'HUMAN_INTERVENTION':
    case 'APPROVAL': return pick('prompt')
    case 'FILTER':
    case 'BRANCH': return pick('condition')
    case 'NOTIFY': return pick('message', 'title')
    case 'HTTP_REQUEST': return [pick('method') || 'POST', pick('url')].join(' ')
    case 'WAIT': return pick('duration', 'until', 'untilExpr')
    case 'STORAGE': return [pick('action') || 'get', pick('key')].join(' ')
    case 'FOR_EACH': return pick('items')
    case 'TRANSFORM': return pick('expression')
    case 'SCRIPT': return c.functionName ? `fn.${String(c.functionName)}` : fieldsOf(c, 'outputs').map(f => f.name).filter(Boolean).join(', ')
    case 'SET_VARIABLE': {
      const vars = Array.isArray(c.variables) ? c.variables as Array<{ name?: string }> : []
      return vars.map(v => v.name).filter(Boolean).join(', ')
    }
    case 'SWITCH': return casesOf(c).map(k => k.label || k.id).join(' / ')
    default: return ''
  }
}

/**
 * The procedure's steps in the order they run, start first, for reading top to bottom. A step
 * reached through a branch says which way leads to it.
 */
export function stepsOf(graph: unknown): ProcedureStep[] {
  const g = fromGraph(graph)
  const byId = new Map(g.nodes.map(n => [n.id, n]))
  const start = g.nodes.find(n => n.type === 'TRIGGER')
  const order: string[] = []
  const when = new Map<string, string | undefined>()
  const queue = start ? [start.id] : []
  const seen = new Set(queue)
  while (queue.length) {
    const id = queue.shift()!
    order.push(id)
    const node = byId.get(id)!
    for (const edge of g.edges.filter(e => e.from === id)) {
      if (seen.has(edge.to) || !byId.has(edge.to)) continue
      seen.add(edge.to)
      queue.push(edge.to)
      if (edge.branch == null) continue
      const caseLabel = node.type === 'SWITCH'
        ? casesOf(node.config).find(k => k.id === edge.branch)?.label : undefined
      when.set(edge.to, caseLabel ? `If ${caseLabel}` : EXIT_WORDS[edge.branch] ?? edge.branch)
    }
  }
  // Steps nothing leads to still belong on the list, after the rest.
  for (const n of g.nodes) if (!seen.has(n.id)) order.push(n.id)

  return order.map(id => {
    const node = byId.get(id)!
    const kind = NODE_META[node.type].label
    return {
      id, kind,
      label: node.label && node.label !== kind ? node.label : '',
      detail: clip(detailOf(node)),
      when: when.get(id),
    }
  })
}

export interface Changes {
  added: string[]
  removed: string[]
  changed: string[]
}

/** What changed between two versions of a procedure, step by step, in plain names. */
export function changesBetween(before: unknown, after: unknown): Changes {
  const from = new Map(fromGraph(before).nodes.map(n => [n.id, n]))
  const to = new Map(fromGraph(after).nodes.map(n => [n.id, n]))
  const name = (n: CanvasNode) => {
    const kind = NODE_META[n.type].label
    return n.label && n.label !== kind ? `${kind}: ${n.label}` : kind
  }
  const same = (a: CanvasNode, b: CanvasNode) =>
    a.type === b.type && a.label === b.label && JSON.stringify(a.config) === JSON.stringify(b.config)
  const out: Changes = { added: [], removed: [], changed: [] }
  for (const [id, n] of to) {
    const old = from.get(id)
    if (!old) out.added.push(name(n))
    else if (!same(old, n)) out.changed.push(name(n))
  }
  for (const [id, n] of from) if (!to.has(id)) out.removed.push(name(n))
  return out
}
