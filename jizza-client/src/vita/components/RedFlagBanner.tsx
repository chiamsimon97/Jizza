import { useState } from 'react'
import { Phone, Siren, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import type { RedFlag } from '@/types/vita'
import { FigureLabel, NeedsVerification } from './FigureLabel'

/**
 * A red flag, shown first and full width (spec 7.19, 8.2). Two levels with different weight and
 * words: "Call now" (emergency) and "Act today" (urgent). Every sentence comes from the server's help
 * block; the client never writes clinical wording of its own and never names a diagnosis. The banner
 * stays until someone acknowledges it.
 */
export function RedFlagBanner({ flag, onAcknowledge, onRaise, onOpenCard, acknowledging = false }: {
  flag: RedFlag
  onAcknowledge?: () => void
  /** "Yes, one of these now": the server raises Act today to Call now. */
  onRaise?: () => void
  onOpenCard?: () => void
  acknowledging?: boolean
}) {
  const [signs, setSigns] = useState<string[]>([])
  const number = flag.emergencyNumbers?.emergency
  const ack = onAcknowledge && <Button size="sm" variant="secondary" loading={acknowledging} onClick={onAcknowledge}>Acknowledge</Button>

  if (flag.level === 'emergency') {
    return (
      <section role="alert" aria-label="Call now"
        className="flex gap-3 rounded-2xl border-2 border-red-600 bg-red-50 p-4 text-red-950 shadow-sm dark:border-red-500 dark:bg-red-950/50 dark:text-red-50">
        <Siren className="mt-0.5 h-6 w-6 shrink-0 text-red-700 dark:text-red-300" aria-hidden />
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <h3 className="text-lg font-bold">Call now{number ? <> · <span className="font-mono">{number}</span></> : null}</h3>
          <p className="text-sm">
            {flag.personName && <b>{flag.personName}</b>}{flag.reading ? ` · ${flag.reading}` : ''}{flag.personName || flag.reading ? '. ' : ''}{flag.wording}
          </p>
          {flag.advice && <p className="text-sm font-semibold">{flag.advice}</p>}
          {flag.preferredHospital && (
            <p className="text-sm">Preferred hospital: <b>{flag.preferredHospital}</b>{flag.preferredHospitalPhone && <> · <span className="font-mono">{flag.preferredHospitalPhone}</span></>}</p>
          )}
          <div className="flex flex-wrap gap-2">
            {number && (
              <a href={`tel:${number}`} className="inline-flex items-center gap-1.5 rounded-lg bg-red-700 px-4 py-2 text-sm font-semibold text-white hover:bg-red-800 dark:bg-red-600 dark:hover:bg-red-500">
                <Phone className="h-4 w-4" aria-hidden />Call {number}
              </a>
            )}
            {onOpenCard && <Button size="sm" variant="secondary" onClick={onOpenCard}>Open {flag.personName ? `${flag.personName}’s` : 'the'} emergency card</Button>}
            {ack}
          </div>
          {flag.emergencyNumbersNeedVerification && <p className="flex flex-wrap items-center gap-1 text-xs">Emergency number from Settings · Vita default <NeedsVerification /></p>}
        </div>
      </section>
    )
  }

  if (flag.level === 'self_harm') {
    return (
      <section role="alert" aria-label="Support"
        className="flex flex-col gap-2 rounded-2xl border-2 border-[rgb(var(--agent-health))] bg-white p-4 dark:bg-gray-900">
        {flag.helpline?.reply && <p className="text-sm text-gray-800 dark:text-gray-100">{flag.helpline.reply}</p>}
        <div className="flex flex-wrap gap-2">
          {flag.helpline?.helpline && (
            <a href={`tel:${flag.helpline.helpline}`} className="inline-flex items-center gap-1.5 rounded-lg bg-gray-900 px-3 py-1.5 text-sm font-medium text-white dark:bg-gray-100 dark:text-gray-900">
              <Phone className="h-4 w-4" aria-hidden />{flag.helpline.helplineName ?? 'Helpline'} {flag.helpline.helpline}
            </a>
          )}
          {number && <a href={`tel:${number}`} className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-700">Emergency {number}</a>}
        </div>
      </section>
    )
  }

  return (
    <section role="alert" aria-label="Act today"
      className="flex gap-3 rounded-2xl border-2 border-[rgb(var(--agent-health))] bg-amber-50 p-4 text-gray-900 shadow-sm dark:bg-amber-950/30 dark:text-gray-50">
      <TriangleAlert className="mt-0.5 h-5 w-5 shrink-0 text-amber-700 dark:text-amber-300" aria-hidden />
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <h3 className="text-base font-semibold">Act today{flag.personName ? ` · ${flag.personName}` : ''}{flag.reading ? ` · ${flag.reading}` : ''}</h3>
        {flag.wording && <p className="text-sm">{flag.wording}</p>}
        {flag.advice && <p className="text-sm font-semibold">{flag.advice}</p>}
        {flag.rule && <FigureLabel state={flag.rule.state} label={flag.rule.label} />}
        {flag.rule?.note && <p className="text-xs text-gray-600 dark:text-gray-300">{flag.rule.note}</p>}
        {flag.question && flag.signs?.length ? (
          <div className="flex flex-col gap-1">
            <p className="text-sm font-semibold">{flag.question}</p>
            <div className="flex flex-wrap gap-1.5">
              {flag.signs.map(s => {
                const on = signs.includes(s)
                return (
                  <button key={s} type="button" aria-pressed={on} onClick={() => setSigns(v => on ? v.filter(x => x !== s) : [...v, s])}
                    className={`rounded-full border px-2.5 py-1 text-xs capitalize ${on ? 'border-red-600 bg-red-100 font-medium text-red-900 dark:bg-red-900/40 dark:text-red-100' : 'border-gray-300 bg-white text-gray-700 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200'}`}>
                    {s}
                  </button>
                )
              })}
            </div>
          </div>
        ) : null}
        {flag.doctorName && <p className="text-sm">{flag.doctorName}{flag.doctorPhone && <> · <a className="font-mono underline" href={`tel:${flag.doctorPhone}`}>{flag.doctorPhone}</a></>}</p>}
        <div className="flex flex-wrap gap-2">
          {onRaise && <Button size="sm" variant="danger" disabled={!signs.length} onClick={onRaise}>Yes, one of these now</Button>}
          {ack}
        </div>
      </div>
    </section>
  )
}
