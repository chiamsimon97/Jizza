import type { JizzaContext } from './contextStore'

/**
 * What each Builder step's settings are, in the words Jizza is given when it configures one. Keep
 * it in step with the fields CanvasInspector shows: Jizza can only fill in what is listed here.
 */
export const STEP_FIELDS: Record<string, string> = {
  AGENT_STEP: 'agentId (id of one of the agents listed under "agents"), instruction (what the agent should do; may use {{ … }} templates)',
  HUMAN_INTERVENTION: 'prompt (what to ask the user when the run reaches this step)',
  CONNECTOR_TOOL: 'toolId (id of one of the service actions listed under "tools"), toolName (its label), arguments (JSON object of the action\'s arguments; values may use {{ … }})',
  TRANSFORM: 'expression (a JEXL expression over input, nodes, vars and instructions; fmt: helpers such as fmt:upper(s), fmt:round(n, 2))',
  BRANCH: 'condition (a JEXL test over input, nodes and vars, e.g. nodes.check.price < 2000)',
  FILTER: 'condition (a JEXL test; when false this path stops here)',
  WAIT: 'duration (ISO-8601 duration such as PT1H or P1D) or until (ISO-8601 local date-time); set the other to null',
  SUB_WORKFLOW: 'workflowId (id from "workflows"), workflowName, inputExpr (optional JEXL for the input)',
  FOR_EACH: 'workflowId (id from "workflows"), workflowName, items (JEXL giving a list), concurrency (1-10), continueOnError (boolean)',
  APPROVAL: 'prompt (what to approve), timeout (optional ISO-8601 duration), onTimeout ("rejected" or "approved")',
  HTTP_REQUEST: [
    'method (GET, POST, PUT, PATCH, DELETE), url (http/https; may use {{ … }})',
    'queryParams (object name → value), headers (object name → value)',
    'auth: null for none, or {"type": "basic", "username", "password"} | {"type": "bearer", "token"} | {"type": "apiKey", "name", "in": "header"|"query", "value"}',
    'bodyType: "json" | "form" | "raw" | "expression" | "none"',
    'fields (object key → value, for json and form; a value that is just {{ … }} keeps its type), unflatten (boolean: nest dotted keys), wrapInArray (boolean)',
    'contentType and body (for raw), bodyExpr (JEXL, for expression)',
    'responseType: null (work it out) | "json" | "text", failOnError (false to keep going on an error status)',
  ].join('; '),
  SWITCH: 'cases (list of {"id": "case1", "label", "condition"}; at most 5; keep existing ids, new ones case2, case3…)',
  SET_VARIABLE: 'variables (list of {"name": letters/digits/_, "expr": JEXL})',
  STORAGE: 'action ("get" | "set" | "increment" | "append" | "delete"), key, valueExpr (JEXL, for set/append), by (number, for increment), maxItems (for append)',
  NOTIFY: 'title, message (may use {{ … }}), channel ("app" | "email" | "both")',
  TRIGGER: 'nothing to configure',
  JOIN: 'nothing to configure',
  PARALLEL: 'nothing to configure',
  END: 'nothing to configure',
}

/** Whether a step has settings Jizza can fill in. */
export function canConfigureWithJizza(type: string): boolean {
  return !!STEP_FIELDS[type] && STEP_FIELDS[type] !== 'nothing to configure'
}

/** A way to start configuring a step, offered as a choice when Jizza opens on it. */
export interface Starter {
  /** Shown on the choice, and sent as what the user picked. */
  label: string
  /** Jizza's reply on the spot, asking for what it needs. */
  reply: string
}

const DESCRIBE: Starter = { label: 'Describe it in words', reply: 'Tell me what this step should do, in your own words, and I\'ll fill in its settings.' }

export const STEP_STARTERS: Record<string, Starter[]> = {
  HTTP_REQUEST: [
    { label: 'From API documentation', reply: 'Paste the API documentation for the call: the endpoint, its parameters, how it signs in, and an example request if there is one. Tell me what the call should do and I\'ll set up the method, URL, headers and payload. I can\'t open links, so paste the text itself.' },
    { label: 'From a cURL command', reply: 'Paste the cURL command and I\'ll turn it into this step\'s settings.' },
    DESCRIBE,
  ],
  AGENT_STEP: [
    { label: 'Write the instruction', reply: 'What should the agent get done in this step? I\'ll write the instruction and pick the agent that fits.' },
    DESCRIBE,
  ],
  CONNECTOR_TOOL: [
    { label: 'Pick the service action', reply: 'What should happen in the connected service? I\'ll pick the action and fill in its arguments.' },
    DESCRIBE,
  ],
  BRANCH: [{ label: 'Write the condition', reply: 'Say when the workflow should take the "yes" exit, e.g. "the price is below 2000", and I\'ll write the condition.' }],
  FILTER: [{ label: 'Write the condition', reply: 'Say when the workflow should carry on past this step, and I\'ll write the condition.' }],
  SWITCH: [{ label: 'Set up the paths', reply: 'Which paths do you need, and when should each be taken? I\'ll set up the cases.' }],
  TRANSFORM: [{ label: 'Build the expression', reply: 'Describe the data you want out of this step, and from which earlier step, and I\'ll write the expression.' }],
  SET_VARIABLE: [{ label: 'Set up the variables', reply: 'Which values should later steps be able to use? I\'ll set up the variables.' }],
  STORAGE: [{ label: 'Set up storage', reply: 'What should this step remember (or read back) between runs?' }],
  NOTIFY: [{ label: 'Write the message', reply: 'What should the notification tell you? I\'ll write it, with values from earlier steps where they help.' }],
  WAIT: [{ label: 'Set the wait', reply: 'How long should the workflow wait, or until when?' }],
  APPROVAL: [{ label: 'Write the request', reply: 'What should you be asked to approve, and what should happen if nobody answers?' }],
  HUMAN_INTERVENTION: [{ label: 'Write the question', reply: 'What should the workflow ask you when it reaches this step?' }],
}

export function startersFor(stepType: string | undefined): Starter[] {
  return (stepType && STEP_STARTERS[stepType]) || [DESCRIBE]
}

/**
 * A slash command. {@code prompt} is sent as it is; {@code insert} goes into the message box for the
 * user to finish; {@code run} is handled on the device.
 */
export interface SlashCommand {
  name: string
  description: string
  prompt?: string
  insert?: string
  run?: 'new' | 'help' | 'leave'
}

const GENERAL: SlashCommand[] = [
  { name: 'new', description: 'Start a new conversation', run: 'new' },
  { name: 'help', description: 'What I can do here', run: 'help' },
]

const BUILDER: SlashCommand[] = [
  { name: 'explain', description: 'Explain what this workflow does, step by step', prompt: 'Explain what this workflow does, step by step.' },
  { name: 'check', description: 'Find problems and missing settings', prompt: 'Check this workflow for problems: missing settings, steps that can never run, and anything that will fail.' },
  { name: 'next', description: 'Suggest the next step to add', prompt: 'Which step should I add next to this workflow, and how should I set it up?' },
  { name: 'data', description: 'How to use data from earlier steps', prompt: 'How can later steps in this workflow use the data earlier steps produce? Show me the {{ }} references I can use.' },
]

const STEP: SlashCommand[] = [
  { name: 'configure', description: 'Fill in this step from a description', insert: 'Configure this step to ' },
  { name: 'explain', description: 'Explain this step\'s settings', prompt: 'Explain what this step does with its current settings.' },
  { name: 'check', description: 'What is missing or wrong', prompt: 'What is missing or wrong in this step\'s settings?' },
  { name: 'map', description: 'Use data from an earlier step', insert: 'Use the data from the earlier step ' },
]

const STEP_EXTRA: Record<string, SlashCommand[]> = {
  HTTP_REQUEST: [
    { name: 'api-docs', description: 'Configure from API documentation', insert: 'Configure this webhook from this API documentation:\n' },
    { name: 'curl', description: 'Configure from a cURL command', insert: 'Configure this webhook from this cURL command:\n' },
    { name: 'auth', description: 'Set up authentication', insert: 'Set up authentication for this call: ' },
    { name: 'payload', description: 'Build the payload from earlier steps', prompt: 'Build this call\'s payload from the data earlier steps produce. Ask me which values to send.' },
  ],
  AGENT_STEP: [{ name: 'instruction', description: 'Write the agent\'s instruction', insert: 'The agent should ' }],
  BRANCH: [{ name: 'condition', description: 'Write the condition from a sentence', insert: 'Take the yes exit when ' }],
  FILTER: [{ name: 'condition', description: 'Write the condition from a sentence', insert: 'Carry on only when ' }],
  SWITCH: [{ name: 'paths', description: 'Set up the paths', insert: 'Set up these paths: ' }],
  TRANSFORM: [{ name: 'expression', description: 'Build the expression', insert: 'Build an expression that ' }],
  NOTIFY: [{ name: 'message', description: 'Write the message', insert: 'The notification should tell me ' }],
  WAIT: [{ name: 'schedule', description: 'Set how long to wait', insert: 'Wait ' }],
}

const PAGE_EXTRA: Record<string, SlashCommand[]> = {
  workflows: [{ name: 'create', description: 'Draft a new workflow', insert: 'Create a workflow that ' }],
  tasks: [{ name: 'schedule', description: 'Schedule a task', insert: 'Schedule a task to ' }],
  agents: [{ name: 'agent', description: 'Draft a new agent', insert: 'Create an agent that ' }],
  usage: [{ name: 'costs', description: 'What my agents cost this month', prompt: 'What did my agents cost this month, and what drove it?' }],
  memory: [{ name: 'recall', description: 'What you know about something', insert: 'What do you know about ' }],
}

const PLANNER: SlashCommand[] = [
  { name: 'example', description: 'Show an example of a good request', prompt: 'Give me an example of a workflow request you can plan well.' },
]

/** The commands that fit where Jizza is, most specific first. */
export function commandsFor(ctx: JizzaContext): SlashCommand[] {
  const specific = ctx.kind === 'step'
    ? [...(STEP_EXTRA[ctx.stepType ?? ''] ?? []), ...STEP, { name: 'leave', description: 'Stop working on this step', run: 'leave' as const }]
    : ctx.kind === 'builder' ? BUILDER
    : ctx.kind === 'planner' ? PLANNER
    : PAGE_EXTRA[ctx.screen] ?? []
  return [...specific, ...GENERAL]
}

/** Commands whose name starts with what was typed after the slash. */
export function matchCommands(ctx: JizzaContext, typed: string): SlashCommand[] {
  const q = typed.toLowerCase()
  const all = commandsFor(ctx)
  return all.filter(c => c.name.startsWith(q)).concat(q ? all.filter(c => !c.name.startsWith(q) && c.description.toLowerCase().includes(q)) : [])
}

/** "/help": what the commands here do, as a reply. */
export function helpText(ctx: JizzaContext): string {
  const where = ctx.detail ? `${ctx.label} (${ctx.detail})` : ctx.label
  return `I'm working with you on the ${where}. Type / for these shortcuts:\n\n${commandsFor(ctx)
    .map(c => `- **/${c.name}**: ${c.description}`).join('\n')}`
}
