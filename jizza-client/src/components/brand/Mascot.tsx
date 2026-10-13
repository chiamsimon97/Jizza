import { type ReactNode, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useGaze } from './usePointer'

/** How a mascot's face reads right now. */
export type Mood = 'normal' | 'happy' | 'surprised' | 'sleep' | 'focused' | 'excited'

export interface FaceCtx {
  gaze: { x: number; y: number }
  blink: boolean
  mood: Mood
}

interface MascotProps {
  size: number
  /** Drives the CSS: idle, working, thinking, listening or off. */
  state: string
  /** Lines it blurts out when poked. */
  quips: string[]
  tracking?: boolean
  asleep?: boolean
  /** Face override, e.g. Jizza looking up while it thinks. */
  look?: { x: number; y: number }
  mood?: Mood
  className?: string
  children: (face: FaceCtx) => ReactNode
}

/**
 * The shared body for every cartoon character: eyes that follow the pointer and blink, a grin
 * on hover, and a poke (click) that makes it jump and blurt out a one-liner.
 */
export function Mascot({ size, state, quips, tracking = true, asleep = false, look, mood, className = '', children }: MascotProps) {
  const ref = useRef<HTMLSpanElement>(null)
  const gaze = useGaze(ref, tracking && !asleep && !look)
  const blink = useBlink(!asleep)
  const [hover, setHover] = useState(false)
  const { poke, poked, surprised, quip } = usePoke(ref, quips)

  const face: Mood = asleep && !poked ? 'sleep'
    : surprised ? 'surprised'
    : poked ? 'happy'
    : mood ?? (hover ? 'excited' : 'normal')

  return (
    <span ref={ref} className={`mascot relative inline-block shrink-0 cursor-pointer select-none ${className}`}
      style={{ width: size, height: size }} data-state={asleep ? 'off' : state} data-hover={hover} data-poke={poked}
      onPointerEnter={() => setHover(true)} onPointerLeave={() => setHover(false)} onClick={poke}>
      <svg viewBox="0 0 100 100" width={size} height={size} className="overflow-visible" aria-hidden>
        <g className="m-shadow"><ellipse cx="50" cy="96" rx="26" ry="3.5" fill="rgb(0 0 0 / 0.12)" /></g>
        <g className="m-bob">{children({ gaze: look ?? gaze, blink: blink && face !== 'surprised', mood: face })}</g>
      </svg>
      {asleep && (
        <span className="pointer-events-none absolute font-black text-gray-400" style={{ right: -size * 0.12, top: -size * 0.1, fontSize: Math.max(9, size * 0.2) }}>
          <span className="m-z">z</span><span className="m-z" style={{ animationDelay: '0.8s' }}>z</span><span className="m-z" style={{ animationDelay: '1.6s' }}>Z</span>
        </span>
      )}
      {quip && createPortal(
        <span className="m-quip pointer-events-none fixed z-[9999] max-w-[14rem] rounded-2xl border border-gray-200 bg-white px-3 py-1.5 text-center text-xs font-semibold text-gray-800 shadow-lg dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100"
          style={{ left: quip.x, top: quip.y }}>
          {quip.text}
          <span className="absolute left-1/2 top-full -mt-1 h-2 w-2 -translate-x-1/2 rotate-45 border-b border-r border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800" />
        </span>,
        document.body,
      )}
    </span>
  )
}

/** Blinks every few seconds, at a slightly random rhythm so it feels alive rather than mechanical. */
export function useBlink(enabled = true) {
  const [blinking, setBlinking] = useState(false)
  useEffect(() => {
    if (!enabled) return
    let timer: number
    const schedule = () => {
      timer = window.setTimeout(() => {
        setBlinking(true)
        window.setTimeout(() => setBlinking(false), 140)
        schedule()
      }, 2200 + Math.random() * 4000)
    }
    schedule()
    return () => window.clearTimeout(timer)
  }, [enabled])
  return blinking
}

/** A click makes the mascot jump, look startled for a beat, then laugh it off with a quip. */
function usePoke(ref: React.RefObject<HTMLElement>, quips: string[]) {
  const [poked, setPoked] = useState(false)
  const [surprised, setSurprised] = useState(false)
  const [quip, setQuip] = useState<{ text: string; x: number; y: number } | null>(null)
  const timers = useRef<number[]>([])
  const lastQuip = useRef(-1)

  useEffect(() => () => timers.current.forEach(t => window.clearTimeout(t)), [])

  const poke = () => {
    timers.current.forEach(t => window.clearTimeout(t))
    // Drop the attribute for a frame so the jump restarts on every poke.
    setPoked(false)
    requestAnimationFrame(() => {
      setPoked(true)
      setSurprised(true)
      const el = ref.current
      if (el && quips.length) {
        let i = Math.floor(Math.random() * quips.length)
        if (quips.length > 1 && i === lastQuip.current) i = (i + 1) % quips.length
        lastQuip.current = i
        const r = el.getBoundingClientRect()
        setQuip({ text: quips[i], x: r.left + r.width / 2, y: r.top - 10 })
      }
      timers.current = [
        window.setTimeout(() => setSurprised(false), 320),
        window.setTimeout(() => setPoked(false), 900),
        window.setTimeout(() => setQuip(null), 2000),
      ]
    })
  }
  return { poke, poked, surprised, quip }
}

const INK = '#1f2937'

/** A pair of googly eyes. Mood swaps them for happy arcs, sleepy lids or wide startled ones. */
export function Eyes({ x1, x2, y, r, face, dollar = false, ink = INK }: { x1: number; x2: number; y: number; r: number; face: FaceCtx; dollar?: boolean; ink?: string }) {
  const { gaze, blink, mood } = face
  return (
    <g>
      {[x1, x2].map(x => {
        if (mood === 'sleep') {
          return <path key={x} d={`M${x - r * 0.75} ${y} Q${x} ${y + r * 0.65} ${x + r * 0.75} ${y}`} fill="none" stroke={ink} strokeWidth={r * 0.28} strokeLinecap="round" />
        }
        if (mood === 'happy') {
          return <path key={x} d={`M${x - r * 0.75} ${y + r * 0.25} Q${x} ${y - r * 0.85} ${x + r * 0.75} ${y + r * 0.25}`} fill="none" stroke={ink} strokeWidth={r * 0.3} strokeLinecap="round" />
        }
        const big = mood === 'surprised' ? 1.15 : 1
        const pr = mood === 'surprised' ? r * 0.28 : mood === 'excited' ? r * 0.58 : r * 0.5
        const dx = gaze.x * (r - pr) * 0.75
        const dy = gaze.y * (r - pr) * 0.75
        return (
          <g key={x} className="m-eye" style={{ transform: `scaleY(${blink ? 0.08 : 1})` }}>
            <circle cx={x} cy={y} r={r * big} fill="white" stroke={ink} strokeWidth={r * 0.18} />
            {dollar ? (
              <text x={x} y={y + r * 0.42} textAnchor="middle" fontSize={r * 1.25} fontWeight="900" fill="#059669"
                className="m-pupil" style={{ transform: `translate(${dx * 0.5}px, ${dy * 0.5}px)` }}>$</text>
            ) : (
              <g className="m-pupil" style={{ transform: `translate(${dx}px, ${dy}px)` }}>
                <circle cx={x} cy={y} r={pr} fill={ink} />
                <circle cx={x + pr * 0.35} cy={y - pr * 0.4} r={pr * 0.35} fill="white" />
              </g>
            )}
          </g>
        )
      })}
    </g>
  )
}

/** A mouth that follows the mood: a smile, a big open grin, a startled "o" or a sleepy line. */
export function Mouth({ x, y, w, face, ink = INK }: { x: number; y: number; w: number; face: FaceCtx; ink?: string }) {
  const h = w / 2
  switch (face.mood) {
    case 'happy':
    case 'excited':
      return (
        <g>
          <path d={`M${x - h} ${y - 1} Q${x} ${y + w * 0.95} ${x + h} ${y - 1} Z`} fill={ink} stroke={ink} strokeWidth={w * 0.08} strokeLinejoin="round" />
          <ellipse cx={x} cy={y + w * 0.38} rx={h * 0.5} ry={w * 0.13} fill="#f472b6" />
        </g>
      )
    case 'surprised':
      return <ellipse cx={x} cy={y + w * 0.15} rx={w * 0.17} ry={w * 0.24} fill={ink} />
    case 'focused':
      return <path d={`M${x - h * 0.6} ${y + 2} q${h * 0.3} -3 ${h * 0.6} 0 t${h * 0.6} 0`} fill="none" stroke={ink} strokeWidth={w * 0.12} strokeLinecap="round" />
    case 'sleep':
      return <ellipse cx={x} cy={y + 1} rx={w * 0.12} ry={w * 0.09} fill={ink} />
    default:
      return <path d={`M${x - h} ${y} Q${x} ${y + w * 0.55} ${x + h} ${y}`} fill="none" stroke={ink} strokeWidth={w * 0.12} strokeLinecap="round" />
  }
}

export function Cheeks({ x1, x2, y, r }: { x1: number; x2: number; y: number; r: number }) {
  return (
    <g className="m-cheeks" fill="#fb7185" opacity="0.55">
      <ellipse cx={x1} cy={y} rx={r} ry={r * 0.6} />
      <ellipse cx={x2} cy={y} rx={r} ry={r * 0.6} />
    </g>
  )
}
