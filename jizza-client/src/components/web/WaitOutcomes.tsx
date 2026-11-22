import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import type { WaitOutcome } from '@/api/web'

const box = 'rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600'

/** What an outcome looks for, in words. */
export function lookingFor(o: WaitOutcome): string {
  if (o.text !== undefined) return `${o.gone ? 'the text gone' : 'the text'} "${o.text}"`
  if (o.urlContains !== undefined) return `the address with "${o.urlContains}"`
  const label = typeof o.element?.label === 'string' ? o.element.label : 'the element you pointed at'
  return o.gone ? `${label} gone` : label
}

/**
 * "Wait until the page answers": each outcome the step waits for, and what the run does when the page
 * shows it: carry on, or stop with a message (the site's "No trains found"). A text outcome can be
 * added here; an element one is pointed at in the browser while recording.
 */
export function WaitOutcomes({ outcomes, busy, onChange }: {
  outcomes: WaitOutcome[]
  busy?: boolean
  onChange: (next: WaitOutcome[]) => void
}) {
  const [adding, setAdding] = useState<{ name: string; text: string } | null>(null)
  const [messages, setMessages] = useState<string[]>(() => outcomes.map(o => o.message ?? ''))
  useEffect(() => setMessages(outcomes.map(o => o.message ?? '')), [outcomes])
  const names = new Set(outcomes.map(o => o.name))
  const change = (i: number, patch: Partial<WaitOutcome>) =>
    onChange(outcomes.map((o, n) => {
      if (n !== i) return o
      const next = { ...o, ...patch }
      if (next.then !== 'stop') delete next.message
      return next
    }))
  return (
    <div className="mt-1 space-y-1" data-testid="wait-outcomes">
      <ul className="space-y-1">
        {outcomes.map((o, i) => (
          <li key={o.name} className="flex flex-wrap items-center gap-1.5 text-xs text-gray-700 dark:text-gray-300">
            <span className="font-medium">{o.name}</span>
            <span className="text-gray-500 dark:text-gray-400">({lookingFor(o)})</span>
            <select aria-label={`When "${o.name}" shows`} value={o.then === 'stop' ? 'stop' : 'continue'} disabled={busy}
              onChange={e => change(i, { then: e.target.value as 'continue' | 'stop' })} className={box}>
              <option value="continue">Carry on</option>
              <option value="stop">Stop the run</option>
            </select>
            {o.then === 'stop' && (
              <input aria-label={`Message for "${o.name}"`} placeholder="Why, e.g. No trains that day" disabled={busy}
                value={messages[i] ?? ''} maxLength={300}
                onChange={e => setMessages(m => m.map((x, n) => (n === i ? e.target.value : x)))}
                onBlur={() => { const v = (messages[i] ?? '').trim(); if (v !== (o.message ?? '')) change(i, { message: v || undefined }) }}
                className={`${box} w-56`} />
            )}
            {outcomes.length > 1 && (
              <button type="button" aria-label={`Remove "${o.name}"`} disabled={busy} className="opacity-50 hover:opacity-100"
                onClick={() => onChange(outcomes.filter((_, n) => n !== i))}><X className="h-3 w-3" /></button>
            )}
          </li>
        ))}
      </ul>
      {adding ? (
        <form className="flex flex-wrap items-center gap-1.5" aria-label="Add an outcome"
          onSubmit={e => {
            e.preventDefault()
            const name = adding.name.trim()
            const text = adding.text.trim()
            if (!name || !text || names.has(name) || outcomes.length >= 8) return
            onChange([...outcomes, { name, text, then: 'continue' }])
            setAdding(null)
          }}>
          <input aria-label="Outcome name" placeholder="Name, e.g. Sold out" value={adding.name} maxLength={60}
            onChange={e => setAdding({ ...adding, name: e.target.value })} className={`${box} w-36`} />
          <input aria-label="Text the page shows" placeholder="Text the page shows" value={adding.text} maxLength={500}
            onChange={e => setAdding({ ...adding, text: e.target.value })} className={`${box} w-48`} />
          <button type="submit" disabled={busy || !adding.name.trim() || !adding.text.trim() || names.has(adding.name.trim())}
            className="text-xs font-medium text-brand-600 disabled:opacity-50 dark:text-brand-300">Add</button>
          <button type="button" onClick={() => setAdding(null)} className="text-xs text-gray-500">Cancel</button>
        </form>
      ) : outcomes.length < 8 && (
        <button type="button" disabled={busy} onClick={() => setAdding({ name: '', text: '' })}
          className="text-xs font-medium text-brand-600 hover:underline disabled:opacity-50 dark:text-brand-300">
          Add what else can happen (a message the page shows)
        </button>
      )}
    </div>
  )
}
