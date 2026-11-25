import { createContext, useContext } from 'react'
import { formatINR, formatINRShort } from '@/lib/money'

/** Whether amounts are masked on screen ("Hide amounts" in the Portfolio bar). */
export const HideAmountsContext = createContext(false)

export const HIDDEN_AMOUNT = '₹ ••••'

/** An amount in Indian format, or a mask while "Hide amounts" is on. */
export function Money({ amount, short = false, signed = false }: { amount: number; short?: boolean; signed?: boolean }) {
  const hidden = useContext(HideAmountsContext)
  if (hidden) return <span aria-label="Amount hidden">{HIDDEN_AMOUNT}</span>
  return <span className="tabular-nums">{short ? formatINRShort(amount, { signed }) : formatINR(amount, { signed })}</span>
}
