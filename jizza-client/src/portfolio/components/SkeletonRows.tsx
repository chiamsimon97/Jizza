/** Loading placeholder: grey rows where the figures will be, never a spinner over old numbers. */
export function SkeletonRows({ rows = 3, className = '' }: { rows?: number; className?: string }) {
  return (
    <div className={`flex flex-col gap-2 ${className}`} role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="h-10 animate-pulse rounded-lg bg-gray-100 dark:bg-gray-800" style={{ width: `${100 - (i % 3) * 12}%` }} />
      ))}
    </div>
  )
}
