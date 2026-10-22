import { useEffect, useRef, useState } from 'react'
import { create } from 'zustand'
import { PAGES } from './pages'

/**
 * Where Jizza is working: a page of the app, the workflow Builder, or one step's settings in it.
 * The chat shows it, the slash commands follow it, and every message carries it so Jizza answers
 * about what is on screen rather than in general.
 */
export interface JizzaContext {
  kind: 'page' | 'planner' | 'builder' | 'step'
  /** Short name of the place, e.g. "Builder" or "Send webhook step". */
  label: string
  /** What in that place, e.g. the workflow's or the step's name. */
  detail?: string
  /** The screen name sent with each message. */
  screen: string
  /** For a step: its type, which picks the slash commands and starters. */
  stepType?: string
  /** What the server is told about this context; it is never stored with the messages. */
  data?: Record<string, unknown>
  /** How the user chose to work on a step ("From API documentation"); kept while the step is open. */
  goal?: string
}

/** A step's settings panel takes Jizza's changes through this. False when it is no longer open. */
export type StepConfigTarget = (patch: Record<string, unknown>, label?: string) => boolean

interface Entry { token: number; ctx: JizzaContext }

interface ContextState {
  /** Innermost last: the Builder, then a step inside it. */
  stack: Entry[]
  push: (ctx: JizzaContext) => number
  update: (token: number, ctx: JizzaContext) => void
  pop: (token: number) => void
}

let nextToken = 1
const targets = new Map<string, StepConfigTarget>()

export const useContextStore = create<ContextState>((set) => ({
  stack: [],
  push: (ctx) => {
    const token = nextToken++
    set(s => ({ stack: [...s.stack, { token, ctx }] }))
    return token
  },
  // The owner refreshes what is on screen; the goal the user picked in the chat stays.
  update: (token, ctx) => set(s => ({
    stack: s.stack.map(e => (e.token === token ? { token, ctx: { goal: e.ctx.goal, ...ctx } } : e)),
  })),
  pop: (token) => set(s => ({ stack: s.stack.filter(e => e.token !== token) })),
}))

/** The page Jizza is on when nothing more specific is open. */
export function pageContext(page: string | undefined): JizzaContext {
  const p = page ? PAGES[page] : undefined
  return { kind: 'page', label: p ? `${p.label} page` : 'Jizza', screen: page ?? 'chat' }
}

export const PLANNER_CONTEXT: JizzaContext = { kind: 'planner', label: 'Workflow planner', screen: 'workflow_planner' }

/** The innermost context, or the page when none is open. */
export function currentContext(page: string | undefined, plannerOpen = false): JizzaContext {
  const stack = useContextStore.getState().stack
  if (stack.length) return stack[stack.length - 1].ctx
  return plannerOpen ? PLANNER_CONTEXT : pageContext(page)
}

/** Lets a step's open settings take what Jizza fills in. Returns the unregister function. */
export function registerStepTarget(stepId: string, target: StepConfigTarget): () => void {
  targets.set(stepId, target)
  return () => { if (targets.get(stepId) === target) targets.delete(stepId) }
}

/** Hands Jizza's settings to the step's open panel; false when that step is not open. */
export function applyStepConfig(stepId: string, patch: Record<string, unknown>, label?: string): boolean {
  const target = targets.get(stepId)
  return target ? target(patch, label) : false
}

/**
 * Tells Jizza a place is open while the calling component is mounted and {@code ctx} is set, and
 * keeps what it knows about it current. Returns the context's token while it is open.
 */
export function useJizzaContext(ctx: JizzaContext | null): number | undefined {
  const [token, setToken] = useState<number>()
  const tokenRef = useRef<number>()
  const json = ctx ? JSON.stringify(ctx) : null
  const open = ctx !== null
  useEffect(() => {
    if (!open) return
    const t = useContextStore.getState().push(JSON.parse(json!) as JizzaContext)
    tokenRef.current = t
    setToken(t)
    return () => { useContextStore.getState().pop(t); tokenRef.current = undefined; setToken(undefined) }
    // Opened once per mount; later changes are updates below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  useEffect(() => {
    if (json && tokenRef.current !== undefined) useContextStore.getState().update(tokenRef.current, JSON.parse(json) as JizzaContext)
  }, [json])
  return token
}
