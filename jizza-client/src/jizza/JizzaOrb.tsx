import { useId } from 'react'
import { Cheeks, Eyes, Mascot, Mouth } from '@/components/brand/Mascot'

export type OrbState = 'idle' | 'thinking' | 'listening'

interface JizzaOrbProps {
  state?: OrbState
  size?: number
  className?: string
  /** Follow the pointer with its eyes. Off for small repeated copies, e.g. beside every message. */
  tracking?: boolean
  /** A white rim, so it stands out against a coloured background. */
  outlined?: boolean
  /** Paused: Jizza naps. */
  asleep?: boolean
}

const QUIPS = [
  'Boop! What can I do for you?',
  'Hey, that tickles!',
  'Ready when you are, boss.',
  'Need a workflow? Just say the word.',
  "I'm awake! I'm awake!",
  'Poke me again and I start a task.',
  'At your service. Mostly.',
]

/**
 * Jizza itself: a bouncy gumdrop with an antenna. It watches the pointer, blinks, grins and waves
 * when you hover, jumps when you poke it, and lights up its antenna while it thinks.
 */
export function JizzaOrb({ state = 'idle', size = 40, className = '', tracking = true, outlined = false, asleep = false }: JizzaOrbProps) {
  const id = useId().replace(/:/g, '')
  const thinking = state === 'thinking'

  return (
    <Mascot size={size} state={state} quips={QUIPS} tracking={tracking} asleep={asleep} className={`jz-orb ${className}`}
      look={thinking ? { x: 0.6, y: -0.8 } : undefined}
      mood={thinking ? 'focused' : state === 'listening' ? 'surprised' : undefined}>
      {face => (
        <>
          <defs>
            <radialGradient id={`${id}-b`} cx="35%" cy="28%" r="80%">
              <stop offset="0%" stopColor="rgb(var(--accent-300))" />
              <stop offset="50%" stopColor="rgb(var(--brand-500))" />
              <stop offset="100%" stopColor="rgb(var(--brand-800))" />
            </radialGradient>
          </defs>

          {state === 'listening' && [0, 1, 2].map(i => (
            <circle key={i} className="jz-orb-ring" style={{ animationDelay: `${i * 0.45}s` }} cx="50" cy="58" r="36"
              fill="none" stroke="rgb(var(--accent-400))" strokeWidth="2.5" />
          ))}

          {/* Antenna, with a bulb that flashes while Jizza thinks */}
          <g className="m-antenna">
            <path d="M50 26 Q47 16 54 8" fill="none" stroke={outlined ? 'white' : 'rgb(var(--brand-700))'} strokeWidth="3.5" strokeLinecap="round" />
            <circle className="m-bulb" cx="54" cy="7" r="6" fill="rgb(var(--accent-400))" stroke={outlined ? 'white' : 'rgb(var(--brand-700))'} strokeWidth="2" />
          </g>

          {/* Arms: the right one waves on hover */}
          <g className="m-arm-l"><ellipse cx="13" cy="64" rx="7" ry="10" fill="rgb(var(--brand-600))" transform="rotate(25 13 64)" /></g>
          <g className="m-arm-r"><ellipse cx="87" cy="64" rx="7" ry="10" fill="rgb(var(--brand-600))" transform="rotate(-25 87 64)" /></g>

          <g className="m-squish">
            <path d="M50 24 C75 24 89 40 89 60 C89 80 72 92 50 92 C28 92 11 80 11 60 C11 40 25 24 50 24Z"
              fill={`url(#${id}-b)`} stroke={outlined ? 'white' : undefined} strokeWidth={outlined ? 6 : undefined} />
            <ellipse cx="34" cy="38" rx="11" ry="6" fill="white" opacity="0.35" transform="rotate(-25 34 38)" />
            <Eyes x1={37} x2={63} y={56} r={11} face={face} />
            <Cheeks x1={24} x2={76} y={70} r={6} />
            <Mouth x={50} y={73} w={14} face={face} />
          </g>

          {/* A thought bubble with dots that tick while Jizza works something out */}
          {thinking && (
            <g className="m-thought">
              <circle cx="80" cy="22" r="2.5" fill="white" stroke="rgb(var(--brand-300))" />
              <ellipse cx="94" cy="8" rx="15" ry="10" fill="white" stroke="rgb(var(--brand-300))" strokeWidth="1.5" />
              {[0, 1, 2].map(i => (
                <circle key={i} className="m-dot" style={{ animationDelay: `${i * 0.18}s` }} cx={87 + i * 7} cy="8" r="2.3" fill="rgb(var(--brand-500))" />
              ))}
            </g>
          )}
        </>
      )}
    </Mascot>
  )
}
