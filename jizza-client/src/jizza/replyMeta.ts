import type { AssistantMessage } from './assistantStore'

/**
 * Where the older messages stop and the summary Jizza keeps of them starts: before the first
 * message sent at or after {@code through}, or at the top when none of the loaded messages says
 * when it was sent. -1 when every message predates the summary's end (nothing after it yet).
 */
export function earlierMarker(messages: AssistantMessage[], through: string) {
  const end = Date.parse(through)
  if (!messages.some(m => m.createdAt)) return messages.length > 0 ? 0 : -1
  const i = messages.findIndex(m => m.createdAt && Date.parse(m.createdAt) >= end)
  if (i >= 0) return i
  // Sent this session, after the history loaded: the summary ends before them.
  const firstNew = messages.findIndex(m => !m.createdAt)
  return firstNew
}

/** How long a job took, the way a person says it: "8s", "2m 5s", "1h 4m". */
export function took(startedAt?: string, finishedAt?: string) {
  if (!startedAt || !finishedAt) return undefined
  const seconds = Math.max(0, Math.round((Date.parse(finishedAt) - Date.parse(startedAt)) / 1000))
  if (Number.isNaN(seconds)) return undefined
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m${seconds % 60 ? ` ${seconds % 60}s` : ''}`
  return `${Math.floor(seconds / 3600)}h${Math.floor((seconds % 3600) / 60) ? ` ${Math.floor((seconds % 3600) / 60)}m` : ''}`
}
