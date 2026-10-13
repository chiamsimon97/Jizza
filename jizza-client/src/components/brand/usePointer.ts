import { type RefObject, useEffect, useState } from 'react'

type Listener = (x: number, y: number) => void
const listeners = new Set<Listener>()
let last = { x: -1, y: -1 }
let frame = 0

function onMove(e: PointerEvent) {
  last = { x: e.clientX, y: e.clientY }
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    listeners.forEach(l => l(last.x, last.y))
  })
}

/**
 * Where an element should "look": a unit-ish vector from its centre toward the pointer, shared by
 * every logo on the page through a single window listener.
 */
export function useGaze(ref: RefObject<Element | null>, enabled = true) {
  const [gaze, setGaze] = useState({ x: 0, y: 0 })

  useEffect(() => {
    if (!enabled) return
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduced) return
    const listener: Listener = (px, py) => {
      const el = ref.current
      if (!el) return
      const r = el.getBoundingClientRect()
      const dx = px - (r.left + r.width / 2)
      const dy = py - (r.top + r.height / 2)
      const dist = Math.hypot(dx, dy) || 1
      // Full deflection once the pointer is a few widths away; gentler up close.
      const reach = Math.min(1, dist / (r.width * 3))
      setGaze({ x: (dx / dist) * reach, y: (dy / dist) * reach })
    }
    if (listeners.size === 0) window.addEventListener('pointermove', onMove, { passive: true })
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
      if (listeners.size === 0) window.removeEventListener('pointermove', onMove)
    }
  }, [ref, enabled])

  return gaze
}
