/**
 * Money as an Indian household reads it: ₹ with lakh and crore grouping (₹12,34,567), and short
 * forms in lakh and crore (₹12.3 L, ₹1.2 Cr) where space is tight.
 *
 * Grouping is done here rather than with Intl's en-IN locale, so the result is the same in every
 * browser and in tests, whatever locale data the runtime ships with.
 */

const LAKH = 100_000
const CRORE = 10_000_000

/** Digits grouped the Indian way: the last three, then pairs. "1234567" → "12,34,567". */
export function groupIndian(digits: string): string {
  if (digits.length <= 3) return digits
  const last3 = digits.slice(-3)
  const rest = digits.slice(0, -3)
  return `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}`
}

export interface MoneyOptions {
  /** Digits after the decimal point. Default 0: household figures are read in whole rupees. */
  decimals?: number
  /** Show "+" before positive amounts, for changes. */
  signed?: boolean
  /** Leave out the ₹ symbol, for table cells whose header already says ₹. */
  symbol?: boolean
}

/** A full amount: formatINR(1234567.5) → "₹12,34,568"; formatINR(-2500) → "−₹2,500". */
export function formatINR(amount: number, { decimals = 0, signed = false, symbol = true }: MoneyOptions = {}): string {
  if (!Number.isFinite(amount)) return '—'
  const fixed = Math.abs(amount).toFixed(decimals)
  const [whole, fraction] = fixed.split('.')
  const body = `${symbol ? '₹' : ''}${groupIndian(whole)}${fraction ? `.${fraction}` : ''}`
  const isZero = Number(fixed) === 0
  if (amount < 0 && !isZero) return `−${body}`
  if (signed && amount > 0 && !isZero) return `+${body}`
  return body
}

/**
 * A short amount for tiles and chart labels: below a lakh it is the full amount; from a lakh it is
 * "₹12.3 L"; from a crore "₹1.25 Cr". One decimal for lakh, two for crore, trailing zeros dropped.
 */
export function formatINRShort(amount: number, { signed = false, symbol = true }: Omit<MoneyOptions, 'decimals'> = {}): string {
  if (!Number.isFinite(amount)) return '—'
  const abs = Math.abs(amount)
  let body: string
  if (abs >= CRORE) body = `${trim((abs / CRORE).toFixed(2))} Cr`
  else if (abs >= LAKH) body = `${trim((abs / LAKH).toFixed(1))} L`
  else return formatINR(amount, { signed, symbol })
  body = `${symbol ? '₹' : ''}${body}`
  if (amount < 0) return `−${body}`
  return signed ? `+${body}` : body
}

/** A share of a whole: formatPercent(0.153) → "15.3%". */
export function formatPercent(fraction: number, decimals = 1): string {
  if (!Number.isFinite(fraction)) return '—'
  return `${trim((fraction * 100).toFixed(decimals))}%`
}

function trim(n: string): string {
  return n.includes('.') ? n.replace(/\.?0+$/, '') : n
}
