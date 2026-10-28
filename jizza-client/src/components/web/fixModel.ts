import type { FixCandidate } from '@/api/web'

/** What a browser's error kind means, from the person's side. */
const KIND_WORDS: Record<string, string> = {
  element_not_found: 'The element could not be found on the page. The page may have changed.',
  element_ambiguous: 'More than one element matched, so the browser did not guess.',
  not_actionable: 'The element was there but could not be used: hidden, disabled or covered.',
  navigation_failed: 'The page did not load.',
  no_session: 'The browser window for this run was closed.',
  site_not_allowed: 'The browser agent is not allowed to open this site.',
  frame_not_found: 'The frame holding the element was not found.',
  secret_missing: 'The saved login this step uses is missing from the browser.',
  bad_input: 'The step’s settings were not right for this action.',
  page_crashed: 'The page crashed.',
  cancelled_by_person: 'Someone stopped it in the browser.',
}

export function errorKindWords(kind?: string | null): string {
  if (!kind) return 'The browser could not do it.'
  return KIND_WORDS[kind] ?? `The browser could not do it (${kind.replace(/_/g, ' ')}).`
}

/** Where the builder opens on a step, which is where a fix is reviewed. */
export function reviewFixLink(workflowId: string, nodeId: string): string {
  return `/workflows?open=${encodeURIComponent(workflowId)}&node=${encodeURIComponent(nodeId)}`
}

/** A short line for one suggestion: its label or text, and how it would be found. */
export function describeCandidate(c: FixCandidate): { title: string; how: string } {
  const title = (c.element.label as string | undefined) || c.text || 'Unnamed element'
  const first = c.element.strategies?.[0] ?? {}
  const by = String(first.by ?? '')
  const how = by === 'role' ? `${first.role ?? 'element'} named “${first.name ?? ''}”`
    : by === 'testid' ? `test id “${first.value ?? ''}”`
    : by ? `${by} “${String(first.value ?? '')}”` : 'no locator'
  return { title, how }
}

/** A score from 0 to 1 as a percentage, or nothing when the browser gave none. */
export function scoreText(score?: number): string | null {
  return typeof score === 'number' ? `${Math.round(score * 100)}% match` : null
}
