/**
 * Fields made from a workflow step: one per setting of the step that takes data (a notification's
 * message, a webhook's URL, a service action's arguments, a start step's inputs). Each one is
 * bound to the setting it fills, so a submission runs that step with what was typed in. The
 * engine never puts the typed text in the setting itself: it points the setting at the run's
 * input (StepOverrides.java), so a setting that takes {{ }} gets {{ input.<name> }} and one that
 * takes a formula gets input.<name>.
 */
import { NODE_META, type NodeType } from '@/components/workflow/canvasModel'
import { linkNameOf, newId, uniqueName, type FieldType, type FormField } from './formModel'

/** A step as the saved graph holds it. */
export interface GraphStep { id: string; type: NodeType; label?: string; config?: Record<string, unknown> }

export interface WorkflowLike { id: string; name: string; graph: unknown }

/** The steps of a saved workflow, in the order they were added, the end step left out. */
export function stepsOf(graph: unknown): GraphStep[] {
  const nodes = (graph as { nodes?: unknown })?.nodes
  if (!Array.isArray(nodes)) return []
  return nodes
    .filter((n): n is GraphStep => !!n && typeof n === 'object' && typeof (n as GraphStep).id === 'string' && typeof (n as GraphStep).type === 'string')
    .filter(n => n.type !== 'END')
}

export function stepLabel(step: GraphStep): string {
  return step.label?.trim() || NODE_META[step.type]?.label || step.type
}

/** One setting of a step that a form field can fill. */
export interface StepInput {
  path: Array<string | number>
  mode: 'template' | 'expression' | 'input'
  label: string
  type: FieldType
  /** The input's own link name (a start input must keep its name). */
  name?: string
  required?: boolean
  help?: string
  /** What the setting holds now, shown as the field's hint. */
  current?: string
}

const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

/** A readable label from a key such as invoiceTotal or due_date. */
export function labelOf(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_\-.]+/g, ' ').trim()
  return words ? words[0].toUpperCase() + words.slice(1).toLowerCase() : key
}

/** The field type that suits a setting, from its declared type or its name. */
export function guessType(key: string, declared?: unknown, current?: unknown): FieldType {
  switch (declared) {
    case 'number': return 'decimal'
    case 'boolean': return 'decision'
    case 'date': return 'date'
    case 'list': case 'map': return 'multi_line'
    default: break
  }
  const k = key.toLowerCase()
  if (/e-?mail/.test(k)) return 'email'
  if (/(^|_|[a-z])(url|link|website|href)$/.test(k) || k === 'url') return 'url'
  if (/phone|mobile/.test(k)) return 'phone'
  if (/date$|_on$|^date|deadline|birthday/.test(k)) return 'date'
  if (/time$|^time/.test(k)) return 'time'
  if (/amount|price|total|cost|qty|quantity|count|number|age|rate/.test(k)) return 'decimal'
  if (/message|body|description|notes?$|comment|prompt|instruction|text$|content|details/.test(k)) return 'multi_line'
  if (typeof current === 'number') return 'decimal'
  if (typeof current === 'boolean') return 'decision'
  if (typeof current === 'string' && current.length > 80) return 'multi_line'
  return 'single_line'
}

const shown = (v: unknown): string | undefined => {
  if (v === undefined || v === null || v === '') return undefined
  const text = typeof v === 'string' ? v : JSON.stringify(v)
  return text.length > 120 ? `${text.slice(0, 117)}…` : text
}

/** Each key of an object setting, such as a service action's arguments, as a template setting. */
function keysOf(config: Record<string, unknown>, key: string, skip: (k: string, v: unknown) => boolean = () => false): StepInput[] {
  const value = config[key]
  if (!isObject(value)) return []
  return Object.entries(value)
    .filter(([k, v]) => /^[A-Za-z0-9_.-]{1,64}$/.test(k) && !skip(k, v))
    .map(([k, v]) => ({ path: [key, k], mode: 'template' as const, label: labelOf(k), type: guessType(k, undefined, v), current: shown(v) }))
}

/** The settings of {@code step} that a form can fill. */
export function inputsOfStep(step: GraphStep): StepInput[] {
  const c = isObject(step.config) ? step.config : {}
  const text = (key: string, label: string, type: FieldType, required = false): StepInput =>
    ({ path: [key], mode: 'template', label, type, required, current: shown(c[key]) })
  switch (step.type) {
    case 'TRIGGER': {
      const inputs = Array.isArray(c.inputs) ? c.inputs : []
      return inputs.filter(isObject).filter(i => typeof i.name === 'string' && i.name).map(i => ({
        path: [], mode: 'input' as const, name: String(i.name), label: labelOf(String(i.name)),
        type: guessType(String(i.name), i.type), required: i.required === true,
        help: typeof i.description === 'string' ? i.description : undefined,
      }))
    }
    case 'NOTIFY':
      return [text('title', 'Title', 'single_line'), text('message', 'Message', 'multi_line', true)]
    case 'APPROVAL':
      return [text('prompt', 'What to approve', 'multi_line')]
    case 'HTTP_REQUEST':
      return [
        text('url', 'URL', 'url'),
        // A JSON or form body is named fields; a raw one (or one from before body types) is text.
        ...(c.bodyType === 'json' || c.bodyType === 'form' ? keysOf(c, 'fields')
          : c.bodyType === 'raw' || (!c.bodyType && !c.bodyExpr) ? [text('body', 'Body', 'multi_line')] : []),
        ...keysOf(c, 'queryParams'),
      ]
    case 'AGENT_STEP':
      return [{ path: ['instructionExpr'], mode: 'expression', label: 'Instruction', type: 'multi_line', required: true, current: shown(c.instruction) }]
    case 'CONNECTOR_TOOL':
    case 'MCP_TOOL':
      return keysOf(c, 'arguments', (_, v) => isObject(v) || Array.isArray(v))
    case 'DEVICE_ACTION':
      // A web step's element and session are how it finds the page, not data to type in.
      return keysOf(c, 'inputs', (k, v) => isObject(v) || Array.isArray(v) || ['element', 'session', 'target', 'selector'].includes(k))
    case 'SUB_WORKFLOW':
      return keysOf(c, 'inputs')
    case 'SCRIPT': {
      if (Array.isArray(c.inputs)) {
        return c.inputs.flatMap((i, at) => (isObject(i) && typeof i.name === 'string' && i.name
          ? [{ path: ['inputs', at, 'value'], mode: 'template' as const, label: labelOf(i.name), type: guessType(i.name, i.type), current: shown(i.value) }]
          : []))
      }
      return keysOf(c, 'inputs')
    }
    default:
      return []
  }
}

/** Whether a step has anything a form can fill. */
export function fillable(step: GraphStep): boolean {
  return inputsOfStep(step).length > 0
}

/**
 * A step layout for {@code step} of {@code workflow}: its fields pending, named so they don't
 * clash with {@code existing}. A start step's inputs keep their own names, since the workflow
 * reads them as input.<name>.
 */
export function layoutFor(workflow: WorkflowLike, step: GraphStep, existing: FormField[]): FormField {
  const fields: FormField[] = []
  for (const input of inputsOfStep(step)) {
    const all = [...existing, ...fields]
    // A start input already in the form is already filled; a second field could not keep its name.
    if (input.name && uniqueName(all, input.name) !== input.name) continue
    const name = input.name ?? uniqueName(all, linkNameOf(input.label))
    fields.push({
      id: newId(),
      type: input.type,
      name,
      label: input.label,
      required: input.required || undefined,
      help: input.help,
      stepValue: input.current,
      ...(input.type === 'decimal' ? { decimals: 2 } : {}),
      binding: { workflowId: workflow.id, nodeId: step.id, path: input.path, mode: input.mode },
      pending: true,
    })
  }
  return {
    id: newId('l'), type: 'step_layout', label: stepLabel(step), stepLabel: stepLabel(step),
    workflowId: workflow.id, workflowName: workflow.name, nodeId: step.id, children: fields,
  }
}
