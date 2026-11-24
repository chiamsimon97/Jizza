import { useEffect, useState } from 'react'
import { Check, Eye, EyeOff, Hand, KeyRound, Timer, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { countdown, DEVICE_HELP_TITLE, type DeviceHelp } from './deviceHelp'
import { canSeal } from './sealAnswer'

/**
 * A device's question on its card: the picture it cropped (a CAPTCHA), a box for the answer, the
 * time left before the page or step gives up, and Send / Done / Stop. What is typed goes to the
 * browser, which types it into the page; Jizza keeps none of it.
 */
export function DeviceHelpCard({ help, expiresAt, open, busy, onSend, onDone, onStop }: {
  help: DeviceHelp
  expiresAt?: string | null
  open: boolean
  busy: string | null
  onSend: (answer: string) => void
  onDone: () => void
  onStop: () => void
}) {
  const [answer, setAnswer] = useState('')
  const [show, setShow] = useState(help.kind === 'captcha')
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!open || !expiresAt) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [open, expiresAt])

  const left = open ? countdown(expiresAt, now) : null
  const soon = !!expiresAt && Date.parse(expiresAt) - now < 60_000
  const asks = !!help.answer
  const masked = asks && help.answer!.secret && !show
  const canSend = answer.trim().length > 0
  const send = () => { if (canSend && busy === null) onSend(answer.trim()) }

  return (
    <div data-testid="device-help" className="mt-3 space-y-3 rounded-lg border border-amber-200 bg-amber-50/60 p-3 dark:border-amber-900/50 dark:bg-amber-500/5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="inline-flex items-center gap-1.5 text-sm font-semibold text-gray-900 dark:text-gray-100">
          {help.kind === 'handoff' ? <Hand className="h-4 w-4" /> : <KeyRound className="h-4 w-4" />}
          {DEVICE_HELP_TITLE[help.kind]}
        </p>
        {left && (
          <span data-testid="device-help-countdown" title="Time before the page or step gives up"
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-mono text-xs font-medium tabular-nums ${
              soon ? 'bg-red-100 text-red-700 dark:bg-red-500/15 dark:text-red-300' : 'bg-white text-gray-700 dark:bg-gray-800 dark:text-gray-200'}`}>
            <Timer className="h-3 w-3" />{left} left
          </span>
        )}
      </div>

      {help.shot && (
        <img src={`/api/devices/${help.deviceId}/files/${help.shot}`} alt="What the page shows"
          className="max-h-48 max-w-full rounded-md border border-gray-200 bg-white object-contain dark:border-gray-700" />
      )}

      {help.values && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 rounded-md bg-white px-3 py-2 text-xs dark:bg-gray-900">
          {Object.entries(help.values).map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="text-gray-500 dark:text-gray-400">{k}</dt>
              <dd className="min-w-0 break-words font-medium text-gray-800 dark:text-gray-200">{v}</dd>
            </div>
          ))}
        </dl>
      )}

      {help.kind === 'handoff' && open && (
        <p className="text-xs text-gray-600 dark:text-gray-300">
          The tab is waiting for you{help.deviceName ? ` in ${help.deviceName}` : ''}. Jizza types and clicks nothing until you are done; it carries on by itself when the page shows the result.
        </p>
      )}

      {open && asks && (
        <div className="space-y-1">
          <label htmlFor={`help-${help.commandId}`} className="text-xs font-medium text-gray-700 dark:text-gray-300">Your answer</label>
          <div className="flex gap-2">
            <input id={`help-${help.commandId}`} data-testid="device-help-answer" autoFocus autoComplete="off" spellCheck={false}
              type={masked ? 'password' : 'text'} value={answer} maxLength={4000}
              onChange={e => setAnswer(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); send() } }}
              className={fieldClass} />
            {help.answer!.secret && (
              <button type="button" onClick={() => setShow(v => !v)} aria-label={show ? 'Hide what I type' : 'Show what I type'}
                className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-gray-500 hover:bg-white dark:hover:bg-gray-800">
                {show ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            )}
          </div>
          {help.answer!.secret && (
            <p className="text-[11px] text-gray-500 dark:text-gray-400">
              {!help.answer!.key
                ? 'Typed into the page only. Jizza doesn’t keep it.'
                : canSeal()
                  ? 'Locked here so only your browser can read it, then typed into the page. Jizza can’t read or keep it.'
                  : 'This page isn’t on HTTPS, so the answer can’t be locked here. It is still typed into the page only, and Jizza doesn’t keep it.'}
            </p>
          )}
        </div>
      )}

      {open && (
        <div className="flex flex-wrap items-center gap-2">
          {asks && (
            <Button size="sm" loading={busy === 'send'} disabled={!canSend || busy !== null} onClick={send}>
              <Check className="h-4 w-4" />Send
            </Button>
          )}
          <Button size="sm" variant={asks ? 'secondary' : 'primary'} loading={busy === 'done'} disabled={busy !== null} onClick={onDone}>
            {asks ? 'I did it in the browser' : 'Done'}
          </Button>
          <Button size="sm" variant="ghost" loading={busy === 'stop'} disabled={busy !== null} onClick={onStop}>
            <X className="h-4 w-4" />Stop
          </Button>
        </div>
      )}
    </div>
  )
}
