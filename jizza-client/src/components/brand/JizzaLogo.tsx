import { JizzaOrb, type OrbState } from '@/jizza/JizzaOrb'

interface JizzaLogoProps {
  size?: number
  /** Show only the mark, without the wordmark. */
  markOnly?: boolean
  state?: OrbState
  className?: string
  /** For a light-on-colour placement, e.g. the gradient panel on sign-in. */
  inverted?: boolean
}

/**
 * The Jizza mark: a "J" whose dot is Jizza itself. The assistant lives in the logo, so the logo
 * watches you, blinks, and spins up while Jizza is working.
 */
export function JizzaLogo({ size = 32, markOnly = false, state = 'idle', className = '', inverted = false }: JizzaLogoProps) {
  return (
    <span className={`group/logo inline-flex select-none items-center gap-2.5 ${className}`}>
      <span className="relative inline-block shrink-0" style={{ width: size, height: size }}>
        <svg viewBox="0 0 64 64" width={size} height={size} aria-hidden className="transition-transform duration-300 group-hover/logo:-rotate-6">
          <defs>
            <linearGradient id="jz-tile" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor="rgb(var(--brand-500))" />
              <stop offset="100%" stopColor="rgb(var(--accent-500))" />
            </linearGradient>
          </defs>
          <rect width="64" height="64" rx="16" fill={inverted ? 'rgb(255 255 255 / 0.18)' : 'url(#jz-tile)'} />
          {/* The J's stem and hook */}
          <path d="M38 24 V40 a10 10 0 0 1 -10 10 h-1 a10 10 0 0 1 -9 -6" fill="none" stroke="white" strokeWidth="7"
            strokeLinecap="round" strokeLinejoin="round" className="jz-logo-stroke" />
        </svg>
        {/* The dot of the J is Jizza */}
        <span className="absolute transition-transform duration-300 group-hover/logo:-translate-y-0.5 group-hover/logo:scale-110"
          style={{ left: size * 0.4, top: size * -0.14, width: size * 0.46, height: size * 0.46 }}>
          <JizzaOrb size={size * 0.46} state={state} outlined className="drop-shadow" />
        </span>
      </span>
      {!markOnly && (
        <span className={`font-bold tracking-tight ${inverted ? 'text-white' : 'brand-gradient-text'}`} style={{ fontSize: size * 0.56 }}>
          Jizza
        </span>
      )}
    </span>
  )
}
