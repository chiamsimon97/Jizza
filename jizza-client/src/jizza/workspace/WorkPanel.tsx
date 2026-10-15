import { useCallback, useRef, useState } from 'react'
import { ActivityPanel } from './ActivityPanel'
import { MemoryPanel } from './MemoryPanel'

const KEY = 'jizza.workPanel.split'
const MIN = 0.2
const MAX = 0.8

function initialSplit(): number {
  try {
    const v = Number(localStorage.getItem(KEY))
    return v >= MIN && v <= MAX ? v : 0.58
  } catch {
    return 0.58
  }
}

/**
 * The right side of the chat workspace, split horizontally: Activity on top (the plan, who asked
 * whom, what ran) and Memory below (what that job used, found and kept). The divider drags.
 */
export function WorkPanel() {
  const [split, setSplit] = useState(initialSplit)
  const box = useRef<HTMLDivElement>(null)

  const move = useCallback((clientY: number) => {
    const rect = box.current?.getBoundingClientRect()
    if (!rect || rect.height === 0) return
    setSplit(Math.min(MAX, Math.max(MIN, (clientY - rect.top) / rect.height)))
  }, [])

  const start = (e: React.PointerEvent) => {
    e.preventDefault()
    const target = e.currentTarget as HTMLElement
    target.setPointerCapture(e.pointerId)
    const onMove = (ev: PointerEvent) => move(ev.clientY)
    const onUp = () => {
      target.removeEventListener('pointermove', onMove)
      target.removeEventListener('pointerup', onUp)
      setSplit(s => { try { localStorage.setItem(KEY, String(s)) } catch { /* a convenience only */ } return s })
    }
    target.addEventListener('pointermove', onMove)
    target.addEventListener('pointerup', onUp)
  }

  const nudge = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowUp') setSplit(s => Math.max(MIN, s - 0.05))
    if (e.key === 'ArrowDown') setSplit(s => Math.min(MAX, s + 0.05))
  }

  return (
    <div ref={box} className="flex h-full min-h-0 flex-col bg-gray-50/80 dark:bg-gray-950">
      <div style={{ height: `${split * 100}%` }} className="min-h-0">
        <ActivityPanel />
      </div>
      <div
        role="separator"
        aria-orientation="horizontal"
        aria-label="Resize activity and memory"
        aria-valuenow={Math.round(split * 100)}
        tabIndex={0}
        onPointerDown={start}
        onKeyDown={nudge}
        className="group relative h-2 shrink-0 cursor-row-resize touch-none focus:outline-none"
      >
        <div className="absolute inset-x-0 top-1/2 h-px -translate-y-1/2 bg-gray-200 dark:bg-gray-800" />
        <div className="absolute left-1/2 top-1/2 h-1 w-10 -translate-x-1/2 -translate-y-1/2 rounded-full bg-gray-300 transition-colors group-hover:bg-brand-400 group-focus-visible:bg-brand-500 dark:bg-gray-700" />
      </div>
      <div className="min-h-0 flex-1">
        <MemoryPanel />
      </div>
    </div>
  )
}
