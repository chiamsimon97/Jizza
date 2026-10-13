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
 * The Jizza mark is Jizza itself: the little antenna'd character that watches you, waves on
 * hover and lights up while it works. Hovering the logo also sends a wave through the wordmark.
 */
export function JizzaLogo({ size = 32, markOnly = false, state = 'idle', className = '', inverted = false }: JizzaLogoProps) {
  return (
    <span className={`group/logo inline-flex select-none items-center gap-2 ${className}`}>
      <JizzaOrb size={size} state={state} outlined={inverted} />
      {!markOnly && (
        <span className={`jz-word font-extrabold tracking-tight ${inverted ? 'text-white' : 'brand-gradient-text'}`} style={{ fontSize: size * 0.6 }}>
          {'Jizza'.split('').map((ch, i) => <span key={i} style={{ animationDelay: `${i * 60}ms` }}>{ch}</span>)}
        </span>
      )}
    </span>
  )
}
