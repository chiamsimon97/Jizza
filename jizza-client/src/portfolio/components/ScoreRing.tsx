/** The band a score falls in, for its colour: under 50 needs work, under 75 is close. */
export function scoreBand(score: number): 'red' | 'amber' | 'green' {
  return score < 50 ? 'red' : score < 75 ? 'amber' : 'green'
}

const STROKES = { red: 'stroke-red-500', amber: 'stroke-amber-500', green: 'stroke-green-500' }

/** The Money Health score out of 100 as a ring. With no known check there is no score yet. */
export function ScoreRing({ score, size = 112 }: { score: number | null; size?: number }) {
  const r = 42
  const c = 2 * Math.PI * r
  const shown = score == null ? 0 : Math.max(0, Math.min(100, score))
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}
      role="img" aria-label={score == null ? 'No score yet' : `Money Health score ${score} out of 100`}>
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90">
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="9" className="stroke-gray-200 dark:stroke-gray-800" />
        {score != null && (
          <circle cx="50" cy="50" r={r} fill="none" strokeWidth="9" strokeLinecap="round"
            className={STROKES[scoreBand(shown)]} strokeDasharray={`${(shown / 100) * c} ${c}`} />
        )}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center" aria-hidden>
        <span className="text-3xl font-semibold tabular-nums text-gray-900 dark:text-gray-100">{score ?? '—'}</span>
        <span className="text-[11px] text-gray-500 dark:text-gray-400">{score == null ? 'no score yet' : 'out of 100'}</span>
      </div>
    </div>
  )
}
