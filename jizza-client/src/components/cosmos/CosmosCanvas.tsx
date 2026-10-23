import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef } from 'react'
import { select } from 'd3-selection'
import { zoom as d3zoom, zoomIdentity, type ZoomBehavior, type ZoomTransform } from 'd3-zoom'
import { quadtree, type Quadtree } from 'd3-quadtree'
import type { BodyLink, MemoryNode } from '@/types'
import { hash, keplerOffset, placeItems, recencyAlpha, type CosmosLayout, type PlacedBody, type PlacedItem } from './useCosmosLayout'
import {
  OBJECT_NAME, drawAsteroid, drawComet, drawIceMoon, drawMoon, drawPlanet, drawPulsar, drawSpace, drawStar, drawStation,
  mix, planetStyle, rgba, type RGB,
} from './celestial'

/** Zoom levels where more of the sky appears: moons, then each body's facts and notes. */
export const MOONS_AT = 0.25
export const ITEMS_AT = 0.9
const FADED = 0.12

/** Facts and notes circle their body in rings; the inner rings turn faster, as Kepler would have it. */
function itemPeriod(orbit: number) {
  return Math.min(90, Math.max(4, 6 * Math.pow(orbit / 30, 1.5)))
}
const TAU = Math.PI * 2

export interface CosmosHighlight {
  /** Bodies on the path to a search hit; everything else fades back. */
  bodies: Set<string>
  /** `kind:id` of each item that matched. */
  items: Set<string>
}

export interface CosmosCanvasHandle {
  /** Centres on a world point; pass the body it belongs to so drift is followed. */
  flyTo: (x: number, y: number, k?: number, bodyId?: string) => void
  fit: () => void
  zoomBy: (factor: number) => void
}

interface Props {
  layout: CosmosLayout
  links: BodyLink[]
  itemsByBody: Map<string, MemoryNode[]>
  selectedBodyId: string | null
  selectedItemKey: string | null
  highlight: CosmosHighlight | null
  /** Filters dim what doesn't match without hiding it. */
  matches: (n: MemoryNode) => boolean
  dimmedAgentKeys: Set<string>
  /** Let the sky turn. It also pauses on its own while the pointer is over something or held down. */
  drift: boolean
  /** Hold the sky still, e.g. while a memory's passport is open. */
  paused?: boolean
  /** How fast sky time runs: 1 is a turn of the inner planet every 24 seconds. */
  speed: number
  onSelectBody: (bodyId: string | null) => void
  onSelectItem: (node: MemoryNode) => void
  onNeedItems: (bodyIds: string[]) => void
}

type Hit = { x: number; y: number; r: number; body?: PlacedBody; item?: PlacedItem }

interface Palette { orbit: string; text: string; pending: string; agent: (key: string, a?: number) => string; rgb: (key: string) => RGB }

export const itemKey = (n: Pick<MemoryNode, 'itemKind' | 'id'>) => `${n.itemKind}:${n.id}`

/** The sky is always night, whatever the app's theme; only the agents' colours come from the theme. */
function readPalette(): Palette {
  const css = getComputedStyle(document.documentElement)
  const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback
  const agents = new Map<string, RGB>()
  const rgb = (key: string) => {
    let c = agents.get(key)
    if (!c) {
      const raw = (v(`--agent-${key}`, '') || v('--agent-other', '100 116 139')).split(/[\s,]+/).map(Number)
      // Lifted a little so even the light theme's deeper colours read against space.
      c = mix([raw[0] || 0, raw[1] || 0, raw[2] || 0], [255, 255, 255], 0.18)
      agents.set(key, c)
    }
    return c
  }
  return {
    orbit: 'rgb(148 163 184)',
    text: 'rgb(226 232 240)',
    pending: 'rgb(251 191 36)',
    agent: (key, a = 1) => rgba(rgb(key), a),
    rgb,
  }
}

function prefersReducedMotion() {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

/**
 * The sky itself: a 2D canvas so tens of thousands of memories stay smooth, with d3-zoom for pan and
 * zoom and a quadtree over whatever was last drawn for hover and click. What appears depends on
 * the zoom (semantic zoom): planets far out, then moons, then each body's facts and notes.
 */
export const CosmosCanvas = forwardRef<CosmosCanvasHandle, Props>(function CosmosCanvas(props, ref) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const propsRef = useRef(props)
  propsRef.current = props
  const transform = useRef<ZoomTransform>(zoomIdentity)
  const zoomRef = useRef<ZoomBehavior<HTMLCanvasElement, unknown> | null>(null)
  const size = useRef({ w: 0, h: 0, dpr: 1 })
  const palette = useRef<Palette | null>(null)
  const frame = useRef<number | null>(null)
  const drawn = useRef<Hit[]>([])
  const tree = useRef<Quadtree<Hit> | null>(null)
  const hovered = useRef<Hit | null>(null)
  const placedCache = useRef(new WeakMap<MemoryNode[], { body: PlacedBody; items: PlacedItem[] }>())
  const fitted = useRef(false)
  const reduced = useRef(prefersReducedMotion())

  const requestDraw = useCallback(() => {
    if (frame.current != null) return
    frame.current = requestAnimationFrame(() => {
      frame.current = null
      draw()
    })
    // draw reads everything through refs, so it never goes stale.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── motion: every body turns slowly round its parent ───────────────────────────────────────
  // Sky time only runs while the sky is free to move, and eases to a stop rather than freezing, so
  // whatever is under the pointer holds still long enough to pick.
  const sky = useRef({ t: 0, last: 0, speed: 1 })
  const pointerDown = useRef(false)
  const lastPos = useRef(new Map<string, { x: number; y: number }>())
  const grow = useRef(new Map<string, number>())
  /** The body the camera rides along with, until the user drags the sky. */
  const follow = useRef<string | null>(null)
  const flying = useRef(false)

  function advance(now: number) {
    const s = sky.current
    const dt = s.last ? Math.min(100, now - s.last) : 0
    s.last = now
    const p = propsRef.current
    const still = !p.drift || reduced.current || p.paused || pointerDown.current || hovered.current != null
    s.speed += ((still ? 0 : 1) - s.speed) * Math.min(1, dt / 300)
    if (s.speed < 0.001) s.speed = still ? 0 : s.speed
    s.t += dt * s.speed * p.speed
    return s.t / 1000
  }
  function rotate(x: number, y: number, theta: number) {
    if (!theta) return { x, y }
    const c = Math.cos(theta), s = Math.sin(theta)
    return { x: x * c - y * s, y: x * s + y * c }
  }

  // ── drawing ─────────────────────────────────────────────────────────────────────────────────
  function draw() {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const p = propsRef.current
    const pal = palette.current ?? (palette.current = readPalette())
    const { w, h, dpr } = size.current
    const now = performance.now()
    const ts = advance(now)
    const hits: Hit[] = []
    const fade = (on: boolean) => (on ? 1 : FADED)
    const moving = !reduced.current

    // Where each body is now: its parent's position plus where its Kepler orbit has carried it.
    const pos = new Map<string, { x: number; y: number }>()
    const posOf = (b: PlacedBody): { x: number; y: number } => {
      const known = pos.get(b.body.bodyId)
      if (known) return known
      const parent = b.body.parentBodyId ? p.layout.byId.get(b.body.parentBodyId) : undefined
      let here = { x: b.x, y: b.y }
      if (parent && b.orbit > 0) {
        const c = posOf(parent)
        const off = keplerOffset(b.kepler, ts)
        here = { x: c.x + off.x, y: c.y + off.y }
      }
      pos.set(b.body.bodyId, here)
      return here
    }
    for (const b of p.layout.bodies) posOf(b)
    lastPos.current = pos

    // Ride along with the followed body, keeping the zoom the user chose.
    const followed = follow.current ? pos.get(follow.current) : undefined
    if (followed && !flying.current && zoomRef.current) {
      const k0 = transform.current.k
      const target = zoomIdentity.translate(w / 2 - followed.x * k0, h / 2 - followed.y * k0).scale(k0)
      if (Math.abs(target.x - transform.current.x) > 0.01 || Math.abs(target.y - transform.current.y) > 0.01) {
        zoomRef.current.transform(select(canvas), target)
      }
    } else if (follow.current && !followed) {
      follow.current = null
    }
    const t = transform.current
    const k = t.k

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    drawSpace(ctx, w, h, t.x, t.y, now, moving)
    ctx.setTransform(dpr * k, 0, 0, dpr * k, dpr * t.x, dpr * t.y)

    // What's on screen, in world units, with a margin so things don't pop in at the edge.
    const margin = 60 / k
    const vx0 = -t.x / k - margin, vy0 = -t.y / k - margin
    const vx1 = (w - t.x) / k + margin, vy1 = (h - t.y) / k + margin
    const inView = (x: number, y: number, r: number) => x + r > vx0 && x - r < vx1 && y + r > vy0 && y - r < vy1

    const showMoons = k >= MOONS_AT
    const showItems = k >= ITEMS_AT
    // Hovered bodies swell a little; the swell eases in and out.
    for (const b of p.layout.bodies) {
      const id = b.body.bodyId
      const target = hovered.current?.body === b || p.selectedBodyId === id ? 1 : 0
      const cur = grow.current.get(id) ?? 0
      const next = moving ? cur + (target - cur) * 0.18 : target
      if (Math.abs(next) < 0.001) grow.current.delete(id); else grow.current.set(id, next)
    }
    // Far out, the star and planets keep a readable size on screen.
    const floor = (b: PlacedBody) => (b.body.type === 'SUN' ? 14 / k : b.body.type === 'AGENT' ? 8 / k : 0)
    const sizeOf = (b: PlacedBody) => Math.max(b.r, floor(b)) * (1 + 0.18 * (grow.current.get(b.body.bodyId) ?? 0))
    const visible = (b: PlacedBody) => b.body.type === 'SUN' || b.body.type === 'AGENT' || showMoons
    const bodyOn = (b: PlacedBody) =>
      !p.dimmedAgentKeys.has(b.body.agentKey) && (!p.highlight || p.highlight.bodies.has(b.body.bodyId))

    // Orbits: the ellipse each body travels, with its parent at one focus. A ring's moons share one.
    const focusId = hovered.current?.body?.body.bodyId ?? p.selectedBodyId
    const drawnOrbits = new Set<string>()
    for (const b of p.layout.bodies) {
      if (b.orbit === 0 || !visible(b)) continue
      const parent = b.body.parentBodyId ? pos.get(b.body.parentBodyId) : undefined
      if (!parent) continue
      const { a, e, omega } = b.kepler
      const lit = b.body.bodyId === focusId
      const key = `${b.body.parentBodyId}@${Math.round(a)}@${e.toFixed(3)}@${omega.toFixed(3)}`
      if ((!lit && drawnOrbits.has(key)) || !inView(parent.x, parent.y, a * (1 + e) + b.r)) continue
      drawnOrbits.add(key)
      const cx = parent.x - Math.cos(omega) * a * e, cy = parent.y - Math.sin(omega) * a * e
      ctx.strokeStyle = lit ? pal.agent(b.body.agentKey, 0.9) : pal.orbit
      ctx.globalAlpha = lit ? 1 : (b.body.type === 'AGENT' ? 0.32 : 0.18) * fade(bodyOn(b))
      ctx.lineWidth = (lit ? 1.6 : 1) / k
      ctx.setLineDash(b.body.type === 'WORKFLOW' && !lit ? [4 / k, 5 / k] : [])
      ctx.beginPath()
      ctx.ellipse(cx, cy, a, a * Math.sqrt(1 - e * e), omega, 0, TAU)
      ctx.stroke()
    }
    ctx.setLineDash([])
    ctx.globalAlpha = 1

    // Items placed around the bodies in view.
    const placedItems: PlacedItem[] = []
    const need: string[] = []
    if (showItems) {
      for (const b of p.layout.bodies) {
        if (b.body.memoryCount === 0 || !visible(b)) continue
        const c = pos.get(b.body.bodyId)!
        if (!inView(c.x, c.y, b.satExtent)) continue
        const items = p.itemsByBody.get(b.body.bodyId)
        if (!items) { need.push(b.body.bodyId); continue }
        let cached = placedCache.current.get(items)
        if (!cached || cached.body !== b) {
          cached = { body: b, items: placeItems(b, items) }
          placedCache.current.set(items, cached)
        }
        for (const it of cached.items) {
          const off = rotate(it.x - b.x, it.y - b.y, (TAU / itemPeriod(it.orbit)) * ts)
          placedItems.push({ ...it, x: c.x + off.x, y: c.y + off.y, parentX: c.x, parentY: c.y })
        }
      }
    }
    if (need.length) p.onNeedItems(need)

    // Links and tethers for what's selected.
    const strokeLink = (a: { x: number; y: number }, b: { x: number; y: number }, color: string, dashed: boolean) => {
      ctx.strokeStyle = color
      ctx.lineWidth = 1.5 / k
      ctx.setLineDash(dashed ? [6 / k, 4 / k] : [])
      ctx.beginPath()
      ctx.moveTo(a.x, a.y)
      ctx.lineTo(b.x, b.y)
      ctx.stroke()
      ctx.setLineDash([])
    }
    if (p.selectedBodyId) {
      const from = pos.get(p.selectedBodyId)
      const sel = p.layout.byId.get(p.selectedBodyId)
      for (const l of p.links) {
        const other = l.fromBodyId === p.selectedBodyId ? l.toBodyId : l.toBodyId === p.selectedBodyId ? l.fromBodyId : null
        const to = other ? pos.get(other) : undefined
        if (from && to && sel) strokeLink(from, to, pal.agent(sel.body.agentKey, 0.7), false)
      }
      if (sel) {
        for (const it of placedItems) {
          if (it.node.parentBodyId !== p.selectedBodyId) continue
          for (const tb of it.node.tetherBodyIds) {
            const to = pos.get(tb)
            if (to) strokeLink(it, to, pal.agent(it.node.agentKey, 0.25), true)
          }
        }
      }
    }
    const selectedItem = p.selectedItemKey ? placedItems.find(it => itemKey(it.node) === p.selectedItemKey) : undefined
    if (selectedItem) {
      for (const tb of selectedItem.node.tetherBodyIds) {
        const to = pos.get(tb)
        if (to) strokeLink(selectedItem, to, pal.agent(selectedItem.node.agentKey, 0.8), true)
      }
    }

    // Bodies, each drawn as its kind of object and lit from the sun.
    const sunAt = p.layout.sunId ? pos.get(p.layout.sunId) ?? { x: 0, y: 0 } : { x: 0, y: 0 }
    for (const b of p.layout.bodies) {
      if (!visible(b)) continue
      const c = pos.get(b.body.bodyId)!
      const r = sizeOf(b)
      const type = b.body.type
      if (!inView(c.x, c.y, r * (type === 'WORKFLOW' ? 18 : type === 'SUN' ? 4.5 : 2.4))) continue
      const on = bodyOn(b)
      ctx.globalAlpha = fade(on)
      const base = pal.rgb(b.body.agentKey)
      const light = Math.atan2(sunAt.y - c.y, sunAt.x - c.x)
      const seed = hash(b.body.bodyId) / 0xffffffff
      const id = b.body.bodyId
      if (type === 'SUN') drawStar(ctx, c.x, c.y, r, now, moving)
      else if (type === 'AGENT') drawPlanet(ctx, id, planetStyle(b.body.agentKey, id), c.x, c.y, r, base, light, now, moving)
      else if (type === 'NOTES') drawIceMoon(ctx, id, c.x, c.y, r, base, light)
      else if (type === 'FILE') drawAsteroid(ctx, id, c.x, c.y, r, base, light, seed * TAU + ts * (0.3 + seed * 0.5))
      else if (type === 'CONNECTOR') drawStation(ctx, c.x, c.y, r, base, seed * TAU + ts * 0.25, now, moving)
      else if (type === 'SKILL') drawPulsar(ctx, c.x, c.y, r, base, now, moving)
      else if (type === 'WORKFLOW') {
        const parent = b.body.parentBodyId ? pos.get(b.body.parentBodyId) : undefined
        const off = keplerOffset(b.kepler, ts), before = keplerOffset(b.kepler, ts - b.kepler.period * 0.01)
        const ax = c.x - sunAt.x, ay = c.y - sunAt.y, al = Math.hypot(ax, ay) || 1
        const bx = before.x - off.x, by = before.y - off.y, bl = Math.hypot(bx, by) || 1
        const { a, e } = b.kepler
        const heat = parent && e > 0 ? Math.min(1, Math.max(0, (a * (1 + e) - Math.hypot(off.x, off.y)) / (2 * a * e))) : 0.5
        drawComet(ctx, c.x, c.y, r, base, { x: ax / al, y: ay / al }, { x: bx / bl, y: by / bl }, heat)
      } else drawMoon(ctx, id, c.x, c.y, r, base, light)
      ctx.globalAlpha = 1
      if (p.selectedBodyId === id || hovered.current?.body === b) {
        ctx.strokeStyle = pal.text
        ctx.globalAlpha = 0.85
        ctx.lineWidth = 1.5 / k
        const pulse = p.selectedBodyId === id && moving ? 2 * Math.sin(now / 350) : 0
        const ring = r * (type === 'AGENT' && planetStyle(b.body.agentKey, id) === 'ringed' ? 2.3 : 1) + (6 + pulse) / k
        ctx.setLineDash([4 / k, 3 / k])
        ctx.beginPath()
        ctx.arc(c.x, c.y, ring, 0, TAU)
        ctx.stroke()
        ctx.setLineDash([])
        ctx.globalAlpha = 1
      }
      hits.push({ x: c.x, y: c.y, r: Math.max(r, 6 / k), body: b })
    }

    // Satellites, like specks of stardust: size is how often agents read it, brightness how recently.
    for (const it of placedItems) {
      if (!inView(it.x, it.y, it.r)) continue
      const n = it.node
      const on = p.matches(n) && !p.dimmedAgentKeys.has(n.agentKey) && (!p.highlight || p.highlight.items.has(itemKey(n)))
      const alpha = fade(on) * (n.status === 'retired' || n.status === 'superseded' ? 0.35 : recencyAlpha(n))
      const color = n.restricted ? [148, 163, 184] as RGB : mix(pal.rgb(n.agentKey), [255, 255, 255], 0.25)
      if (k >= 1.6 && !n.restricted) {
        ctx.globalAlpha = alpha * 0.5
        const g = ctx.createRadialGradient(it.x, it.y, 0, it.x, it.y, it.r * 2.4)
        g.addColorStop(0, rgba(color, 0.8))
        g.addColorStop(1, rgba(color, 0))
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(it.x, it.y, it.r * 2.4, 0, TAU)
        ctx.fill()
      }
      ctx.globalAlpha = alpha
      ctx.fillStyle = rgba(color)
      ctx.beginPath()
      if (n.itemKind === 'note') {
        // Notes are little shards; facts are round specks.
        ctx.moveTo(it.x, it.y - it.r); ctx.lineTo(it.x + it.r * 0.8, it.y); ctx.lineTo(it.x, it.y + it.r); ctx.lineTo(it.x - it.r * 0.8, it.y); ctx.closePath()
      } else ctx.arc(it.x, it.y, it.r, 0, TAU)
      ctx.fill()
      if (n.restricted && k >= 3) drawLock(ctx, it.x, it.y, it.r, pal.text, k)
      if (n.status === 'pending') {
        // Waiting on the user to confirm: an amber dashed ring.
        ctx.globalAlpha = fade(on)
        ctx.strokeStyle = pal.pending
        ctx.lineWidth = 1.2 / k
        ctx.setLineDash([2 / k, 2 / k])
        ctx.beginPath()
        ctx.arc(it.x, it.y, it.r + 2 / k, 0, TAU)
        ctx.stroke()
        ctx.setLineDash([])
      }
      if (p.selectedItemKey === itemKey(n) || hovered.current?.item?.node === n) {
        ctx.globalAlpha = 1
        ctx.strokeStyle = pal.text
        ctx.lineWidth = 1.5 / k
        ctx.beginPath()
        ctx.arc(it.x, it.y, it.r + 3 / k, 0, TAU)
        ctx.stroke()
      }
      hits.push({ x: it.x, y: it.y, r: Math.max(it.r, 4 / k), item: it })
    }
    ctx.globalAlpha = 1

    // Labels, in screen space so they stay crisp at any zoom.
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'top'
    ctx.shadowColor = 'rgba(0,0,0,0.8)'
    ctx.shadowBlur = 4
    for (const b of p.layout.bodies) {
      if (!visible(b)) continue
      const isPlanet = b.body.type === 'SUN' || b.body.type === 'AGENT'
      if (!isPlanet && k < 0.6) continue
      const c = pos.get(b.body.bodyId)!
      if (!inView(c.x, c.y, b.r)) continue
      const ringed = b.body.type === 'AGENT' && planetStyle(b.body.agentKey, b.body.bodyId) === 'ringed'
      const below = sizeOf(b) * (ringed ? 1.1 : b.body.type === 'SUN' ? 1.15 : 1)
      const sx = c.x * k + t.x, sy = (c.y + below) * k + t.y + 5
      ctx.globalAlpha = fade(bodyOn(b)) * (isPlanet ? 1 : 0.8)
      ctx.font = isPlanet ? '600 13px Inter, system-ui, sans-serif' : '11px Inter, system-ui, sans-serif'
      ctx.fillStyle = pal.text
      const label = b.body.label.length > 28 ? `${b.body.label.slice(0, 27)}…` : b.body.label
      ctx.fillText(isPlanet && b.total > 0 ? `${label} · ${b.total}` : label, sx, sy)
    }
    ctx.shadowBlur = 0
    ctx.globalAlpha = 1
    const hov = hovered.current
    if (hov?.item) {
      const n = hov.item.node
      const text = n.redacted || !n.label ? 'Restricted memory' : n.label.length > 60 ? `${n.label.slice(0, 59)}…` : n.label
      tooltip(ctx, [text], hov.x * k + t.x, (hov.y - hov.r) * k + t.y - 8, w)
    } else if (hov?.body) {
      const b = hov.body
      const at = pos.get(b.body.bodyId)
      if (at) {
        const kind = OBJECT_NAME[b.body.type] ?? b.body.type
        const count = b.body.type === 'SUN' || b.body.type === 'AGENT' ? b.total : b.body.memoryCount
        const detail = `${kind} · ${count} ${count === 1 ? 'memory' : 'memories'}${b.orbit > 0 ? ` · orbit ${Math.round(b.kepler.period)}s` : ''}`
        tooltip(ctx, [b.body.label, detail], at.x * k + t.x, (at.y - sizeOf(b)) * k + t.y - 10, w)
      }
    }

    drawn.current = hits
    tree.current = null
    // Keep the sky alive: orbits, the sun's corona and the stars all move.
    if (moving) requestDraw()
  }

  // ── hit testing ─────────────────────────────────────────────────────────────────────────────
  function hitAt(clientX: number, clientY: number): Hit | null {
    const canvas = canvasRef.current
    if (!canvas) return null
    const rect = canvas.getBoundingClientRect()
    const t = transform.current
    const wx = (clientX - rect.left - t.x) / t.k
    const wy = (clientY - rect.top - t.y) / t.k
    if (!tree.current) tree.current = quadtree<Hit>().x(d => d.x).y(d => d.y).addAll(drawn.current)
    const slop = 6 / t.k
    // Items first: they sit on top and are small.
    let best: Hit | null = null
    let bestD = Infinity
    tree.current.visit((node, x0, y0, x1, y1) => {
      if (!('length' in node)) {
        let leaf: typeof node | undefined = node
        while (leaf) {
          const d = leaf.data
          const dist = Math.hypot(d.x - wx, d.y - wy) - d.r
          const score = dist - (d.item ? 1000 : 0)
          if (dist <= slop && score < bestD) { best = d; bestD = score }
          leaf = leaf.next
        }
      }
      const reach = 60 + slop
      return x0 > wx + reach || x1 < wx - reach || y0 > wy + reach || y1 < wy - reach
    })
    return best as Hit | null
  }

  // ── setup ───────────────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    const canvas = canvasRef.current!
    const z = d3zoom<HTMLCanvasElement, unknown>()
      .scaleExtent([0.03, 16])
      .on('zoom', e => {
        transform.current = e.transform
        // Dragging the sky lets go of whatever the camera was following; zooming keeps it.
        const src = e.sourceEvent as Event | null
        if (src && /move/.test(src.type)) follow.current = null
        requestDraw()
      })
    zoomRef.current = z
    select(canvas).call(z)

    const resize = () => {
      const rect = canvas.parentElement!.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      size.current = { w: rect.width, h: rect.height, dpr }
      canvas.width = Math.max(1, Math.round(rect.width * dpr))
      canvas.height = Math.max(1, Math.round(rect.height * dpr))
      canvas.style.width = `${rect.width}px`
      canvas.style.height = `${rect.height}px`
      if (!fitted.current && propsRef.current.layout.bodies.length) {
        fitted.current = true
        fit(false)
      }
      requestDraw()
    }
    const ro = new ResizeObserver(resize)
    ro.observe(canvas.parentElement!)
    resize()

    // Re-read colours when the theme changes.
    const mo = new MutationObserver(() => { palette.current = null; requestDraw() })
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] })
    const mq = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    const onMotion = () => { reduced.current = !!mq?.matches; requestDraw() }
    mq?.addEventListener?.('change', onMotion)

    return () => {
      ro.disconnect()
      mo.disconnect()
      mq?.removeEventListener?.('change', onMotion)
      select(canvas).on('.zoom', null)
      if (frame.current != null) cancelAnimationFrame(frame.current)
      frame.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!fitted.current && props.layout.bodies.length && size.current.w > 0) {
      fitted.current = true
      fit(false)
    }
    requestDraw()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.layout, props.links, props.itemsByBody, props.selectedBodyId, props.selectedItemKey,
      props.highlight, props.matches, props.dimmedAgentKeys, props.drift, props.paused, props.speed])

  /** Glides to `target`; with `bodyId`, aims at wherever that body has moved to by each frame. */
  function applyTransform(target: ZoomTransform, animate: boolean, bodyId?: string) {
    const canvas = canvasRef.current
    const z = zoomRef.current
    if (!canvas || !z) return
    const sel = select(canvas)
    if (!animate || reduced.current) { z.transform(sel, target); return }
    const from = transform.current
    const start = performance.now()
    flying.current = true
    const step = (now: number) => {
      const u = Math.min(1, (now - start) / 600)
      const e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2
      const k = Math.exp(Math.log(from.k) + (Math.log(target.k) - Math.log(from.k)) * e)
      // Interpolate the world point at the centre of the screen, so it glides rather than swings.
      const { w, h } = size.current
      const fx = (w / 2 - from.x) / from.k, fy = (h / 2 - from.y) / from.k
      const moved = bodyId ? lastPos.current.get(bodyId) : undefined
      const tx = moved ? moved.x : (w / 2 - target.x) / target.k, ty = moved ? moved.y : (h / 2 - target.y) / target.k
      const cx = fx + (tx - fx) * e, cy = fy + (ty - fy) * e
      z.transform(sel, zoomIdentity.translate(w / 2 - cx * k, h / 2 - cy * k).scale(k))
      if (u < 1) requestAnimationFrame(step)
      else {
        flying.current = false
        if (bodyId) follow.current = bodyId
      }
    }
    requestAnimationFrame(step)
  }

  function flyTo(x: number, y: number, k?: number, bodyId?: string) {
    const { w, h } = size.current
    const scale = k ?? Math.max(transform.current.k, ITEMS_AT * 1.6)
    // Where the body is now, not where the layout first put it; then ride along with it.
    const target = (bodyId && lastPos.current.get(bodyId)) || { x, y }
    follow.current = null
    applyTransform(zoomIdentity.translate(w / 2 - target.x * scale, h / 2 - target.y * scale).scale(scale), true, bodyId)
    if (reduced.current && bodyId) follow.current = bodyId
  }

  function fit(animate = true) {
    follow.current = null
    const { w, h } = size.current
    if (!w || !h) return
    const r = propsRef.current.layout.radius
    const k = Math.min(w, h) / (2 * r * 1.05)
    applyTransform(zoomIdentity.translate(w / 2, h / 2).scale(k), animate)
  }

  useImperativeHandle(ref, () => ({
    flyTo: (x, y, k, bodyId) => flyTo(x, y, k, bodyId),
    fit: () => fit(true),
    zoomBy: factor => {
      const canvas = canvasRef.current
      if (canvas && zoomRef.current) zoomRef.current.scaleBy(select(canvas), factor)
    },
  }))

  function onPointerMove(e: React.PointerEvent) {
    const hit = hitAt(e.clientX, e.clientY)
    if (hit?.body !== hovered.current?.body || hit?.item !== hovered.current?.item) {
      hovered.current = hit
      if (canvasRef.current) canvasRef.current.style.cursor = hit ? 'pointer' : 'grab'
      requestDraw()
    }
  }

  function onClick(e: React.MouseEvent) {
    const hit = hitAt(e.clientX, e.clientY)
    const p = propsRef.current
    if (hit?.item) {
      p.onSelectItem(hit.item.node)
    } else if (hit?.body) {
      p.onSelectBody(hit.body.body.bodyId)
      // Clicking a body flies to it and follows it round its orbit; far out, it zooms in too.
      const b = hit.body
      const k = transform.current.k < ITEMS_AT ? Math.min(3, Math.max(ITEMS_AT * 1.2, 260 / (b.extent + 40))) : transform.current.k
      flyTo(b.x, b.y, b.body.type === 'SUN' ? Math.max(transform.current.k, 0.5) : k, b.body.bodyId)
    } else {
      p.onSelectBody(null)
    }
  }

  function onKeyDown(e: React.KeyboardEvent) {
    const canvas = canvasRef.current
    const z = zoomRef.current
    if (!canvas || !z) return
    const sel = select(canvas)
    const step = 60
    switch (e.key) {
      case '+': case '=': z.scaleBy(sel, 1.4); break
      case '-': case '_': z.scaleBy(sel, 1 / 1.4); break
      case 'ArrowLeft': z.translateBy(sel, step / transform.current.k, 0); break
      case 'ArrowRight': z.translateBy(sel, -step / transform.current.k, 0); break
      case 'ArrowUp': z.translateBy(sel, 0, step / transform.current.k); break
      case 'ArrowDown': z.translateBy(sel, 0, -step / transform.current.k); break
      case '0': fit(true); break
      case 'Escape': follow.current = null; propsRef.current.onSelectBody(null); break
      default: return
    }
    e.preventDefault()
  }

  return (
    <canvas ref={canvasRef} tabIndex={0} role="img"
      aria-label="Memory Cosmos, drawn as a solar system. Jizza is the star and each agent a planet; people and things are moons, notes ice moons, files asteroids, connectors space stations, workflows comets and skills pulsars, and each fact or note is a speck of stardust. Use plus and minus to zoom, arrow keys to pan, or switch to the list for a keyboard-friendly view."
      onPointerMove={onPointerMove} onPointerLeave={() => { hovered.current = null; pointerDown.current = false; requestDraw() }}
      onPointerDown={() => { pointerDown.current = true }} onPointerUp={() => { pointerDown.current = false }}
      onClick={onClick} onKeyDown={onKeyDown}
      className="block h-full w-full cursor-grab touch-none outline-none focus-visible:ring-2 focus-visible:ring-brand-500" />
  )
})

function drawLock(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string, k: number) {
  const s = Math.max(r, 3 / k)
  ctx.save()
  ctx.globalAlpha = 1
  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineWidth = 0.8 / k
  ctx.beginPath()
  ctx.arc(x, y - s * 0.3, s * 0.45, Math.PI, 0)
  ctx.stroke()
  ctx.fillRect(x - s * 0.6, y - s * 0.3, s * 1.2, s * 0.9)
  ctx.restore()
}

function tooltip(ctx: CanvasRenderingContext2D, lines: string[], x: number, y: number, maxX: number) {
  ctx.font = '12px Inter, system-ui, sans-serif'
  const widths = lines.map((l, i) => {
    ctx.font = i === 0 && lines.length > 1 ? '600 12px Inter, system-ui, sans-serif' : '12px Inter, system-ui, sans-serif'
    return ctx.measureText(l).width
  })
  const w = Math.max(...widths) + 16
  const h = lines.length * 16 + 8
  const left = Math.min(Math.max(4, x - w / 2), maxX - w - 4)
  const top = Math.max(4, y - h)
  ctx.globalAlpha = 0.94
  ctx.fillStyle = 'rgb(15 23 42)'
  ctx.strokeStyle = 'rgba(148,163,184,0.45)'
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.roundRect(left, top, w, h, 6)
  ctx.fill()
  ctx.stroke()
  ctx.globalAlpha = 1
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  lines.forEach((l, i) => {
    ctx.font = i === 0 && lines.length > 1 ? '600 12px Inter, system-ui, sans-serif' : '12px Inter, system-ui, sans-serif'
    ctx.fillStyle = i === 0 ? 'rgb(241 245 249)' : 'rgb(148 163 184)'
    ctx.fillText(l, left + 8, top + 12 + i * 16)
  })
}
