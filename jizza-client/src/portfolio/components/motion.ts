import { useEffect, useRef, useState } from 'react'

/** True when the user asked for less motion, or the page can't tell (tests, old browsers). */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return true
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)

/**
 * A number that counts from where it was to `target` over `ms`. With reduced motion, or where
 * there is no animation frame, it is the target straight away, so the figure is never wrong.
 */
export function useCountUp(target: number, ms = 900): number {
  const [shown, setShown] = useState(() => (prefersReducedMotion() ? target : 0))
  const from = useRef(shown)
  useEffect(() => {
    if (prefersReducedMotion() || typeof requestAnimationFrame !== 'function') { setShown(target); return }
    const start = performance.now()
    const begin = from.current
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / ms)
      const v = begin + (target - begin) * easeOut(t)
      from.current = v
      setShown(t === 1 ? target : v)
      if (t < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [target, ms])
  return shown
}

/** False on the first paint and true right after, so a CSS transition can grow a bar from zero. */
export function useMounted(): boolean {
  const [on, setOn] = useState(() => prefersReducedMotion())
  useEffect(() => {
    if (on) return
    const frame = requestAnimationFrame(() => setOn(true))
    return () => cancelAnimationFrame(frame)
  }, [on])
  return on
}

/** Inline style that staggers an entrance animation by position in a list. */
export const stagger = (i: number, step = 60) => ({ animationDelay: `${i * step}ms` })
