interface JizzaOrbProps {
  state?: 'idle' | 'thinking' | 'listening'
  size?: number
  className?: string
}

/** Jizza's presence: a glowing orb that breathes when idle and spins up while working or listening. */
export function JizzaOrb({ state = 'idle', size = 40, className = '' }: JizzaOrbProps) {
  const active = state !== 'idle'
  return (
    <span className={`relative inline-grid shrink-0 place-items-center ${className}`} style={{ width: size, height: size }} aria-hidden>
      <span className={`absolute inset-0 rounded-full brand-gradient opacity-40 blur-md ${active ? 'animate-orb-pulse-fast' : 'animate-orb-pulse'}`} />
      <span className={`absolute inset-0 rounded-full brand-gradient ${state === 'thinking' ? 'animate-spin-slow' : ''}`}
        style={{ maskImage: 'radial-gradient(circle, transparent 52%, black 54%)', WebkitMaskImage: 'radial-gradient(circle, transparent 52%, black 54%)' }} />
      <span className="absolute rounded-full brand-gradient shadow-glow" style={{ inset: size * 0.16 }} />
      <span className="absolute rounded-full bg-white/70 blur-[1px]" style={{ width: size * 0.16, height: size * 0.16, top: size * 0.28, left: size * 0.3 }} />
      {state === 'listening' && (
        <span className="absolute inset-[-6px] rounded-full border-2 border-brand-400/60 animate-ping" />
      )}
    </span>
  )
}
