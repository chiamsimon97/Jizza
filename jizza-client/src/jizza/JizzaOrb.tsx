import { useEffect, useId, useRef, useState } from 'react'
import { useGaze } from '@/components/brand/usePointer'

export type OrbState = 'idle' | 'thinking' | 'listening'

interface JizzaOrbProps {
  state?: OrbState
  size?: number
  className?: string
  /** Follow the pointer with its eye. Off for tiny repeated orbs, e.g. beside every message. */
  tracking?: boolean
  /** A white rim, so a small orb stands out against a coloured background such as the logo tile. */
  outlined?: boolean
}

/**
 * Jizza itself: a glowing orb with one eye. It watches the pointer, blinks now and then, orbits
 * while it is thinking and sends out rings while it is listening.
 */
export function JizzaOrb({ state = 'idle', size = 40, className = '', tracking = true, outlined = false }: JizzaOrbProps) {
  const id = useId().replace(/:/g, '')
  const ref = useRef<SVGSVGElement>(null)
  const gaze = useGaze(ref, tracking && state !== 'thinking')
  const blinking = useBlink()

  // Thinking: the eye drifts up and to the side, as if working something out.
  const look = state === 'thinking' ? { x: 0.55, y: -0.6 } : gaze
  const px = 50 + look.x * 9
  const py = 48 + look.y * 8

  return (
    <svg ref={ref} viewBox="0 0 100 100" width={size} height={size} className={`jz-orb shrink-0 overflow-visible ${className}`}
      data-state={state} aria-hidden>
      <defs>
        <radialGradient id={`${id}-body`} cx="35%" cy="30%" r="75%">
          <stop offset="0%" stopColor="rgb(var(--accent-300))" />
          <stop offset="45%" stopColor="rgb(var(--brand-500))" />
          <stop offset="100%" stopColor="rgb(var(--brand-800))" />
        </radialGradient>
        <radialGradient id={`${id}-glow`} cx="50%" cy="50%" r="50%">
          <stop offset="55%" stopColor="rgb(var(--brand-500) / 0.55)" />
          <stop offset="100%" stopColor="rgb(var(--brand-500) / 0)" />
        </radialGradient>
      </defs>

      <circle className="jz-orb-glow" cx="50" cy="50" r="50" fill={`url(#${id}-glow)`} />
      {state === 'listening' && [0, 1, 2].map(i => (
        <circle key={i} className="jz-orb-ring" style={{ animationDelay: `${i * 0.45}s` }} cx="50" cy="50" r="34"
          fill="none" stroke="rgb(var(--accent-400))" strokeWidth="2" />
      ))}
      <circle cx="50" cy="50" r={outlined ? 40 : 34} fill={`url(#${id}-body)`} stroke={outlined ? 'white' : undefined} strokeWidth={outlined ? 7 : undefined} />
      <ellipse cx="38" cy="33" rx="10" ry="6" fill="white" opacity="0.35" transform="rotate(-25 38 33)" />

      {/* The eye */}
      <g className="jz-orb-eye" style={{ transform: `scaleY(${blinking ? 0.1 : 1})`, transformOrigin: `${px}px ${py}px` }}>
        <ellipse cx={px} cy={py} rx="9" ry="10" fill="white" />
        <circle cx={px + look.x * 2.5} cy={py + look.y * 2.5} r="4.2" fill="rgb(var(--brand-900))" />
        <circle cx={px + look.x * 2.5 + 1.6} cy={py + look.y * 2.5 - 1.8} r="1.3" fill="white" />
      </g>

      {state === 'thinking' && (
        <g className="jz-orb-orbit">
          {[0, 120, 240].map(a => (
            <circle key={a} cx={50 + 44 * Math.cos((a * Math.PI) / 180)} cy={50 + 44 * Math.sin((a * Math.PI) / 180)} r="4"
              fill="rgb(var(--accent-400))" />
          ))}
        </g>
      )}
    </svg>
  )
}

/** Blinks every few seconds, at a slightly random rhythm so it feels alive rather than mechanical. */
function useBlink() {
  const [blinking, setBlinking] = useState(false)
  useEffect(() => {
    let timer: number
    const schedule = () => {
      timer = window.setTimeout(() => {
        setBlinking(true)
        window.setTimeout(() => setBlinking(false), 140)
        schedule()
      }, 2500 + Math.random() * 4000)
    }
    schedule()
    return () => window.clearTimeout(timer)
  }, [])
  return blinking
}
