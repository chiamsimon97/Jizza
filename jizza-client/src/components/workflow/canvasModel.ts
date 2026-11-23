/**
 * The canvas builder's model and its translation to and from the engine's workflow graph.
 *
 * Ported from the original Jizza builder (frontend/src/domain/builder.ts). Two changes: node
 * settings are real fields instead of free "key: value" text, and connections can be set
 * explicitly, including a branch's yes and no exits.
 */

import { contractIssues } from './moduleContract'
import { readGroups, writeGroups, type StepGroup } from './groupModel'

export type NodeType = 'TRIGGER' | 'AGENT_STEP' | 'HUMAN_INTERVENTION' | 'CONNECTOR_TOOL' | 'TRANSFORM' | 'BRANCH' | 'END'
  | 'PARALLEL' | 'JOIN' | 'WAIT' | 'SUB_WORKFLOW' | 'FOR_EACH'
  | 'APPROVAL' | 'HTTP_REQUEST' | 'FILTER' | 'SWITCH' | 'SET_VARIABLE' | 'STORAGE' | 'NOTIFY'
  | 'MCP_TOOL' | 'SCRIPT' | 'DEVICE_ACTION'

/**
 * Which exit of a node an edge leaves from: "true"/"false" on a branch, a lane letter on a
 * parallel step (every lane runs), "approved"/"rejected" on an approval, a case id or "else" on
 * a paths step, null on a node with a single exit. A paths step's exits come from its config,
 * so this is any string; {@link exitsOf} says which ones a node has.
 */
export type ExitKey = string | null

/** A parallel step's lanes. Two must be connected; the third is there when needed. */
export const LANES = ['a', 'b', 'c'] as const

/** One of a paths step's cases. The id is the exit's key and stays put when the label changes. */
export interface SwitchCase { id: string; label: string; condition: string }

/** The most cases a paths step can have, besides "else" (the engine allows the same). */
export const MAX_CASES = 10

export const NODE_W = 200
export const NODE_H = 92
const COL_W = 250
const ROW_H = 130

export interface CanvasNode {
  id: string
  type: NodeType
  label: string
  /** The engine's node config, minus the canvas position. */
  config: Record<string, unknown>
  x: number
  y: number
}

/** {@code branch} names the exit the edge leaves from; see {@link ExitKey}. */
export interface CanvasEdge {
  from: string
  to: string
  branch: ExitKey
}

export interface CanvasGraph {
  nodes: CanvasNode[]
  edges: CanvasEdge[]
  /** Step groups drawn on the canvas; the engine ignores them. See groupModel. */
  groups?: StepGroup[]
}

/** A palette item may be a plain node type or a ready-configured version of one. */
export interface PaletteEntry {
  type: NodeType
  label: string
  group: string
  config?: Record<string, unknown>
}

export const NODE_META: Record<NodeType, { label: string; desc: string; tone: string }> = {
  TRIGGER: { label: 'Start', desc: 'Where the workflow begins.', tone: 'bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900' },
  AGENT_STEP: { label: 'Ask an agent', desc: 'A specialist does a task.', tone: 'bg-brand-500 text-white' },
  HUMAN_INTERVENTION: { label: 'Ask me', desc: 'Pauses and asks you.', tone: 'bg-amber-500 text-white' },
  CONNECTOR_TOOL: { label: 'Service action', desc: 'Calls a tool on a connected service.', tone: 'bg-sky-600 text-white' },
  TRANSFORM: { label: 'Transform', desc: 'Reshapes data between steps.', tone: 'bg-violet-600 text-white' },
  BRANCH: { label: 'Branch', desc: 'Splits on a yes/no condition.', tone: 'bg-emerald-600 text-white' },
  END: { label: 'End', desc: 'Finishes the run.', tone: 'bg-gray-400 text-white dark:bg-gray-600' },
  PARALLEL: { label: 'Parallel', desc: 'Runs several paths at once.', tone: 'bg-teal-600 text-white' },
  JOIN: { label: 'Join', desc: 'Waits for every path (an hour at most, unless you change it), then carries on.', tone: 'bg-teal-700 text-white' },
  WAIT: { label: 'Wait', desc: 'Pauses for a while or until a time.', tone: 'bg-orange-500 text-white' },
  SUB_WORKFLOW: { label: 'Run a module', desc: 'Runs another workflow as one step: give it inputs, map its outputs.', tone: 'bg-indigo-600 text-white' },
  FOR_EACH: { label: 'For each', desc: 'Runs a workflow once per item in a list.', tone: 'bg-fuchsia-600 text-white' },
  APPROVAL: { label: 'Approval', desc: 'Waits for you to approve or reject.', tone: 'bg-amber-600 text-white' },
  HTTP_REQUEST: { label: 'Send webhook', desc: 'Calls any URL or API.', tone: 'bg-cyan-600 text-white' },
  FILTER: { label: 'Filter', desc: 'Carries on only when a condition is true.', tone: 'bg-lime-600 text-white' },
  SWITCH: { label: 'Paths', desc: 'Picks one of several paths by condition.', tone: 'bg-green-700 text-white' },
  SCRIPT: { label: 'Script', desc: 'Runs one of your functions: map its inputs; later steps map its result.', tone: 'bg-violet-800 text-white' },
  SET_VARIABLE: { label: 'Set variable', desc: 'Keeps values for later steps.', tone: 'bg-purple-600 text-white' },
  STORAGE: { label: 'Storage', desc: 'Remembers values between runs.', tone: 'bg-slate-600 text-white' },
  NOTIFY: { label: 'Notify me', desc: 'Sends you a notification.', tone: 'bg-rose-500 text-white' },
  MCP_TOOL: { label: 'MCP tool', desc: 'Calls a tool on an MCP server you added.', tone: 'bg-violet-700 text-white' },
  DEVICE_ACTION: { label: 'Device action', desc: 'Runs an action on your computer or phone with the Jizza Agent app.', tone: 'bg-stone-700 text-white' },
}

const entries = (group: string, ...types: NodeType[]): PaletteEntry[] => types.map(type => ({ type, label: NODE_META[type].label, group }))
const web = (label: string, action: string, version = 1): PaletteEntry => ({
  type: 'DEVICE_ACTION', label, group: 'Web Automation', config: { preset: 'web', action, version, target: 'sameBrowser' },
})

/** Web steps are deliberately ordered to match the browser action contract. */
export const WEB_PALETTE: PaletteEntry[] = [
  web('Open URL', 'browser.open', 2),
  web('Click', 'browser.click', 2),
  web('Point at it', 'browser.hover'),
  web('Type text', 'browser.setText'),
  web('Read text', 'browser.getText'),
  web('Set checkbox', 'browser.setCheckbox'),
  web('Get checkbox state', 'browser.getCheckbox'),
  web('Make sure it is on, off or open', 'browser.ensureState'),
  web('Always close this if it shows up', 'browser.alwaysClose'),
  web('Select item', 'browser.selectItem'),
  web('Read a table', 'browser.extractTable'),
  web('Select radio button', 'browser.selectRadio'),
  web('Get radio button state', 'browser.getRadio'),
  web('Scroll', 'browser.scroll'),
  web('Send hotkeys', 'browser.sendKeys'),
  web('Type text key by key', 'browser.typeInto'),
  web('Check it is on the page', 'browser.exists'),
  web('Switch to', 'browser.switchTo'),
  web('Refresh page', 'browser.refresh'),
  web('Get element selectors', 'browser.getSelectors'),
  web('Get web page details', 'browser.pageInfo'),
  web('Go forward a page', 'browser.forward'),
  web('Go back a page', 'browser.back'),
  web('Close browser', 'browser.close'),
  web('Wait', 'browser.wait'),
  web('Screenshot', 'browser.screenshot'),
  web('Answer browser pop-ups', 'browser.handleDialog'),
  web('Drag and drop', 'browser.dragDrop'),
  web('Fill several fields', 'browser.fillForm'),
  {
    ...web('Fill a whole form', 'browser.fillLayer'),
    config: {
      preset: 'web', action: 'browser.fillLayer', version: 1, target: 'sameBrowser',
      inputs: { groups: [{ id: 'g1', title: 'Details' }], fields: [], buttons: [] },
    },
  },
  web('Type a date', 'browser.setDate'),
  web('Pick a date in a calendar', 'browser.pickDate'),
  web('Type and pick a suggestion', 'browser.chooseOption'),
  web('Tick several boxes', 'browser.checkItems'),
  web('Choose from a list', 'browser.clickItem'),
  web('Pick from a dropdown', 'browser.selectOption'),
  web('Read a list', 'browser.listItems'),
  web('Ask me', 'browser.ask'),
  web('Hand over to me', 'browser.handOff'),
]

/** Browser actions whose contract requires a concrete element locator. */
export const WEB_ELEMENT_ACTIONS = new Set([
  'browser.click', 'browser.hover', 'browser.setText', 'browser.getText', 'browser.setCheckbox',
  'browser.getCheckbox', 'browser.ensureState', 'browser.selectItem', 'browser.extractTable', 'browser.typeInto', 'browser.exists',
  'browser.dragDrop', 'browser.setDate', 'browser.checkItems', 'browser.clickItem', 'browser.listItems',
  'browser.pickDate', 'browser.chooseOption', 'browser.selectOption', 'browser.listOptions',
])

/** Actions that can use an element, including those where it is an optional targeting mode. */
export const WEB_ELEMENT_INPUT_ACTIONS = new Set([
  ...WEB_ELEMENT_ACTIONS,
  'browser.selectRadio', 'browser.getRadio', 'browser.scroll', 'browser.sendKeys', 'browser.switchTo',
  'browser.getSelectors', 'browser.wait', 'browser.screenshot', 'browser.alwaysClose',
])

/** The palette's steps, grouped by what they do. Start is added once, automatically. */
export const PALETTE_GROUPS: Array<{ title: string; icon?: 'globe'; entries: PaletteEntry[] }> = [
  { title: 'Agents and people', entries: entries('Agents and people', 'AGENT_STEP', 'HUMAN_INTERVENTION', 'APPROVAL', 'NOTIFY') },
  { title: 'Apps and webhooks', entries: entries('Apps and webhooks', 'CONNECTOR_TOOL', 'MCP_TOOL', 'HTTP_REQUEST', 'DEVICE_ACTION') },
  { title: 'Web Automation', icon: 'globe', entries: WEB_PALETTE },
  { title: 'Data', entries: entries('Data', 'TRANSFORM', 'SCRIPT', 'SET_VARIABLE', 'STORAGE') },
  { title: 'Conditions and paths', entries: entries('Conditions and paths', 'BRANCH', 'SWITCH', 'FILTER', 'PARALLEL', 'JOIN') },
  { title: 'Flow control', entries: entries('Flow control', 'WAIT', 'FOR_EACH', 'SUB_WORKFLOW', 'END') },
]

/** What can be dragged from the palette, in group order. */
export const PALETTE: PaletteEntry[] = PALETTE_GROUPS.flatMap(g => g.entries)

/** The title of the group a step type is listed under. */
export const groupOf = (type: NodeType): string => PALETTE.find(e => e.type === type && !e.config)?.group ?? ''

const toExit = (v: unknown): ExitKey => (v === true ? 'true' : v === false ? 'false' : typeof v === 'string' && v ? v : null)

const NODE_TYPES = new Set<string>(Object.keys(NODE_META))

let counter = 0
export function newId(type: NodeType): string {
  counter += 1
  return `${type.toLowerCase()}_${Date.now().toString(36)}${counter}`
}

/**
 * A new step. Paths and set-variable steps start with one empty row, so there is something to fill
 * in; a wait starts at an hour and a webhook as a JSON POST, which is what their settings show.
 */
export function newNode(typeOrEntry: NodeType | PaletteEntry, x: number, y: number): CanvasNode {
  const entry = typeof typeOrEntry === 'string' ? undefined : typeOrEntry
  const type = entry?.type ?? typeOrEntry as NodeType
  const config: Record<string, unknown> = type === 'SWITCH' ? { cases: [{ id: 'case1', label: 'Path 1', condition: '' }] }
    : type === 'SET_VARIABLE' ? { variables: [{ name: '', expr: '' }] }
    : type === 'WAIT' ? { duration: 'PT1H' }
    : type === 'HTTP_REQUEST' ? { method: 'POST', bodyType: 'json' } : {}
  return {
    id: type === 'TRIGGER' ? 'start' : newId(type), type, label: entry?.label ?? NODE_META[type].label,
    config: { ...config, ...entry?.config }, x, y,
  }
}

export function emptyGraph(): CanvasGraph {
  return { nodes: [newNode('TRIGGER', 30, 150)], edges: [] }
}

/** Reads an engine graph. Saved canvas positions are used; anything else is laid out left to right. */
export function fromGraph(graph: unknown): CanvasGraph {
  const g = graph as { nodes?: Array<Record<string, unknown>>; edges?: Array<Record<string, unknown>>; groups?: unknown } | null
  if (!g || !Array.isArray(g.nodes) || g.nodes.length === 0) return emptyGraph()

  const edges: CanvasEdge[] = (Array.isArray(g.edges) ? g.edges : []).map(e => ({
    from: String(e.from), to: String(e.to), branch: toExit(e.branch),
  }))
  const layout = autoLayout(g.nodes.map(n => String(n.id)), edges, g.nodes.find(n => n.type === 'TRIGGER')?.id as string | undefined)

  const nodes: CanvasNode[] = g.nodes.map(n => {
    const { ui, ...config } = (n.config ?? {}) as Record<string, unknown> & { ui?: { x?: number; y?: number } }
    const type = (NODE_TYPES.has(String(n.type)) ? n.type : 'TRANSFORM') as NodeType
    const at = layout.get(String(n.id)) ?? { x: 30, y: 150 }
    return {
      id: String(n.id), type, label: typeof n.label === 'string' && n.label ? n.label : NODE_META[type].label,
      config, x: typeof ui?.x === 'number' ? ui.x : at.x, y: typeof ui?.y === 'number' ? ui.y : at.y,
    }
  })
  const groups = readGroups(g.groups, nodes)
  return groups.length ? { nodes, edges, groups } : { nodes, edges }
}

/** Columns by the longest path from the start (so every edge points right), rows in order. */
function autoLayout(ids: string[], edges: CanvasEdge[], start?: string): Map<string, { x: number; y: number }> {
  const depth = new Map<string, number>()
  if (start) depth.set(start, 0)
  // The graph is acyclic, so relaxing every edge |nodes| times settles the longest paths.
  for (let i = 0; i < ids.length; i++) {
    let changed = false
    for (const e of edges) {
      const d = depth.get(e.from)
      if (d !== undefined && (depth.get(e.to) ?? -1) < d + 1) { depth.set(e.to, d + 1); changed = true }
    }
    if (!changed) break
  }
  let orphan = Math.max(0, ...depth.values()) + 1
  for (const id of ids) if (!depth.has(id)) depth.set(id, orphan++)
  const rows = new Map<number, number>()
  const out = new Map<string, { x: number; y: number }>()
  for (const id of ids) {
    const col = depth.get(id)!
    const row = rows.get(col) ?? 0
    rows.set(col, row + 1)
    out.set(id, { x: 30 + col * COL_W, y: 40 + row * ROW_H })
  }
  return out
}

/** Re-arranges every node left to right ("Tidy up"). */
export function tidy(graph: CanvasGraph): CanvasGraph {
  const at = autoLayout(graph.nodes.map(n => n.id), graph.edges, graph.nodes.find(n => n.type === 'TRIGGER')?.id)
  return { ...graph, nodes: graph.nodes.map(n => ({ ...n, ...(at.get(n.id) ?? {}) })) }
}

/** Every step reachable from {@code id}, not counting itself. */
export function descendantsOf(graph: CanvasGraph, id: string): string[] {
  const seen = new Set<string>()
  const stack = graph.edges.filter(e => e.from === id).map(e => e.to)
  while (stack.length) {
    const next = stack.pop()!
    if (next === id || seen.has(next)) continue
    seen.add(next)
    for (const e of graph.edges) if (e.from === next) stack.push(e.to)
  }
  return [...seen]
}

/**
 * Puts a step's edited copy back into the graph: its settings, and the connections leaving it.
 * Everything else comes from {@code graph}, so changes made on the canvas meanwhile are kept.
 */
export function applyStepEdit(graph: CanvasGraph, edited: CanvasGraph, id: string): CanvasGraph {
  const node = edited.nodes.find(n => n.id === id)
  if (!node || !graph.nodes.some(n => n.id === id)) return graph
  const ids = new Set(graph.nodes.map(n => n.id))
  return {
    ...graph,
    nodes: graph.nodes.map(n => (n.id === id ? { ...node, x: n.x, y: n.y } : n)),
    edges: [...graph.edges.filter(e => e.from !== id), ...edited.edges.filter(e => e.from === id && ids.has(e.to))],
  }
}

/** The nearest spot at or below (x, y) where a new step would not sit on top of another. */
export function freeSpot(graph: CanvasGraph, x: number, y: number): { x: number; y: number } {
  const clashes = (ty: number) => graph.nodes.some(n => Math.abs(n.x - x) < NODE_W && Math.abs(n.y - ty) < NODE_H)
  for (let i = 0; i < 50; i++) {
    for (const ty of i === 0 ? [y] : [y + i * ROW_H, y - i * ROW_H]) if (ty >= 0 && !clashes(ty)) return { x, y: ty }
  }
  return { x, y }
}

/** Puts a new step on an existing connection: from → new → to, keeping the from side's exit. */
export function insertOnEdge(graph: CanvasGraph, edge: CanvasEdge, type: NodeType | PaletteEntry): { graph: CanvasGraph; id: string } {
  const from = graph.nodes.find(n => n.id === edge.from)
  const to = graph.nodes.find(n => n.id === edge.to)
  const at = freeSpot(graph, from && to ? Math.round((from.x + to.x) / 2) : 0, from && to ? Math.round((from.y + to.y) / 2) + ROW_H : 0)
  const node = newNode(type, at.x, at.y)
  const edges = graph.edges.filter(e => !(e.from === edge.from && e.to === edge.to && e.branch === edge.branch))
  edges.push({ from: edge.from, to: node.id, branch: edge.branch })
  // The onward path leaves from the first exit: a branch's "yes", a parallel step's first lane,
  // an approval's "approved", a paths step's first case.
  if (node.type !== 'END') edges.push({ from: node.id, to: edge.to, branch: exitsOf(node)[0] })
  return { graph: { ...graph, nodes: [...graph.nodes, node], edges }, id: node.id }
}

/** Adds a step on a given exit of a node, replacing whatever that exit pointed at before. */
export function addAfter(graph: CanvasGraph, from: string, branch: ExitKey, type: NodeType | PaletteEntry): { graph: CanvasGraph; id: string } {
  const source = graph.nodes.find(n => n.id === from)
  const siblings = graph.edges.filter(e => e.from === from).length
  // One row down per exit; the first of several goes up a row when something is already connected.
  const lane = Math.max(0, source ? exitsOf(source).indexOf(branch) : 0)
  const offset = lane === 0 && branch !== null && siblings ? -ROW_H : lane * ROW_H
  const at = freeSpot(graph, (source?.x ?? 0) + COL_W, (source?.y ?? 0) + offset)
  const node = newNode(type, at.x, at.y)
  const withNode = { ...graph, nodes: [...graph.nodes, node] }
  return { graph: setNext(withNode, from, branch, node.id), id: node.id }
}

/**
 * Puts a chain of steps (such as a recording) after a node, under new ids. Whatever followed that
 * exit now follows the chain, so the chain is spliced in, not added beside it.
 */
export function insertChain(graph: CanvasGraph, from: string, branch: ExitKey, chain: { nodes: CanvasNode[]; edges: CanvasEdge[] }): { graph: CanvasGraph; ids: string[] } {
  if (chain.nodes.length === 0) return { graph, ids: [] }
  const ids = new Map(chain.nodes.map(n => [n.id, newId(n.type)]))
  const source = graph.nodes.find(n => n.id === from)
  const heads = chain.nodes.filter(n => !chain.edges.some(e => e.to === n.id))
  const tails = chain.nodes.filter(n => !chain.edges.some(e => e.from === n.id))
  const at = freeSpot(graph, (source?.x ?? 0) + COL_W, source?.y ?? 0)
  const first = chain.nodes[0]
  const nodes = chain.nodes.map(n => ({ ...n, id: ids.get(n.id)!, config: structuredClone(n.config), x: at.x + (n.x - first.x), y: at.y + (n.y - first.y) }))
  const edges = chain.edges.map(e => ({ ...e, from: ids.get(e.from)!, to: ids.get(e.to)! }))
  const previous = graph.edges.find(e => e.from === from && e.branch === branch)?.to
  let next: CanvasGraph = { ...graph, nodes: [...graph.nodes, ...nodes], edges: [...graph.edges, ...edges] }
  next = setNext(next, from, branch, ids.get(heads[0].id)!)
  if (previous && tails.length === 1) next = setNext(next, ids.get(tails[0].id)!, null, previous)
  return { graph: next, ids: nodes.map(n => n.id) }
}

/**
 * Copies nodes (with the connections between them) to new ids, offset so the copy is visible.
 * Start is never copied: a workflow has exactly one.
 */
export function cloneNodes(graph: CanvasGraph, ids: string[], source: CanvasGraph = graph, offset = 40): { graph: CanvasGraph; ids: string[] } {
  const picked = source.nodes.filter(n => ids.includes(n.id) && n.type !== 'TRIGGER')
  const map = new Map(picked.map(n => [n.id, newId(n.type)]))
  const nodes = picked.map(n => ({ ...n, id: map.get(n.id)!, config: structuredClone(n.config), x: n.x + offset, y: n.y + offset }))
  const edges = source.edges.filter(e => map.has(e.from) && map.has(e.to)).map(e => ({ ...e, from: map.get(e.from)!, to: map.get(e.to)! }))
  return { graph: { ...graph, nodes: [...graph.nodes, ...nodes], edges: [...graph.edges, ...edges] }, ids: nodes.map(n => n.id) }
}

/** Steps further up the flow, whose results a step can read as nodes.<id>. */
export function upstreamOf(graph: CanvasGraph, id: string): CanvasNode[] {
  return graph.nodes.filter(n => n.id !== id && n.type !== 'END' && reaches(graph, n.id, id))
}

/** Writes the engine graph, keeping each node's canvas position in its config. */
export function toGraph(graph: CanvasGraph) {
  return {
    nodes: graph.nodes.map(n => ({ id: n.id, type: n.type, label: n.label, config: { ...n.config, ui: { x: n.x, y: n.y } } })),
    edges: graph.edges.map(e => ({ from: e.from, to: e.to, branch: e.branch })),
    ...writeGroups(graph),
  }
}

/**
 * The exits a node has: one, a yes and a no for a branch, lanes for a parallel step, approved and
 * rejected for an approval, or one per case plus "else" for a paths step. End has none.
 */
export function exitsOf(node: Pick<CanvasNode, 'type' | 'config'>): ExitKey[] {
  if (node.type === 'END') return []
  if (isCheckStep(node)) return [null, 'false']
  if (isLayerStep(node)) return layerButtonsOf(node).map(b => b.id)
  if (node.type === 'BRANCH') return ['true', 'false']
  if (node.type === 'PARALLEL') return [...LANES]
  if (node.type === 'APPROVAL') return ['approved', 'rejected']
  if (node.type === 'SWITCH') return [...casesOf(node.config).map(c => c.id), 'else']
  return [null]
}

/**
 * Device steps that answer yes or no ("is this on the page?"). Their usual exit is taken on yes
 * and an optional "no" exit on no; with nothing wired to "no" they carry on either way.
 */
export const CHECK_ACTIONS: readonly string[] = ['browser.exists', 'browser.getCheckbox', 'browser.getRadio']

export function isCheckStep(node: Pick<CanvasNode, 'type' | 'config'> | undefined): boolean {
  return !!node && node.type === 'DEVICE_ACTION' && CHECK_ACTIONS.includes(String(node.config.action ?? ''))
}

/** A device layer: a whole area of a page filled in one step, then one of its buttons clicked. */
export const LAYER_ACTION = 'browser.fillLayer'

export function isLayerStep(node: Pick<CanvasNode, 'type' | 'config'> | undefined): boolean {
  return !!node && node.type === 'DEVICE_ACTION' && node.config.action === LAYER_ACTION
}

export interface LayerButton { id: string; name: string; kind?: 'submit' | 'cancel' | 'other'; element?: unknown }

/** A device layer's buttons, in order: each is a way out of the step, by its id. */
export function layerButtonsOf(node: Pick<CanvasNode, 'type' | 'config'> | undefined): LayerButton[] {
  if (!isLayerStep(node)) return []
  const inputs = node!.config.inputs as Record<string, unknown> | undefined
  const buttons = Array.isArray(inputs?.buttons) ? inputs!.buttons as Array<Record<string, unknown>> : []
  const seen = new Set<string>()
  const out: LayerButton[] = []
  for (const b of buttons) {
    const id = typeof b?.id === 'string' ? b.id.trim() : ''
    if (!id || seen.has(id)) continue
    seen.add(id)
    out.push({ ...b, id, name: typeof b.name === 'string' ? b.name : '' } as LayerButton)
  }
  return out
}

/** An id for a new layer button or field: one past the highest, so a removed one's id is never reused. */
export function nextLayerId(prefix: string, ids: string[]): string {
  const top = Math.max(0, ...ids.map(id => Number(new RegExp(`^${prefix}(\\d+)$`).exec(id)?.[1] ?? 0)))
  return `${prefix}${top + 1}`
}

/**
 * A layer button is going away: the steps on its path either move to another button's path or
 * are left on the canvas, disconnected, so nothing is deleted without the person seeing it.
 */
export function dropLayerButton(graph: CanvasGraph, stepId: string, buttonId: string, moveTo?: string): CanvasGraph {
  const edges: CanvasEdge[] = []
  for (const e of graph.edges) {
    if (e.from !== stepId || e.branch !== buttonId) { edges.push(e); continue }
    if (moveTo && !graph.edges.some(o => o.from === stepId && o.branch === moveTo)) edges.push({ ...e, branch: moveTo })
  }
  return { ...graph, edges }
}

/** The exits a step needs wired: every exit except a check step's optional "no". */
export function mainExitsOf(node: Pick<CanvasNode, 'type' | 'config'>): ExitKey[] {
  return isCheckStep(node) ? [null] : exitsOf(node)
}

/** A paths step's cases, skipping anything that isn't one. */
export function casesOf(config: Record<string, unknown>): SwitchCase[] {
  const cases = Array.isArray(config.cases) ? config.cases as Array<Record<string, unknown>> : []
  return cases.filter(c => c && typeof c.id === 'string' && c.id).map(c => ({
    id: String(c.id), label: typeof c.label === 'string' ? c.label : '', condition: typeof c.condition === 'string' ? c.condition : '',
  }))
}

/** An id for a new case: one past the highest "caseN", so a removed case's id is never reused. */
export function nextCaseId(cases: SwitchCase[]): string {
  const top = Math.max(0, ...cases.map(c => Number(/^case(\d+)$/.exec(c.id)?.[1] ?? 0)))
  return `case${top + 1}`
}

/** The buttons of a "one of these buttons" web step (browser.clickOneOf), by name. */
export function groupButtonsOf(node: Pick<CanvasNode, 'type' | 'config'> | undefined): string[] {
  if (!node || node.type !== 'DEVICE_ACTION' || node.config.action !== 'browser.clickOneOf') return []
  const inputs = node.config.inputs as Record<string, unknown> | undefined
  const options = Array.isArray(inputs?.options) ? inputs!.options as Array<{ name?: unknown }> : []
  return [...new Set(options.map(o => (typeof o?.name === 'string' ? o.name.trim() : '')).filter(Boolean))]
}

/** The condition of a paths case that holds when {@code button} was the one the step clicked. */
export function clickedCondition(stepId: string, button: string): string {
  return `nodes.${stepId}.clicked == "${button.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
}

/** The paths step that branches on which button {@code stepId} clicked, if one follows it. */
export function buttonPathsAfter(graph: CanvasGraph, stepId: string): CanvasNode | undefined {
  return graph.nodes.find(n => n.type === 'SWITCH' && n.config.byButton === stepId
    && graph.edges.some(e => e.from === stepId && e.to === n.id))
}

/**
 * Branches the flow on which of a group's buttons was clicked: a paths step goes right after the
 * step, with one path per button and "otherwise" for the rest. Every path starts out connected to
 * what followed the step, so the workflow runs as before until steps are put on a path; a button
 * that needs nothing of its own just keeps that connection (or its path is removed).
 */
export function branchByButton(graph: CanvasGraph, stepId: string, buttons?: string[]): { graph: CanvasGraph; id: string } | null {
  const step = graph.nodes.find(n => n.id === stepId)
  const names = (buttons ?? groupButtonsOf(step)).slice(0, MAX_CASES)
  if (!step || names.length === 0) return null
  const existing = buttonPathsAfter(graph, stepId)
  if (existing) return { graph, id: existing.id }
  const list = step.config.list as { title?: unknown } | undefined
  const title = typeof list?.title === 'string' && list.title.trim() ? list.title.trim() : 'button'
  const cases: SwitchCase[] = names.map((name, i) => ({ id: `case${i + 1}`, label: name, condition: clickedCondition(stepId, name) }))
  const at = freeSpot(graph, step.x + COL_W, step.y)
  const node: CanvasNode = {
    ...newNode('SWITCH', at.x, at.y),
    label: `Which ${title} was clicked`,
    config: { cases, byButton: stepId },
  }
  const onward = graph.edges.filter(e => e.from === stepId).map(e => e.to)
  const edges = graph.edges.filter(e => e.from !== stepId)
  edges.push({ from: stepId, to: node.id, branch: null })
  for (const to of onward) for (const exit of exitsOf(node)) edges.push({ from: node.id, to, branch: exit })
  return { graph: { ...graph, nodes: [...graph.nodes, node], edges }, id: node.id }
}

/** Points one exit of a node at another node, or clears it. */
export function setNext(graph: CanvasGraph, from: string, branch: ExitKey, to: string | null): CanvasGraph {
  const edges = graph.edges.filter(e => !(e.from === from && e.branch === branch))
  return { ...graph, edges: to ? [...edges, { from, to, branch }] : edges }
}

/**
 * Adds a node and connects it to the flow: from the selected node if it has a free exit,
 * otherwise from the nearest node with one.
 */
export function addNode(graph: CanvasGraph, type: NodeType | PaletteEntry, x: number, y: number, after?: string | null): { graph: CanvasGraph; id: string } {
  const node = newNode(type, x, y)
  const freeExit = (n: CanvasNode) => (n.id === after ? exitsOf(n) : mainExitsOf(n)).find(b => !graph.edges.some(e => e.from === n.id && e.branch === b))
  const candidates = graph.nodes.filter(n => freeExit(n) !== undefined)
  const chosen = candidates.find(n => n.id === after)
    ?? candidates.sort((a, b) => dist(a, x, y) - dist(b, x, y))[0]
  const edges = chosen ? [...graph.edges, { from: chosen.id, to: node.id, branch: freeExit(chosen)! }] : graph.edges
  return { graph: { ...graph, nodes: [...graph.nodes, node], edges }, id: node.id }
}

const dist = (n: CanvasNode, x: number, y: number) => (n.x - x) ** 2 + (n.y - y) ** 2

/** Whether {@code to} can be reached from {@code from}; connecting back to it would make a loop. */
export function reaches(graph: CanvasGraph, from: string, to: string): boolean {
  const seen = new Set<string>()
  const stack = [from]
  while (stack.length) {
    const id = stack.pop()!
    if (id === to) return true
    if (seen.has(id)) continue
    seen.add(id)
    for (const e of graph.edges) if (e.from === id) stack.push(e.to)
  }
  return false
}

/** Nodes an exit of {@code from} may point at: anything but Start, itself, or what leads back to it. */
export function targetsFor(graph: CanvasGraph, from: string): CanvasNode[] {
  return graph.nodes.filter(n => n.type !== 'TRIGGER' && n.id !== from && !reaches(graph, n.id, from))
}

export function removeNode(graph: CanvasGraph, id: string): CanvasGraph {
  const groups = graph.groups?.map(g => ({ ...g, nodes: g.nodes.filter(n => n !== id) })).filter(g => g.nodes.length > 0)
  return {
    ...graph, nodes: graph.nodes.filter(n => n.id !== id), edges: graph.edges.filter(e => e.from !== id && e.to !== id),
    ...(graph.groups ? { groups } : {}),
  }
}

export function updateNode(graph: CanvasGraph, id: string, patch: Partial<CanvasNode>): CanvasGraph {
  return { ...graph, nodes: graph.nodes.map(n => (n.id === id ? { ...n, ...patch } : n)) }
}

const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '')

/** What a later step reads a set variable as: vars.<name>. */
const VAR_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/

/** A webhook's URL: absolute, or a {{ template }} that gives one. */
const URL_OK = (url: string) => /^https?:\/\//.test(url) || url.startsWith('{{')

/** The ISO-8601 durations the engine reads, such as PT30M, PT2H or P1D. */
export const ISO_DURATION = /^P(?!$)(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+(\.\d+)?S)?)?$/

/** Writes a number and unit as an ISO-8601 duration, and reads one back; null when it isn't one of these. */
export function toDuration(amount: number, unit: 'minutes' | 'hours' | 'days'): string {
  return unit === 'days' ? `P${amount}D` : `PT${amount}${unit === 'hours' ? 'H' : 'M'}`
}

export function fromDuration(iso: string): { amount: number; unit: 'minutes' | 'hours' | 'days' } | null {
  const m = /^P(?:(\d+)D|T(\d+)([HM]))$/.exec(iso.trim())
  if (!m) return null
  if (m[1]) return { amount: Number(m[1]), unit: 'days' }
  return { amount: Number(m[2]), unit: m[3] === 'H' ? 'hours' : 'minutes' }
}

/** One thing wrong with the graph, tied to the step it is about when there is one. */
export interface Issue { nodeId?: string; message: string }

/** A setting a step can't run without, left empty or unusable; {@code field} is the config key it is about. */
export interface FieldIssue { field: string; message: string }

/** A Script step saved before Functions existed holds its own script instead of naming a function. */
export const isInlineScript = (config: Record<string, unknown>) => !text(config.functionId) && typeof config.script === 'string'

/**
 * What a step's own settings are missing, field by field. These are the mandatory fields: the step
 * settings can't be saved until they are filled in. Connections are checked by {@link issues}.
 */
export function fieldIssues(n: CanvasNode, name = `"${n.label}"`): FieldIssue[] {
  const out: FieldIssue[] = []
  const add = (field: string, message: string) => out.push({ field, message })
  if (n.type === 'AGENT_STEP' && !text(n.config.agentId)) add('agentId', `${name} needs an agent.`)
  if (n.type === 'AGENT_STEP' && !text(n.config.instruction) && !text(n.config.instructionExpr)) add('instruction', `${name} needs an instruction.`)
  if (n.type === 'CONNECTOR_TOOL' && !text(n.config.toolId)) add('toolId', `${name} needs a service action.`)
  if (n.type === 'MCP_TOOL' && !text(n.config.serverId)) add('serverId', `${name} needs an MCP server.`)
  if (n.type === 'DEVICE_ACTION' && !text(n.config.action)) add('action', `${name} needs an action to run on the device.`)
  if (n.type === 'DEVICE_ACTION' && n.config.preset === 'web' && WEB_ELEMENT_ACTIONS.has(text(n.config.action))) {
    const inputs = n.config.inputs && typeof n.config.inputs === 'object' && !Array.isArray(n.config.inputs)
      ? n.config.inputs as Record<string, unknown> : {}
    const element = inputs.element
    const template = typeof element === 'string' && /^\{\{(?:(?!\{\{|}})[\s\S])+}}$/.test(element.trim())
    const bundle = element && typeof element === 'object' && !Array.isArray(element) ? element as Record<string, unknown> : {}
    if (!template && (!Array.isArray(bundle.strategies) || bundle.strategies.length === 0)) {
      add('inputs.element', 'Pick the element this step works on.')
    }
  }
  if (n.type === 'MCP_TOOL' && text(n.config.serverId) && !text(n.config.tool)) add('tool', `${name} needs a tool to call.`)
  if (n.type === 'TRANSFORM' && !text(n.config.expression)) add('expression', `${name} needs an expression.`)
  if ((n.type === 'BRANCH' || n.type === 'FILTER') && !text(n.config.condition)) add('condition', `${name} needs a condition.`)
  if (n.type === 'WAIT' && !text(n.config.duration) && !text(n.config.until)) add('until' in n.config ? 'until' : 'duration', `${name} needs how long to wait or until when.`)
  if (n.type === 'WAIT' && text(n.config.duration) && !ISO_DURATION.test(text(n.config.duration))) add('duration', `${name} has a wait length the engine can't read.`)
  if ((n.type === 'SUB_WORKFLOW' || n.type === 'FOR_EACH') && !text(n.config.workflowId)) add('workflowId', `${name} needs a workflow to run.`)
  if (n.type === 'FOR_EACH' && !text(n.config.items)) add('items', `${name} needs the list to go through.`)
  if (n.type === 'APPROVAL' && text(n.config.timeout) && !ISO_DURATION.test(text(n.config.timeout))) add('timeout', `${name} has a time limit the engine can't read.`)
  if (n.type === 'HTTP_REQUEST') {
    if (!text(n.config.url)) add('url', `${name} needs a URL.`)
    else if (!URL_OK(text(n.config.url))) add('url', `${name} needs a URL starting with http:// or https://.`)
    const auth = (n.config.auth ?? {}) as Record<string, unknown>
    if (auth.type === 'basic' && !text(auth.username)) add('auth.username', `${name} needs a username to sign in with.`)
    if (auth.type === 'bearer' && !text(auth.token)) add('auth.token', `${name} needs a token to sign in with.`)
    if (auth.type === 'apiKey' && !text(auth.name)) add('auth.name', `${name} needs the API key's name.`)
    if (auth.type === 'apiKey' && !text(auth.value)) add('auth.value', `${name} needs the API key.`)
    if (n.config.bodyType === 'expression' && !text(n.config.bodyExpr)) add('bodyExpr', `${name} needs an expression for the body.`)
    if (n.config.bodyType === 'raw' && !text(n.config.body)) add('body', `${name} needs a body to send.`)
  }
  if (n.type === 'TRIGGER') contractIssues(n.config, 'inputs', name).forEach(([f, m]) => add(f, m))
  if (n.type === 'END') contractIssues(n.config, 'outputs', name).forEach(([f, m]) => add(f, m))
  if (n.type === 'SCRIPT' && !text(n.config.functionId)) {
    if (isInlineScript(n.config)) {
      // A step saved before Functions existed, with its own script.
      if (!text(n.config.script)) add('script', `${name} needs a script.`)
      contractIssues(n.config, 'inputs', name, true).forEach(([f, m]) => add(f, m))
      contractIssues(n.config, 'outputs', name, false).forEach(([f, m]) => add(f, m))
    } else {
      add('functionId', `${name} needs a function to run.`)
    }
  }
  if (n.type === 'SWITCH') {
    const cases = casesOf(n.config)
    if (cases.length === 0) add('cases', `${name} needs at least one path.`)
    if (cases.length > MAX_CASES) add('cases', `${name} can have at most ${MAX_CASES} paths besides "else".`)
    cases.forEach((c, i) => { if (!text(c.condition)) add(`cases.${c.id}`, `${name} needs a condition for "${c.label || `path ${i + 1}`}".`) })
    if (new Set(cases.map(c => c.id)).size !== cases.length) add('cases', `${name} has two paths with the same id.`)
  }
  if (n.type === 'SET_VARIABLE') {
    const vars = Array.isArray(n.config.variables) ? n.config.variables as Array<Record<string, unknown>> : []
    if (vars.length === 0) add('variables', `${name} needs at least one variable.`)
    vars.forEach((v, i) => {
      if (!VAR_NAME.test(text(v?.name))) add(`variables.${i}.name`, `${name} needs a name for variable ${i + 1} (letters, digits and _).`)
      if (!text(v?.expr)) add(`variables.${i}.expr`, `${name} needs an expression for ${text(v?.name) ? `"${text(v.name)}"` : `variable ${i + 1}`}.`)
    })
  }
  if (n.type === 'STORAGE') {
    if (!text(n.config.key)) add('key', `${name} needs a key.`)
    const action = text(n.config.action) || 'get'
    if ((action === 'set' || action === 'append') && !text(n.config.valueExpr)) add('valueExpr', `${name} needs a value to ${action === 'set' ? 'store' : 'add'}.`)
  }
  if (n.type === 'NOTIFY' && !text(n.config.message)) add('message', `${name} needs a message.`)
  if (isLayerStep(n)) layerIssues(n, name).forEach(([f, m]) => add(f, m))
  return out
}

/** A device layer field, as the step keeps it. */
export interface LayerField {
  id: string
  label?: string
  kind: 'text' | 'select' | 'checkbox' | 'checkboxes' | 'radio' | 'date' | 'datepicker' | 'combobox' | 'list'
  element?: unknown
  section?: 'static' | 'config'
  group?: string
  value?: unknown
  options?: string[]
  mode?: 'static' | 'dynamic'
  dependsOn?: string
  required?: boolean
  format?: string
  [key: string]: unknown
}

export interface LayerGroup { id: string; title?: string }

/** A device layer's fields, groups and buttons, read from its inputs. */
export function layerOf(config: Record<string, unknown>): { fields: LayerField[]; groups: LayerGroup[]; buttons: LayerButton[] } {
  const inputs = (config.inputs && typeof config.inputs === 'object' ? config.inputs : {}) as Record<string, unknown>
  const list = <T,>(v: unknown) => (Array.isArray(v) ? v.filter(x => x && typeof x === 'object') as T[] : [])
  return {
    fields: list<LayerField>(inputs.fields),
    groups: list<LayerGroup>(inputs.groups),
    buttons: layerButtonsOf({ type: 'DEVICE_ACTION', config }),
  }
}

/**
 * Puts a layer's fields in the order it runs them: fixed values first, then the values for this
 * run group by group, and a field that follows another after it.
 */
export function orderLayerFields(fields: LayerField[], groups: LayerGroup[]): LayerField[] {
  const rank = new Map(groups.map((g, i) => [g.id, i]))
  const fixed = fields.filter(f => f.section === 'static')
  const perRun = fields.filter(f => f.section !== 'static')
    .map((f, i) => ({ f, i }))
    .sort((a, b) => (rank.get(a.f.group ?? '') ?? groups.length) - (rank.get(b.f.group ?? '') ?? groups.length) || a.i - b.i)
    .map(x => x.f)
  const out: LayerField[] = []
  const placed = new Set<string>()
  const place = (f: LayerField, seen: Set<string>) => {
    if (placed.has(f.id) || seen.has(f.id)) return
    seen.add(f.id)
    const parent = f.dependsOn ? perRun.find(p => p.id === f.dependsOn) : undefined
    if (parent) place(parent, seen)
    placed.add(f.id)
    out.push(f)
  }
  for (const f of perRun) place(f, new Set())
  return [...fixed, ...out]
}

function layerIssues(n: CanvasNode, name: string): Array<[string, string]> {
  const out: Array<[string, string]> = []
  const { fields, buttons } = layerOf(n.config)
  if (buttons.length === 0) out.push(['inputs.buttons', `${name} needs at least one button to click.`])
  buttons.forEach((b, i) => { if (!b.name.trim()) out.push(['inputs.buttons', `${name} needs a name for button ${i + 1}.`]) })
  const names = buttons.map(b => b.name.trim().toLowerCase()).filter(Boolean)
  if (new Set(names).size !== names.length) out.push(['inputs.buttons', `${name} has two buttons with the same name.`])
  const hasElement = (e: unknown) => !!e && typeof e === 'object' && Array.isArray((e as { strategies?: unknown }).strategies)
    && ((e as { strategies: unknown[] }).strategies.length > 0)
  buttons.forEach(b => { if (!hasElement(b.element)) out.push(['inputs.buttons', `${name}: pick "${b.name || 'the button'}" on the page.`]) })
  fields.forEach((f, i) => {
    if (!hasElement(f.element)) out.push([`inputs.fields.${f.id}`, `${name}: pick "${f.label || `field ${i + 1}`}" on the page.`])
  })
  return out
}

/**
 * What would stop the engine accepting this graph, in the user's words, so the canvas can say so
 * before saving and mark the step it is about. The server checks again regardless.
 */
export function issues(graph: CanvasGraph, label: (n: CanvasNode) => string = n => n.label): Issue[] {
  const out: Issue[] = []
  const starts = graph.nodes.filter(n => n.type === 'TRIGGER')
  if (starts.length !== 1) out.push({ message: 'The workflow needs exactly one Start.' })
  if (!graph.nodes.some(n => n.type !== 'TRIGGER' && n.type !== 'END')) out.push({ message: 'Add at least one step after Start.' })
  for (const n of graph.nodes) {
    const name = `"${label(n)}"`
    const add = (message: string) => out.push({ nodeId: n.id, message })
    for (const f of fieldIssues(n, name)) add(f.message)
    if (n.type === 'BRANCH') {
      for (const b of ['true', 'false'] as const) {
        if (!graph.edges.some(e => e.from === n.id && e.branch === b)) add(`${name} needs a step for "${b === 'true' ? 'yes' : 'no'}".`)
      }
    }
    if (n.type === 'PARALLEL' && graph.edges.filter(e => e.from === n.id).length < 2) add(`${name} needs at least two paths.`)
    if (n.type === 'APPROVAL' && !graph.edges.some(e => e.from === n.id && e.branch === 'approved')) add(`${name} needs a step for "approved".`)
    if (n.type === 'SWITCH' || isLayerStep(n)) {
      const exits = new Set(exitsOf(n))
      if (graph.edges.some(e => e.from === n.id && !exits.has(e.branch))) add(`${name} has a connection from a path that no longer exists.`)
    }
  }
  return out
}

export function problems(graph: CanvasGraph, label?: (n: CanvasNode) => string): string[] {
  return issues(graph, label).map(i => i.message)
}

/** Steps nothing leads to from Start. They are allowed, but they will never run. */
export function unreachable(graph: CanvasGraph): Set<string> {
  const start = graph.nodes.find(n => n.type === 'TRIGGER')
  return new Set(graph.nodes.filter(n => n.type !== 'TRIGGER' && !(start && reaches(graph, start.id, n.id))).map(n => n.id))
}

/**
 * What a recorded click chose, to start a "Choose from a list" step with: the element's own text
 * as it was recorded, so the step still chooses the same item until the person maps another.
 */
export function recordedItem(element: unknown): string {
  if (typeof element !== 'object' || element === null) return ''
  const e = element as { fingerprint?: { text?: unknown }; label?: unknown; strategies?: unknown }
  const text = typeof e.fingerprint?.text === 'string' ? e.fingerprint.text : ''
  if (text.trim()) return text.replace(/\s+/g, ' ').trim().slice(0, 200)
  if (Array.isArray(e.strategies)) {
    for (const s of e.strategies as { by?: string; name?: unknown; value?: unknown }[]) {
      if (s.by === 'role' && typeof s.name === 'string' && s.name.trim()) return s.name.trim().slice(0, 200)
      if (s.by === 'text' && typeof s.value === 'string' && s.value.trim()) return s.value.trim().slice(0, 200)
    }
  }
  return typeof e.label === 'string' ? e.label.replace(/^Click\s+/, '').replace(/^"|"$/g, '').slice(0, 200) : ''
}
