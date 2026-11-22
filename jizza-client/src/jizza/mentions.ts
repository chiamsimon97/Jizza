/** An agent the user can name with "@" so it answers the message itself. */
export interface Mentionable {
  id: string
  key: string
  name: string
  enabled: boolean
  orchestrator: boolean
  status?: string
}

/** Specialists that can answer: switched on, not drafts or archived, and not Jizza itself. */
export function mentionable<T extends Mentionable>(agents: T[]): T[] {
  return agents.filter(a => a.enabled && !a.orchestrator && (a.status === undefined || a.status === 'active'))
}

/** What is typed after an "@" that starts the message, while the user is still picking who answers. */
export function mentionQuery(input: string): string | null {
  const m = /^@([^\s@]*)$/.exec(input)
  return m ? m[1] : null
}

/** The agents whose name or key has what was typed, those that start with it first. */
export function matchAgents<T extends Mentionable>(agents: T[], typed: string): T[] {
  const q = typed.toLowerCase()
  const all = mentionable(agents)
  if (!q) return all
  const starts = (a: T) => a.name.toLowerCase().startsWith(q) || a.key.toLowerCase().startsWith(q)
  return [...all.filter(starts), ...all.filter(a => !starts(a) && a.name.toLowerCase().includes(q))]
}

/**
 * The agent a message starting with "@Name" (or "@key") is for, or undefined. The longest name that
 * fits wins, so "@Kai Tax" is not taken for "@Kai" when both exist.
 */
export function mentionOf<T extends Mentionable>(text: string, agents: T[]): T | undefined {
  if (!text.startsWith('@')) return undefined
  const rest = text.slice(1).toLowerCase()
  const fits = (label: string) => {
    const l = label.toLowerCase()
    return rest.startsWith(l) && (rest.length === l.length || /[\s,.:;!?]/.test(rest[l.length]))
  }
  return mentionable(agents)
    .flatMap(a => [{ a, len: a.name.length, ok: fits(a.name) }, { a, len: a.key.length, ok: fits(a.key) }])
    .filter(x => x.ok)
    .sort((x, y) => y.len - x.len)[0]?.a
}
