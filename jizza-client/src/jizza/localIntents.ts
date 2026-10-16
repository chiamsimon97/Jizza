import type { UiAction } from '@/types'
import { PAGES, pagesFor } from './pages'
import { getCachedSnapshot } from '@/auth/useAuthorization'

export interface LocalIntent {
  actions: UiAction[]
  reply: string
}

const OPEN = /^(?:please\s+)?(?:open|go to|goto|show(?: me)?|take me to|navigate to|switch to|bring up|view)\s+(?:the\s+|my\s+)?(.+?)(?:\s+page)?[.!?]*$/i
const THEME = /\b(light|dark|sunset)\b(?:\s+(?:mode|theme))?/i
const THEME_VERB = /\b(switch|change|use|turn on|set|go|make it)\b|\b(mode|theme)\b/i
const CREATE = /^(?:please\s+)?(?:create|make|build|add|set up|start|draft|new)\s+(?:me\s+)?(?:a\s+|an\s+)?(?:new\s+)?(workflow|automation|task|reminder|scheduled task)(?:\s+(?:called|named)\s+["']?(.+?)["']?)?[.!?]*$/i

function findPage(phrase: string): string | undefined {
  const p = phrase.toLowerCase().trim()
  return Object.entries(pagesFor(getCachedSnapshot())).find(([key, page]) => key === p || page.aliases.includes(p))?.[0]
}

/**
 * Short, unambiguous commands Jizza handles instantly on the device: opening a page, switching the
 * theme, or opening a blank create form. Anything richer goes to the server, where Jizza can reason
 * about it and fill the form in.
 */
export function matchLocalIntent(text: string): LocalIntent | null {
  const t = text.trim()
  if (t.split(/\s+/).length > 8) return null

  const create = t.match(CREATE)
  if (create) {
    const kind = create[1].toLowerCase()
    const name = create[2]
    if (kind === 'workflow' || kind === 'automation') {
      return {
        actions: [{ type: 'open_form', payload: { form: 'new_workflow', fields: name ? { name } : {} } }],
        reply: name
          ? `I've started the "${name}" workflow. Tell me what it should do and I'll fill in the steps, or add them yourself.`
          : "I've opened a new workflow. Tell me what it should do and I'll draft the steps for you.",
      }
    }
    return {
      actions: [{ type: 'open_form', payload: { form: 'new_task', fields: name ? { title: name } : {} } }],
      reply: "I've opened a new task. Tell me what and when, and I'll fill it in.",
    }
  }

  const theme = t.match(THEME)
  if (theme && THEME_VERB.test(t)) {
    const name = theme[1].toLowerCase()
    return {
      actions: [{ type: 'set_theme', payload: { theme: name } }],
      reply: `Switched to the ${name[0].toUpperCase()}${name.slice(1)} theme.`,
    }
  }

  const open = t.match(OPEN)
  if (open) {
    const page = findPage(open[1])
    if (page) {
      return {
        actions: [{ type: 'navigate', payload: { page } }],
        reply: `Here's ${PAGES[page].label}.`,
      }
    }
  }
  return null
}
