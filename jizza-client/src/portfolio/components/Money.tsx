import { createContext, useContext } from 'react'
import { formatINR, formatINRShort } from '@/lib/money'
import { useCountUp } from './motion'

/** Whether amounts are masked on screen ("Hide amounts" in the Portfolio bar). */
export const HideAmountsContext = createContext(false)

export const HIDDEN_AMOUNT = '₹ ••••'

/** An amount in Indian format, or a mask while "Hide amounts" is on. */
export function Money({ amount, short = false, signed = false, animate = false }: { amount: number; short?: boolean; signed?: boolean; animate?: boolean }) {
  const hidden = useContext(HideAmountsContext)
  if (hidden) return <span aria-label="Amount hidden">{HIDDEN_AMOUNT}</span>
  if (animate) return <CountingMoney amount={amount} short={short} signed={signed} />
  return <span className="tabular-nums">{short ? formatINRShort(amount, { signed }) : formatINR(amount, { signed })}</span>
}

/** Counts up to the amount on first show; screen readers get the final figure only. */
function CountingMoney({ amount, short, signed }: { amount: number; short: boolean; signed: boolean }) {
  const shown = useCountUp(amount)
  const fmt = (n: number) => (short ? formatINRShort(n, { signed }) : formatINR(Math.round(n), { signed }))
  const final = fmt(amount)
  const now = shown === amount ? final : fmt(shown)
  return <span className="tabular-nums" aria-label={now === final ? undefined : final}>{now}</span>
}
