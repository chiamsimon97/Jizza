import { useRef, useState } from 'react'
import type { CanvasGraph } from './canvasModel'

/**
 * Undo history for the workflow builder. It lives above the board so going full screen keeps it.
 * A graph it didn't produce (another workflow opened) starts the history afresh.
 */
export function useHistory(graph: CanvasGraph, onChange: (g: CanvasGraph) => void) {
  const past = useRef<CanvasGraph[]>([])
  const future = useRef<CanvasGraph[]>([])
  const last = useRef<{ key?: string; at: number }>({ at: 0 })
  const emitted = useRef(graph)
  const [, rerender] = useState(0)

  if (graph !== emitted.current) {
    past.current = []
    future.current = []
    emitted.current = graph
  }

  const emit = (next: CanvasGraph) => { emitted.current = next; onChange(next) }
  return {
    canUndo: past.current.length > 0,
    canRedo: future.current.length > 0,
    /** A change worth its own undo step. Changes with the same key within a second share one. */
    commit(next: CanvasGraph, key?: string) {
      const now = Date.now()
      if (!(key && last.current.key === key && now - last.current.at < 1000)) {
        past.current = [...past.current.slice(-99), emitted.current]
        future.current = []
        rerender(n => n + 1)
      }
      last.current = { key, at: now }
      emit(next)
    },
    undo() {
      const prev = past.current.pop()
      if (!prev) return
      future.current.push(emitted.current)
      last.current = { at: 0 }
      emit(prev)
      rerender(n => n + 1)
    },
    redo() {
      const next = future.current.pop()
      if (!next) return
      past.current.push(emitted.current)
      last.current = { at: 0 }
      emit(next)
      rerender(n => n + 1)
    },
  }
}

export type History = ReturnType<typeof useHistory>
