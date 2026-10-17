import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { chatApi } from '@/api/chat'
import { conversationsApi } from '@/api/conversations'
import { ApiError } from '@/lib/api'
import { useThemeStore, THEMES, type Theme } from '@/store/themeStore'
import type { AgentProposal, ChatSocketEvent, Contribution, JizzaForm, JobDto, JobEvent, JobStatus, MessageDto, UiAction, WorkflowPlan } from '@/types'
import { FORM_PAGE, PAGES, canOpenPage } from './pages'
import { getCachedSnapshot } from '@/auth/useAuthorization'
import { useFormStore } from './formStore'
import { matchLocalIntent } from './localIntents'

export interface Step {
  label: string
}

export interface AssistantMessage {
  id: number
  /** 'event' is something that happened rather than something said: "You approved Kai's request". */
  role: 'user' | 'assistant' | 'event'
  text: string
  /** On an event line: the approval it records, so its card in the reply above shows as answered. */
  notificationId?: string
  /** The job that answered (or is answering) this message; opens it in the Activity panel. */
  jobId?: string
  /** Text is still arriving. */
  streaming?: boolean
  /** What Jizza did on screen for this reply, shown as a trail under the message. */
  steps?: Step[]
  /** A question Jizza is waiting on, with one-tap answers. */
  question?: { text: string; options: string[] }
  /** A workflow plan Jizza proposed, saved only if the user approves it. */
  plan?: WorkflowPlan
  /** A new agent or team Jizza drafted, created only if the user says so. */
  proposal?: AgentProposal
  /** Specialists who worked on this reply, shown by their logos. */
  helpers?: { agentId: string; agentName: string }[]
  error?: boolean
  stopped?: boolean
}

/** A job as the Activity panel shows it. */
export interface JobView {
  id: string
  title?: string
  summary?: string
  status: JobStatus
  startedAt: string
  finishedAt?: string
  events: JobEvent[]
  /** Events have been fetched (or streamed from the start) rather than only the summary. */
  loaded: boolean
}

/** Set by the router-aware runtime so actions can move the user around the app. */
let navigator: { go: (path: string) => void; currentPage: () => string | undefined } | null = null

export function bindNavigator(n: typeof navigator) {
  navigator = n
}

interface AssistantState {
  messages: AssistantMessage[]
  conversationId?: string
  incognito: boolean
  /** A turn is running. */
  thinking: boolean
  activeJobId?: string
  jobs: Record<string, JobView>
  jobOrder: string[]
  /** The job whose memory the Memory panel shows. */
  selectedJobId?: string
  expanded: Record<string, boolean>
  dockOpen: boolean
  voiceReplies: boolean
  /** Bumped whenever Jizza replies, so voice output and other listeners can react. */
  lastReplyId: number
  /** Bumped when the conversation list should refresh (a new conversation, a new title). */
  conversationsVersion: number
  /** The Workflows page's planner is open: messages go to Jizza as workflow planning. */
  plannerOpen: boolean
  /** Plans the user approved, by plan id, with the workflow each was saved as. */
  savedPlans: Record<string, string>

  send: (text: string) => Promise<void>
  stop: () => Promise<void>
  applyEvent: (event: ChatSocketEvent) => void
  newConversation: () => void
  setIncognito: (on: boolean) => void
  setDockOpen: (open: boolean) => void
  setVoiceReplies: (on: boolean) => void
  selectJob: (jobId: string) => void
  toggleJob: (jobId: string) => void
  /** Selects a job and unfolds it, e.g. from a reply's "See how this was done". */
  openJob: (jobId: string) => void
  /** Lets the app tell Jizza about something the user just did, e.g. saving a form it drafted. */
  note: (text: string) => void
  setPlannerOpen: (open: boolean) => void
  /** Records an approved plan, and a line in the conversation saying it was saved. */
  planSaved: (planId: string, workflowId: string, name: string) => void
}

let nextId = 1

function describe(action: UiAction): string {
  switch (action.type) {
    case 'navigate': return `Opened ${PAGES[action.payload.page]?.label ?? action.payload.page}`
    case 'open_form': return action.payload.form === 'new_workflow' ? 'Drafted a new workflow' : 'Drafted a new task'
    case 'set_theme': return `Switched to the ${action.payload.theme} theme`
    case 'ask_user': return 'Waiting for your answer'
    case 'propose_workflow': return 'Proposed a workflow plan'
    case 'propose_agent': return 'Drafted a new agent'
  }
}

/** Carries out Jizza's actions on the app, in order, and reports what was done. */
function execute(actions: UiAction[], run = true): { steps: Step[]; question?: AssistantMessage['question']; plan?: WorkflowPlan; proposal?: AgentProposal } {
  const steps: Step[] = []
  let question: AssistantMessage['question']
  let plan: WorkflowPlan | undefined
  let proposal: AgentProposal | undefined
  for (const action of actions) {
    switch (action.type) {
      case 'navigate': {
        const page = PAGES[action.payload.page]
        // Never open a page this role can't read or that isn't part of the active account.
        if (!page || !canOpenPage(action.payload.page, getCachedSnapshot())) continue
        if (run) navigator?.go(page.path)
        break
      }
      case 'open_form': {
        const form = action.payload.form as JizzaForm
        const page = PAGES[FORM_PAGE[form]]
        if (FORM_PAGE[form] && !canOpenPage(FORM_PAGE[form], getCachedSnapshot())) continue
        if (run) {
          if (page && navigator?.currentPage() !== FORM_PAGE[form]) navigator?.go(page.path)
          useFormStore.getState().open(form, action.payload.fields ?? {}, true)
        }
        break
      }
      case 'set_theme': {
        const theme = action.payload.theme as Theme
        if (!THEMES.some(t => t.id === theme)) continue
        if (run) useThemeStore.getState().setTheme(theme)
        break
      }
      case 'ask_user':
        question = { text: action.payload.question, options: action.payload.options ?? [] }
        continue
      case 'propose_workflow':
        if (!action.payload?.planId || !Array.isArray(action.payload.steps)) continue
        plan = action.payload
        continue
      case 'propose_agent':
        if (!action.payload?.proposalId || !Array.isArray(action.payload.agents)) continue
        proposal = action.payload
        continue
    }
    steps.push({ label: describe(action) })
  }
  return { steps, question, plan, proposal }
}

const helpersOf = (contributors?: Contribution[]) =>
  (contributors ?? []).map(c => ({ agentId: c.agentId, agentName: c.agentName }))

/**
 * Events that reach the socket before the POST that started their job has returned are held here,
 * then replayed once the job is known. A fast turn can otherwise finish before the client learns
 * which job it is following.
 */
const early = new Map<string, ChatSocketEvent[]>()

function uuid(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function jobFromDto(dto: JobDto): JobView {
  return {
    id: dto.id, title: dto.title, summary: dto.summary, status: dto.status,
    startedAt: dto.startedAt, finishedAt: dto.finishedAt, events: dto.events ?? [], loaded: (dto.events ?? []).length > 0,
  }
}

/** Merges events by sequence number, so a replayed or duplicated event changes nothing. */
function mergeEvents(existing: JobEvent[], incoming: JobEvent[]): JobEvent[] {
  const bySeq = new Map(existing.map(e => [e.seq, e]))
  for (const e of incoming) if (e.type !== 'delta') bySeq.set(e.seq, e)
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq)
}

/** How long a turn may go quiet before the client asks the server where it got to. */
const QUIET_MS = 12_000
let watchdog: ReturnType<typeof setTimeout> | undefined

export const useAssistantStore = create<AssistantState>()(
  persist(
    (set, get) => {
      const updateMessage = (jobId: string, fn: (m: AssistantMessage) => AssistantMessage) =>
        set(s => ({ messages: s.messages.map(m => (m.role === 'assistant' && m.jobId === jobId ? fn(m) : m)) }))

      const finish = (jobId: string, status: JobStatus, summary?: string) => {
        set(s => {
          const job = s.jobs[jobId]
          const jobs = job ? { ...s.jobs, [jobId]: { ...job, status, summary: summary ?? job.summary, finishedAt: new Date().toISOString() } } : s.jobs
          // A finished job folds down to its one-line summary; the chevron opens it again.
          return {
            jobs, expanded: { ...s.expanded, [jobId]: false },
            ...(s.activeJobId === jobId ? { activeJobId: undefined, thinking: false } : {}),
            conversationsVersion: s.conversationsVersion + 1,
          }
        })
        if (watchdog && get().activeJobId === undefined) clearTimeout(watchdog)
      }

      /** Asks the server for what this job did since the last event the client saw. */
      const catchUp = async (jobId: string) => {
        const known = get().jobs[jobId]
        const lastSeq = known?.events.reduce((max, e) => Math.max(max, e.seq), 0) ?? 0
        try {
          const dto = await chatApi.job(jobId, lastSeq)
          for (const e of dto.events) apply({ ...e, jobId, conversationId: dto.conversationId })
          if (dto.status === 'running') {
            if (dto.partialText) updateMessage(jobId, m => ({ ...m, text: dto.partialText ?? m.text }))
            arm(jobId)
          }
        } catch { /* the socket may still deliver; the next quiet period tries again */ }
      }

      const arm = (jobId: string) => {
        if (watchdog) clearTimeout(watchdog)
        watchdog = setTimeout(() => { if (get().activeJobId === jobId) void catchUp(jobId) }, QUIET_MS)
      }

      const apply = (e: ChatSocketEvent) => {
        const state = get()
        const job = state.jobs[e.jobId]
        if (!job) {
          // Only the conversation on screen is followed; anything else waits to be opened.
          if (e.conversationId && e.conversationId === state.conversationId && e.type === 'job.started') {
            const continuation = e.payload.origin === 'continuation' || e.payload.origin === 'signal'
            const parent: string | undefined = e.payload.noteData?.parentJobId
            set(s => ({
              jobs: {
                ...s.jobs,
                ...(parent && s.jobs[parent] ? { [parent]: { ...s.jobs[parent], status: 'completed' as JobStatus } } : {}),
                [e.jobId]: { id: e.jobId, title: e.payload.title, status: 'running', startedAt: e.at, events: [], loaded: true },
              },
              jobOrder: [...s.jobOrder, e.jobId],
              // Something happened that Jizza is now reporting on (an approval was answered, maybe
              // from the Notifications page): show what happened, then Jizza's reply streaming in.
              ...(continuation ? {
                messages: [...s.messages,
                  { id: nextId++, role: 'event' as const, text: e.payload.note ?? '', jobId: e.jobId, notificationId: e.payload.noteData?.notificationId },
                  { id: nextId++, role: 'assistant' as const, text: '', streaming: true, jobId: e.jobId }],
                activeJobId: e.jobId, thinking: true, selectedJobId: e.jobId,
                expanded: { ...s.expanded, [e.jobId]: true },
              } : {}),
            }))
            if (continuation) arm(e.jobId)
          } else {
            const held = early.get(e.jobId) ?? []
            held.push(e)
            early.set(e.jobId, held.slice(-500))
            // Jobs from other conversations are never claimed; keep only the most recent few.
            while (early.size > 20) early.delete(early.keys().next().value as string)
            return
          }
        }
        if (e.jobId === state.activeJobId) arm(e.jobId)

        switch (e.type) {
          case 'delta':
            updateMessage(e.jobId, m => ({ ...m, text: m.streaming ? m.text + (e.payload.text ?? '') : m.text }))
            return
          case 'conversation.titled':
            // The model named the conversation after its first exchange; the list shows it.
            set(s => ({ conversationsVersion: s.conversationsVersion + 1 }))
            break
          case 'segment.preamble':
            // What streamed so far was Jizza narrating before using tools; the answer comes next.
            updateMessage(e.jobId, m => ({ ...m, text: '' }))
            break
          case 'job.completed': {
            const actions = (e.payload.actions ?? []) as UiAction[]
            const { steps, question, plan, proposal } = execute(actions)
            const id = nextId++
            set(s => ({
              messages: s.messages.map(m => (m.role === 'assistant' && m.jobId === e.jobId
                ? { ...m, id, text: e.payload.text, streaming: false, steps, question, plan, proposal, helpers: helpersOf(e.payload.contributors) }
                : m)),
              lastReplyId: id,
            }))
            break
          }
          case 'job.failed':
            updateMessage(e.jobId, m => ({ ...m, streaming: false, error: true, text: e.payload.detail ?? 'Something went wrong.' }))
            break
          case 'job.cancelled':
            updateMessage(e.jobId, m => ({ ...m, streaming: false, stopped: true, text: e.payload.text || '' }))
            break
        }

        set(s => {
          const current = s.jobs[e.jobId]
          if (!current) return s
          return { jobs: { ...s.jobs, [e.jobId]: { ...current, events: mergeEvents(current.events, [e]) } } }
        })

        if (e.type === 'job.completed') finish(e.jobId, (e.payload.status as JobStatus) ?? 'completed', e.payload.summary)
        if (e.type === 'job.failed') finish(e.jobId, 'failed')
        if (e.type === 'job.cancelled') finish(e.jobId, 'cancelled', e.payload.summary)
      }

      return {
        messages: [],
        conversationId: undefined,
        incognito: false,
        thinking: false,
        activeJobId: undefined,
        jobs: {},
        jobOrder: [],
        selectedJobId: undefined,
        expanded: {},
        dockOpen: false,
        voiceReplies: false,
        lastReplyId: 0,
        conversationsVersion: 0,
        plannerOpen: false,
        savedPlans: {},

        send: async (raw) => {
          const text = raw.trim()
          if (!text || get().thinking) return
          // Answering a question clears its choices so they can't be tapped twice.
          set(s => ({
            messages: [...s.messages.map(m => (m.question ? { ...m, question: undefined } : m)),
              { id: nextId++, role: 'user', text }],
          }))

          // Instant path: a plain command needs no round trip. Not while planning: there
          // "create a workflow" is the start of a conversation, not a request for the form.
          const local = get().plannerOpen ? null : matchLocalIntent(text)
          if (local) {
            const { steps } = execute(local.actions)
            const id = nextId++
            set(s => ({ messages: [...s.messages, { id, role: 'assistant', text: local.reply, steps }], lastReplyId: id }))
            return
          }

          set({ thinking: true })
          const { incognito, conversationId } = get()
          const clientMessageId = uuid()
          try {
            const ref = await chatApi.send({
              message: text, clientMessageId,
              screen: get().plannerOpen ? 'workflow_planner' : navigator?.currentPage() ?? 'chat',
              ...(incognito ? { incognito: true } : { conversationId }),
            })
            set(s => ({
              conversationId: incognito ? undefined : ref.conversationId,
              activeJobId: ref.jobId,
              selectedJobId: ref.jobId,
              jobs: { ...s.jobs, [ref.jobId]: s.jobs[ref.jobId] ?? { id: ref.jobId, title: text, status: 'running', startedAt: new Date().toISOString(), events: [], loaded: true } },
              jobOrder: s.jobOrder.includes(ref.jobId) ? s.jobOrder : [...s.jobOrder, ref.jobId],
              expanded: { ...s.expanded, [ref.jobId]: true },
              messages: [
                ...s.messages.map((m, i) => (i === s.messages.length - 1 && m.role === 'user' ? { ...m, jobId: ref.jobId } : m)),
                { id: nextId++, role: 'assistant', text: '', streaming: true, jobId: ref.jobId },
              ],
              conversationsVersion: s.conversationsVersion + 1,
            }))
            const held = early.get(ref.jobId) ?? []
            early.delete(ref.jobId)
            for (const e of held) apply(e)
            arm(ref.jobId)
          } catch (err) {
            const busy = err instanceof ApiError && err.status === 409
            const message = err instanceof Error ? err.message : 'Request failed'
            const id = nextId++
            set(s => ({
              thinking: false,
              messages: [...s.messages, {
                id, role: 'assistant', error: true,
                text: busy ? message : `I couldn't reach my reasoning engine (${message}). Simple commands like "open workflows" or "create a workflow" still work.`,
              }],
              lastReplyId: id,
            }))
          }
        },

        stop: async () => {
          const jobId = get().activeJobId
          if (!jobId) return
          try { await chatApi.cancel(jobId) } catch { /* it may have just finished */ }
        },

        applyEvent: apply,

        newConversation: () => set({ messages: [], conversationId: undefined, jobs: {}, jobOrder: [], selectedJobId: undefined, expanded: {}, activeJobId: undefined, thinking: false }),
        setIncognito: (on) => set({ incognito: on, messages: [], conversationId: undefined, jobs: {}, jobOrder: [], selectedJobId: undefined, expanded: {} }),
        setDockOpen: (open) => set({ dockOpen: open }),
        setVoiceReplies: (on) => set({ voiceReplies: on }),

        selectJob: (jobId) => {
          set({ selectedJobId: jobId })
          void loadJob(jobId)
        },
        toggleJob: (jobId) => {
          set(s => ({ expanded: { ...s.expanded, [jobId]: !s.expanded[jobId] }, selectedJobId: jobId }))
          void loadJob(jobId)
        },

        openJob: (jobId) => {
          set(s => ({ expanded: { ...s.expanded, [jobId]: true }, selectedJobId: jobId }))
          void loadJob(jobId)
        },

        note: (text) => {
          const id = nextId++
          set(s => ({ messages: [...s.messages, { id, role: 'assistant', text }], lastReplyId: id }))
        },

        setPlannerOpen: (open) => set({ plannerOpen: open }),

        planSaved: (planId, workflowId, name) => set(s => ({
          savedPlans: { ...s.savedPlans, [planId]: workflowId },
          messages: [...s.messages, { id: nextId++, role: 'event', text: `You approved the plan. "${name}" is saved as a draft workflow.` }],
        })),
      }
    },
    {
      name: 'jizza-assistant',
      // The conversation lives on the server; locally keep only where we were and preferences.
      partialize: (s) => ({ conversationId: s.conversationId, voiceReplies: s.voiceReplies, savedPlans: s.savedPlans }),
    },
  ),
)

/** Fetches a finished job's timeline the first time it is opened. */
export async function loadJob(jobId: string) {
  const job = useAssistantStore.getState().jobs[jobId]
  if (!job || job.loaded) return
  try {
    const dto = await chatApi.job(jobId, 0)
    useAssistantStore.setState(s => ({
      jobs: { ...s.jobs, [jobId]: { ...jobFromDto(dto), events: mergeEvents(s.jobs[jobId]?.events ?? [], dto.events), loaded: true } },
    }))
  } catch { /* leave the summary; opening it again retries */ }
}

function messageFromDto(m: MessageDto): AssistantMessage {
  if (m.role === 'event') {
    return { id: nextId++, role: 'event', text: m.content, jobId: m.jobId, notificationId: m.metadata?.notificationId }
  }
  const actions = m.metadata?.actions ?? []
  const { steps, question, plan, proposal } = execute(actions, false)
  return {
    id: nextId++,
    role: m.role === 'user' ? 'user' : 'assistant',
    text: m.content,
    jobId: m.jobId ?? m.metadata?.jobId,
    streaming: m.status === 'streaming',
    error: m.status === 'failed',
    stopped: m.status === 'cancelled',
    steps: steps.length ? steps : undefined,
    question,
    plan,
    proposal,
    helpers: m.metadata?.contributors?.length ? helpersOf(m.metadata.contributors) : undefined,
  }
}

/** Fills in the current conversation and its jobs after a reload or when it is opened from the list. */
export async function loadHistory() {
  const { conversationId, messages } = useAssistantStore.getState()
  if (!conversationId || messages.length > 0) return
  try {
    const [rows, jobs] = await Promise.all([conversationsApi.messages(conversationId), conversationsApi.jobs(conversationId)])
    if (useAssistantStore.getState().conversationId !== conversationId) return
    const running = jobs.find(j => j.status === 'running')
    useAssistantStore.setState(s => (s.messages.length > 0 ? s : {
      messages: rows.map(messageFromDto),
      jobs: Object.fromEntries(jobs.map(j => [j.id, jobFromDto(j)])),
      jobOrder: jobs.map(j => j.id),
      expanded: running ? { [running.id]: true } : {},
      selectedJobId: running?.id ?? jobs[jobs.length - 1]?.id,
      activeJobId: running?.id,
      thinking: !!running,
    }))
    const selected = useAssistantStore.getState().selectedJobId
    if (selected) void loadJob(selected)
    if (running) void catchUpRunning(running.id)
  } catch {
    // The conversation may be gone; start fresh rather than show an error.
    useAssistantStore.setState({ conversationId: undefined })
  }
}

/** A turn that was running when the page reloaded: pick up where it is. */
async function catchUpRunning(jobId: string) {
  try {
    const dto = await chatApi.job(jobId, 0)
    const apply = useAssistantStore.getState().applyEvent
    if (dto.partialText) {
      useAssistantStore.setState(s => ({ messages: s.messages.map(m => (m.role === 'assistant' && m.jobId === jobId ? { ...m, text: dto.partialText ?? '' } : m)) }))
    }
    for (const e of dto.events) apply({ ...e, jobId, conversationId: dto.conversationId })
  } catch { /* the socket will deliver the rest */ }
}

/** Opens a conversation from the list. */
export function resumeConversation(conversationId: string) {
  useAssistantStore.setState({ conversationId, messages: [], incognito: false, jobs: {}, jobOrder: [], selectedJobId: undefined, expanded: {}, activeJobId: undefined, thinking: false })
  void loadHistory()
}
