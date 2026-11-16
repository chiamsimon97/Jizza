import { useState } from 'react'
import type { PollRule } from '@/api/web'

const box = 'rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600'

/** The rule the boxes ask for: null when looking once, else every so often for a while. */
export function pollOf(on: boolean, every: string, minutes: string): PollRule | null {
  if (!on) return null
  const everySec = Math.max(15, Math.round(Number(every) || 60))
  const forMinutes = Math.min(7 * 24 * 60, Math.max(1, Math.round(Number(minutes) || 30)))
  return { everySec, forMinutes }
}

/**
 * What "Wait for what happens" does when nothing it waits for shows: give up, or keep looking on
 * a freshly loaded page every so often (a slot that opens, a price that drops). Sites are never
 * asked more than once every 15 seconds.
 */
export function KeepLookingField({ poll, busy, onChange }: {
  poll?: PollRule
  busy?: boolean
  onChange: (next: PollRule | null) => void
}) {
  const [on, setOn] = useState(!!poll)
  const [every, setEvery] = useState(String(poll?.everySec ?? 60))
  const [minutes, setMinutes] = useState(String(poll?.forMinutes ?? 30))
  const send = (nextOn = on) => {
    const next = pollOf(nextOn, every, minutes)
    if (JSON.stringify(next) !== JSON.stringify(poll ?? null)) onChange(next)
  }
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
      <label className="inline-flex items-center gap-1">
        <input type="checkbox" checked={on} disabled={busy} aria-label="Keep looking"
          onChange={e => { setOn(e.target.checked); send(e.target.checked) }} />
        If none shows, keep looking
      </label>
      {on && (
        <>
          every
          <input aria-label="Seconds between looks" value={every} disabled={busy} inputMode="numeric"
            onChange={e => setEvery(e.target.value.replace(/[^0-9]/g, ''))} onBlur={() => send()} className={`${box} w-14`} />
          seconds for
          <input aria-label="Minutes to keep looking" value={minutes} disabled={busy} inputMode="numeric"
            onChange={e => setMinutes(e.target.value.replace(/[^0-9]/g, ''))} onBlur={() => send()} className={`${box} w-14`} />
          minutes
        </>
      )}
    </span>
  )
}
