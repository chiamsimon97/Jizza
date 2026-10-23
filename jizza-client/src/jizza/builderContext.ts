import { NODE_META, upstreamOf, type CanvasGraph, type CanvasNode } from '@/components/workflow/canvasModel'
import type { JizzaContext } from './contextStore'
import type { McpServer } from '@/types'
import { STEP_FIELDS } from './commands'

/** Settings that hold a credential. Jizza is told whether one is set, never what it is. */
const SECRET_KEYS = new Set(['password', 'token', 'value', 'clientSecret', 'secret', 'apiKey'])

export function redact(config: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(config)) {
    if (k === 'ui') continue
    if (k === 'auth' && v && typeof v === 'object') {
      out.auth = Object.fromEntries(Object.entries(v as Record<string, unknown>)
        .map(([ak, av]) => [ak, SECRET_KEYS.has(ak) && av ? '(set, hidden)' : av]))
    } else out[k] = v
  }
  return out
}

const brief = (n: CanvasNode) => ({ id: n.id, type: n.type, kind: NODE_META[n.type].label, label: n.label })

/** The Builder, as Jizza is told about it: the workflow's name and its steps with their settings. */
export function builderContext(name: string, graph: CanvasGraph | null, problems: string[]): JizzaContext {
  return {
    kind: 'builder', label: 'Builder', detail: name || 'New workflow', screen: 'workflow_builder',
    data: {
      kind: 'builder', workflowName: name || 'New workflow',
      ...(graph ? {
        steps: graph.nodes.map(n => ({ ...brief(n), config: redact(n.config) })),
        edges: graph.edges.map(e => ({ from: e.from, to: e.to, ...(e.branch ? { exit: e.branch } : {}) })),
      } : {}),
      ...(problems.length ? { problems: problems.slice(0, 20) } : {}),
    },
  }
}

/** One step's settings, as Jizza is told about them when it is configuring that step. */
export function stepContext(node: CanvasNode, graph: CanvasGraph, workflowName: string | undefined, options: {
  agents?: Array<{ id: string; name: string; domain?: string; enabled: boolean }>
  tools?: Array<{ id: string; label: string }>
  mcpServers?: McpServer[]
  workflows?: Array<{ id: string; name: string }>
  missing: string[]
}): JizzaContext {
  const kind = NODE_META[node.type].label
  const upstream = upstreamOf(graph, node.id).filter(n => n.type !== 'TRIGGER')
  const vars = [...new Set(upstream.filter(n => n.type === 'SET_VARIABLE')
    .flatMap(n => (Array.isArray(n.config.variables) ? n.config.variables as Array<{ name?: unknown }> : []).map(v => String(v?.name ?? '').trim()))
    .filter(Boolean))]
  return {
    kind: 'step', label: `${kind} step`, detail: node.label, screen: 'workflow_step', stepType: node.type,
    data: {
      kind: 'step', workflowName: workflowName || 'New workflow',
      step: { ...brief(node), config: redact(node.config) },
      fields: STEP_FIELDS[node.type] ?? '',
      earlierSteps: upstream.map(brief),
      ...(vars.length ? { vars } : {}),
      ...(options.missing.length ? { missing: options.missing } : {}),
      ...(node.type === 'AGENT_STEP' && options.agents ? { agents: options.agents.filter(a => a.enabled).slice(0, 40).map(a => ({ id: a.id, name: a.name, domain: a.domain })) } : {}),
      ...(node.type === 'CONNECTOR_TOOL' && options.tools ? { tools: options.tools.slice(0, 80) } : {}),
      ...(node.type === 'MCP_TOOL' && options.mcpServers ? {
        mcpServers: options.mcpServers.filter(s => s.enabled).slice(0, 20).map(s => ({
          id: s.id, name: s.name,
          tools: s.tools.filter(t => t.enabled).slice(0, 60).map(t => ({ name: t.name, description: t.description?.slice(0, 200), inputSchema: t.inputSchema })),
        })),
      } : {}),
      ...((node.type === 'SUB_WORKFLOW' || node.type === 'FOR_EACH') && options.workflows ? { workflows: options.workflows.slice(0, 40).map(w => ({ id: w.id, name: w.name })) } : {}),
    },
  }
}
