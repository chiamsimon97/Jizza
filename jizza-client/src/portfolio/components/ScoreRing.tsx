import { useId } from 'react'
import { useCountUp, useMounted } from './motion'

/** The band a score falls in, for its colour: under 50 needs work, under 75 is close. */
export function scoreBand(score: number): 'red' | 'amber' | 'green' {
  return score < 50 ? 'red' : score < 75 ? 'amber' : 'green'
}

/** Two stops per band, so the ring reads as a gradient rather than a flat line. */
const GRADIENTS = {
  red: ['#fb7185', '#e11d48'],
  amber: ['#fbbf24', '#f97316'],
  green: ['#34d399', '#059669'],
}

/**
 * The Money Health score out of 100 as a ring. It sweeps in and the number counts up on first
 * show (not with reduced motion). With no known check there is no score yet.
 */
export function ScoreRing({ score, size = 112 }: { score: number | null; size?: number }) {
  const r = 42
  const c = 2 * Math.PI * r
  const shown = score == null ? 0 : Math.max(0, Math.min(100, score))
  const mounted = useMounted()
  const counted = useCountUp(shown)
  const gid = useId().replace(/:/g, '')
  const band = scoreBand(shown)
  const [from, to] = GRADIENTS[band]
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}
      role="img" aria-label={score == null ? 'No score yet' : `Money Health score ${score} out of 100`}>
      {score != null && (
        <div aria-hidden className="absolute inset-3 rounded-full opacity-[0.18] blur-2xl" style={{ background: to }} />
      )}
      <svg viewBox="0 0 100 100" className="relative h-full w-full -rotate-90">
        <defs>
          <linearGradient id={`ring-${gid}`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={from} />
            <stop offset="100%" stopColor={to} />
          </linearGradient>
        </defs>
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="9" className="stroke-gray-200/80 dark:stroke-gray-800" />
        {score != null && (
          <circle cx="50" cy="50" r={r} fill="none" strokeWidth="9" strokeLinecap="round" stroke={`url(#ring-${gid})`}
            data-band={band}
            strokeDasharray={`${((mounted ? shown : 0) / 100) * c} ${c}`}
            style={{ transition: 'stroke-dasharray 1.1s cubic-bezier(0.16, 1, 0.3, 1)' }} />
        )}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center" aria-hidden>
        <span className="text-3xl font-bold tabular-nums tracking-tight text-gray-900 dark:text-gray-100">
          {score == null ? '—' : Math.round(counted)}
        </span>
        <span className="text-[11px] text-gray-500 dark:text-gray-400">{score == null ? 'no score yet' : 'out of 100'}</span>
      </div>
    </div>
  )
}
