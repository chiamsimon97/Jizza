import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { chatApi } from '@/api/chat'
import { conversationsApi } from '@/api/conversations'
import { useThemeStore, THEMES, type Theme } from '@/store/themeStore'
import type { JizzaForm, UiAction } from '@/types'
import { FORM_PAGE, PAGES } from './pages'
import { useFormStore } from './formStore'
import { matchLocalIntent } from './localIntents'

export interface Step {
  label: string
}

export interface AssistantMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** What Jizza did on screen for this reply, shown as a trail under the message. */
  steps?: Step[]
  /** A question Jizza is waiting on, with one-tap answers. */
  question?: { text: string; options: string[] }
  /** Specialists who worked on this reply, shown by their logos. */
  helpers?: { agentId: string; agentName: string }[]
  error?: boolean
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
  thinking: boolean
  dockOpen: boolean
  voiceReplies: boolean
  /** Bumped whenever Jizza replies, so voice output and other listeners can react. */
  lastReplyId: number

  send: (text: string) => Promise<void>
  newConversation: () => void
  setIncognito: (on: boolean) => void
  setDockOpen: (open: boolean) => void
  setVoiceReplies: (on: boolean) => void
  /** Lets the app tell Jizza about something the user just did, e.g. saving a form it drafted. */
  note: (text: string) => void
}

let nextId = 1

function describe(action: UiAction): string {
  switch (action.type) {
    case 'navigate': return `Opened ${PAGES[action.payload.page]?.label ?? action.payload.page}`
    case 'open_form': return action.payload.form === 'new_workflow' ? 'Drafted a new workflow' : 'Drafted a new task'
    case 'set_theme': return `Switched to the ${action.payload.theme} theme`
    case 'ask_user': return 'Waiting for your answer'
  }
}

/** Carries out Jizza's actions on the app, in order, and reports what was done. */
function execute(actions: UiAction[]): { steps: Step[]; question?: AssistantMessage['question'] } {
  const steps: Step[] = []
  let question: AssistantMessage['question']
  for (const action of actions) {
    switch (action.type) {
      case 'navigate': {
        const page = PAGES[action.payload.page]
        if (!page) continue
        navigator?.go(page.path)
        break
      }
      case 'open_form': {
        const form = action.payload.form as JizzaForm
        const page = PAGES[FORM_PAGE[form]]
        if (page && navigator?.currentPage() !== FORM_PAGE[form]) navigator?.go(page.path)
        useFormStore.getState().open(form, action.payload.fields ?? {}, true)
        break
      }
      case 'set_theme': {
        const theme = action.payload.theme as Theme
        if (!THEMES.some(t => t.id === theme)) continue
        useThemeStore.getState().setTheme(theme)
        break
      }
      case 'ask_user':
        question = { text: action.payload.question, options: action.payload.options ?? [] }
        continue
    }
    steps.push({ label: describe(action) })
  }
  return { steps, question }
}

export const useAssistantStore = create<AssistantState>()(
  persist(
    (set, get) => ({
      messages: [],
      conversationId: undefined,
      incognito: false,
      thinking: false,
      dockOpen: false,
      voiceReplies: false,
      lastReplyId: 0,

      send: async (raw) => {
        const text = raw.trim()
        if (!text || get().thinking) return
        // Answering a question clears its choices so they can't be tapped twice.
        set(s => ({
          messages: [...s.messages.map(m => (m.question ? { ...m, question: undefined } : m)),
            { id: nextId++, role: 'user', text }],
        }))

        const reply = (msg: Omit<AssistantMessage, 'id' | 'role'>) => {
          const id = nextId++
          set(s => ({ messages: [...s.messages, { id, role: 'assistant', ...msg }], thinking: false, lastReplyId: id }))
        }

        // Instant path: a plain command needs no round trip.
        const local = matchLocalIntent(text)
        if (local) {
          const { steps } = execute(local.actions)
          reply({ text: local.reply, steps })
          return
        }

        set({ thinking: true })
        const screen = navigator?.currentPage() ?? 'chat'
        try {
          const { incognito, conversationId } = get()
          const res = incognito
            ? await chatApi.sendIncognito(text, screen)
            : await chatApi.send(text, conversationId, screen)
          if (!incognito && res.conversationId) set({ conversationId: res.conversationId })
          const { steps, question } = execute(res.actions ?? [])
          const helpers = (res.contributors ?? []).map(c => ({ agentId: c.agentId, agentName: c.agentName }))
          reply({ text: res.text, steps, question, helpers })
        } catch (err) {
          const message = err instanceof Error ? err.message : 'Request failed'
          reply({ text: `I couldn't reach my reasoning engine (${message}). Simple commands like "open workflows" or "create a workflow" still work.`, error: true })
        }
      },

      newConversation: () => set({ messages: [], conversationId: undefined }),
      setIncognito: (on) => set({ incognito: on, messages: [], conversationId: undefined }),
      setDockOpen: (open) => set({ dockOpen: open }),
      setVoiceReplies: (on) => set({ voiceReplies: on }),
      note: (text) => {
        const id = nextId++
        set(s => ({ messages: [...s.messages, { id, role: 'assistant', text }], lastReplyId: id }))
      },
    }),
    {
      name: 'jizza-assistant',
      // The conversation lives on the server; locally keep only where we were and preferences.
      partialize: (s) => ({ conversationId: s.conversationId, voiceReplies: s.voiceReplies }),
    },
  ),
)

/** Fills in the earlier turns of the current conversation after a reload or from History. */
export async function loadHistory() {
  const { conversationId, messages } = useAssistantStore.getState()
  if (!conversationId || messages.length > 0) return
  try {
    const rows = await conversationsApi.messages(conversationId)
    if (useAssistantStore.getState().conversationId !== conversationId) return
    useAssistantStore.setState(s => s.messages.length > 0 ? s : {
      messages: rows.map(r => ({
        id: nextId++,
        role: String(r.role).toLowerCase() === 'user' ? 'user' as const : 'assistant' as const,
        text: r.content,
      })),
    })
  } catch {
    // The conversation may be gone; start fresh rather than show an error.
    useAssistantStore.setState({ conversationId: undefined })
  }
}

/** Opens the conversation from the history page. */
export function resumeConversation(conversationId: string) {
  useAssistantStore.setState({ conversationId, messages: [], incognito: false })
  void loadHistory()
}
