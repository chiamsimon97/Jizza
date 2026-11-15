import type { NotificationDto } from '@/types'

/**
 * A card raised because a browser or app needs the person mid-step: a CAPTCHA to solve, a
 * one-time code, an Ask step's question, a hand-over (a payment). Answering it goes back to the
 * device, which types the answer into the page; the answer is never kept. Pure, for tests.
 */
export type DeviceHelpKind = 'captcha' | 'otp' | 'verification' | 'login' | 'ask' | 'handoff' | 'other'

export interface DeviceHelp {
  deviceId: string
  commandId: string
  kind: DeviceHelpKind
  deviceName?: string
  /** A picture the device cropped from the page (a CAPTCHA), as a device file id. */
  shot?: string
  /** The card asks for an answer; `secret`: it is never shown again or kept. */
  answer?: { secret: boolean; required: boolean }
  /** Read-only values read from the page (a total). */
  values?: Record<string, string>
}

const KINDS: DeviceHelpKind[] = ['captcha', 'otp', 'verification', 'login', 'ask', 'handoff', 'other']

export function deviceHelpOf(n: NotificationDto): DeviceHelp | null {
  const raw = n.context?.deviceHelp
  if (!raw || typeof raw !== 'object') return null
  const h = raw as Record<string, unknown>
  if (typeof h.deviceId !== 'string' || typeof h.commandId !== 'string') return null
  const kind = KINDS.includes(h.kind as DeviceHelpKind) ? (h.kind as DeviceHelpKind) : 'other'
  const answer = h.answer && typeof h.answer === 'object' ? h.answer as Record<string, unknown> : null
  const values: Record<string, string> = {}
  if (h.values && typeof h.values === 'object') {
    for (const [k, v] of Object.entries(h.values as Record<string, unknown>)) if (typeof v === 'string') values[k] = v
  }
  return {
    deviceId: h.deviceId,
    commandId: h.commandId,
    kind,
    ...(typeof h.deviceName === 'string' ? { deviceName: h.deviceName } : {}),
    ...(typeof h.shot === 'string' && /^[0-9a-f-]{36}$/i.test(h.shot) ? { shot: h.shot } : {}),
    ...(answer ? { answer: { secret: answer.secret !== false, required: answer.required !== false } } : {}),
    ...(Object.keys(values).length ? { values } : {}),
  }
}

/** The card's heading for each kind. */
export const DEVICE_HELP_TITLE: Record<DeviceHelpKind, string> = {
  captcha: 'Solve the CAPTCHA',
  otp: 'Enter the one-time code',
  verification: 'Verify it’s you',
  login: 'Sign in',
  ask: 'Jizza has a question',
  handoff: 'Your turn in the browser',
  other: 'Your browser needs you',
}

/** Time left as m:ss (or h:mm:ss), never negative. */
export function countdown(expiresAt: string | null | undefined, now: number): string | null {
  if (!expiresAt) return null
  const ms = Math.max(0, Date.parse(expiresAt) - now)
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (v: number) => String(v).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}
