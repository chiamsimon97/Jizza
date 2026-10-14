import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef } from 'react'
import { select } from 'd3-selection'
import { zoom as d3zoom, zoomIdentity, type ZoomBehavior, type ZoomTransform } from 'd3-zoom'
import { quadtree, type Quadtree } from 'd3-quadtree'
import type { BodyLink, MemoryNode } from '@/types'
import { placeItems, recencyAlpha, type CosmosLayout, type PlacedBody, type PlacedItem } from './useCosmosLayout'

/** Zoom levels where more of the sky appears: moons, then each body's facts and notes. */
export const MOONS_AT = 0.6
export const ITEMS_AT = 1.5
const FADED = 0.12

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
  drift: boolean
  onSelectBody: (bodyId: string | null) => void
  onSelectItem: (node: MemoryNode) => void
  onNeedItems: (bodyIds: string[]) => void
}

type Hit = { x: number; y: number; r: number; body?: PlacedBody; item?: PlacedItem }

interface Palette { bg: string; orbit: string; text: string; pending: string; agent: (key: string, a?: number) => string }

export const itemKey = (n: Pick<MemoryNode, 'itemKind' | 'id'>) => `${n.itemKind}:${n.id}`

function readPalette(): Palette {
  const css = getComputedStyle(document.documentElement)
  const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback
  const agents = new Map<string, string>()
  const agent = (key: string, a = 1) => {
    let rgb = agents.get(key)
    if (!rgb) {
      rgb = v(`--agent-${key}`, '') || v('--agent-other', '100 116 139')
      agents.set(key, rgb)
    }
    return `rgb(${rgb} / ${a})`
  }
  return {
    bg: `rgb(${v('--cosmos-bg', '248 250 252')})`,
    orbit: `rgb(${v('--cosmos-orbit', '203 213 225')})`,
    text: `rgb(${v('--cosmos-text', '30 41 59')})`,
    pending: `rgb(${v('--cosmos-pending', '217 119 6')})`,
    agent,
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

  // ── drift: each planet's system turns slowly round the sun ─────────────────────────────────
  const driftAngle = (orbit: number, t: number) => (t / 1000) * 0.02 * (220 / Math.max(220, orbit))
  const planetOf = useRef(new Map<string, PlacedBody>())
  useEffect(() => {
    const m = new Map<string, PlacedBody>()
    for (const p of props.layout.bodies) {
      let at: PlacedBody | undefined = p
      while (at && at.body.type !== 'AGENT' && at.body.parentBodyId) at = props.layout.byId.get(at.body.parentBodyId)
      if (at && at.body.type === 'AGENT') m.set(p.body.bodyId, at)
    }
    planetOf.current = m
    requestDraw()
  }, [props.layout, requestDraw])

  function rotation(bodyId: string, t: number) {
    if (!propsRef.current.drift || reduced.current) return 0
    const planet = planetOf.current.get(bodyId)
    return planet ? driftAngle(planet.orbit, t) : 0
  }
  function at(x: number, y: number, theta: number) {
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
    const t = transform.current
    const k = t.k
    const now = performance.now()
    const hits: Hit[] = []
    const fade = (on: boolean) => (on ? 1 : FADED)

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.fillStyle = pal.bg
    ctx.fillRect(0, 0, w, h)
    ctx.setTransform(dpr * k, 0, 0, dpr * k, dpr * t.x, dpr * t.y)

    // What's on screen, in world units, with a margin so things don't pop in at the edge.
    const margin = 60 / k
    const vx0 = -t.x / k - margin, vy0 = -t.y / k - margin
    const vx1 = (w - t.x) / k + margin, vy1 = (h - t.y) / k + margin
    const inView = (x: number, y: number, r: number) => x + r > vx0 && x - r < vx1 && y + r > vy0 && y - r < vy1

    const showMoons = k >= MOONS_AT
    const showItems = k >= ITEMS_AT
    const pos = new Map<string, { x: number; y: number }>()
    for (const b of p.layout.bodies) pos.set(b.body.bodyId, at(b.x, b.y, rotation(b.body.bodyId, now)))
    const visible = (b: PlacedBody) => b.body.type === 'SUN' || b.body.type === 'AGENT' || showMoons
    const bodyOn = (b: PlacedBody) =>
      !p.dimmedAgentKeys.has(b.body.agentKey) && (!p.highlight || p.highlight.bodies.has(b.body.bodyId))

    // Orbits.
    ctx.lineWidth = 1 / k
    ctx.strokeStyle = pal.orbit
    const drawnOrbits = new Set<string>()
    for (const b of p.layout.bodies) {
      if (b.orbit === 0 || !visible(b)) continue
      const parent = b.body.parentBodyId ? pos.get(b.body.parentBodyId) : undefined
      if (!parent) continue
      const key = `${b.body.parentBodyId}@${Math.round(b.orbit)}`
      if (drawnOrbits.has(key) || !inView(parent.x, parent.y, b.orbit + b.r)) continue
      drawnOrbits.add(key)
      ctx.globalAlpha = b.body.type === 'AGENT' ? 0.9 : 0.5
      ctx.beginPath()
      ctx.arc(parent.x, parent.y, b.orbit, 0, Math.PI * 2)
      ctx.stroke()
    }
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
        const dx = c.x - b.x, dy = c.y - b.y
        for (const it of cached.items) {
          placedItems.push(dx || dy ? { ...it, x: it.x + dx, y: it.y + dy, parentX: c.x, parentY: c.y } : it)
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

    // Bodies.
    for (const b of p.layout.bodies) {
      if (!visible(b)) continue
      const c = pos.get(b.body.bodyId)!
      if (!inView(c.x, c.y, b.r)) continue
      const on = bodyOn(b)
      ctx.globalAlpha = fade(on)
      const color = (a = 1) => pal.agent(b.body.agentKey, a)
      if (b.body.type === 'SUN') {
        const g = ctx.createRadialGradient(c.x, c.y, b.r * 0.2, c.x, c.y, b.r * 1.6)
        g.addColorStop(0, color(1))
        g.addColorStop(0.6, color(0.55))
        g.addColorStop(1, color(0))
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(c.x, c.y, b.r * 1.6, 0, Math.PI * 2)
        ctx.fill()
      }
      ctx.fillStyle = b.body.type === 'NOTES' || b.body.type === 'FILE' ? color(0.55) : color(b.body.type === 'AGENT' || b.body.type === 'SUN' ? 1 : 0.8)
      ctx.beginPath()
      if (b.body.type === 'WORKFLOW' || b.body.type === 'SKILL') {
        // Procedures are diamonds, so they read apart from what's remembered.
        ctx.moveTo(c.x, c.y - b.r); ctx.lineTo(c.x + b.r, c.y); ctx.lineTo(c.x, c.y + b.r); ctx.lineTo(c.x - b.r, c.y); ctx.closePath()
      } else if (b.body.type === 'CONNECTOR') {
        ctx.rect(c.x - b.r * 0.85, c.y - b.r * 0.85, b.r * 1.7, b.r * 1.7)
      } else {
        ctx.arc(c.x, c.y, b.r, 0, Math.PI * 2)
      }
      ctx.fill()
      if (p.selectedBodyId === b.body.bodyId || hovered.current?.body === b) {
        ctx.strokeStyle = pal.text
        ctx.lineWidth = 2 / k
        ctx.beginPath()
        ctx.arc(c.x, c.y, b.r + 4 / k, 0, Math.PI * 2)
        ctx.stroke()
      }
      hits.push({ x: c.x, y: c.y, r: b.r, body: b })
    }

    // Satellites: size is how often agents read it, brightness how recently.
    for (const it of placedItems) {
      if (!inView(it.x, it.y, it.r)) continue
      const n = it.node
      const on = p.matches(n) && !p.dimmedAgentKeys.has(n.agentKey) && (!p.highlight || p.highlight.items.has(itemKey(n)))
      const alpha = fade(on) * (n.status === 'retired' || n.status === 'superseded' ? 0.35 : recencyAlpha(n))
      ctx.globalAlpha = alpha
      ctx.fillStyle = n.restricted ? pal.orbit : pal.agent(n.agentKey, 1)
      ctx.beginPath()
      if (n.itemKind === 'note') ctx.rect(it.x - it.r * 0.85, it.y - it.r * 0.85, it.r * 1.7, it.r * 1.7)
      else ctx.arc(it.x, it.y, it.r, 0, Math.PI * 2)
      ctx.fill()
      if (n.restricted && k >= 3) drawLock(ctx, it.x, it.y, it.r, pal.text, k)
      if (n.status === 'pending') {
        // Waiting on the user to confirm: an amber dashed ring.
        ctx.globalAlpha = fade(on)
        ctx.strokeStyle = pal.pending
        ctx.lineWidth = 1.2 / k
        ctx.setLineDash([2 / k, 2 / k])
        ctx.beginPath()
        ctx.arc(it.x, it.y, it.r + 2 / k, 0, Math.PI * 2)
        ctx.stroke()
        ctx.setLineDash([])
      }
      if (p.selectedItemKey === itemKey(n) || hovered.current?.item?.node === n) {
        ctx.globalAlpha = 1
        ctx.strokeStyle = pal.text
        ctx.lineWidth = 1.5 / k
        ctx.beginPath()
        ctx.arc(it.x, it.y, it.r + 3 / k, 0, Math.PI * 2)
        ctx.stroke()
      }
      hits.push({ x: it.x, y: it.y, r: it.r, item: it })
    }
    ctx.globalAlpha = 1

    // Labels, in screen space so they stay crisp at any zoom.
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'top'
    for (const b of p.layout.bodies) {
      if (!visible(b)) continue
      const isPlanet = b.body.type === 'SUN' || b.body.type === 'AGENT'
      if (!isPlanet && k < 1) continue
      const c = pos.get(b.body.bodyId)!
      if (!inView(c.x, c.y, b.r)) continue
      const sx = c.x * k + t.x, sy = (c.y + b.r) * k + t.y + 4
      ctx.globalAlpha = fade(bodyOn(b))
      ctx.font = isPlanet ? '600 13px Inter, system-ui, sans-serif' : '11px Inter, system-ui, sans-serif'
      ctx.fillStyle = pal.text
      const label = b.body.label.length > 28 ? `${b.body.label.slice(0, 27)}…` : b.body.label
      ctx.fillText(isPlanet && b.total > 0 ? `${label} · ${b.total}` : label, sx, sy)
    }
    ctx.globalAlpha = 1
    const hov = hovered.current
    if (hov?.item) {
      const n = hov.item.node
      const text = n.redacted || !n.label ? 'Restricted memory' : n.label.length > 60 ? `${n.label.slice(0, 59)}…` : n.label
      tooltip(ctx, text, hov.x * k + t.x, (hov.y - hov.r) * k + t.y - 8, pal, w)
    }

    drawn.current = hits
    tree.current = null
    if (p.drift && !reduced.current) requestDraw()
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
      props.highlight, props.matches, props.dimmedAgentKeys, props.drift])

  function applyTransform(target: ZoomTransform, animate: boolean) {
    const canvas = canvasRef.current
    const z = zoomRef.current
    if (!canvas || !z) return
    const sel = select(canvas)
    if (!animate || reduced.current) { z.transform(sel, target); return }
    const from = transform.current
    const start = performance.now()
    const step = (now: number) => {
      const u = Math.min(1, (now - start) / 300)
      const e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2
      const k = Math.exp(Math.log(from.k) + (Math.log(target.k) - Math.log(from.k)) * e)
      // Interpolate the world point at the centre of the screen, so it glides rather than swings.
      const { w, h } = size.current
      const fx = (w / 2 - from.x) / from.k, fy = (h / 2 - from.y) / from.k
      const tx = (w / 2 - target.x) / target.k, ty = (h / 2 - target.y) / target.k
      const cx = fx + (tx - fx) * e, cy = fy + (ty - fy) * e
      z.transform(sel, zoomIdentity.translate(w / 2 - cx * k, h / 2 - cy * k).scale(k))
      if (u < 1) requestAnimationFrame(step)
    }
    requestAnimationFrame(step)
  }

  function flyTo(x: number, y: number, k?: number, bodyId?: string) {
    const { w, h } = size.current
    const scale = k ?? Math.max(transform.current.k, ITEMS_AT * 1.6)
    // Follow the drift, so the target is where the body is now.
    const target = bodyId ? at(x, y, rotation(bodyId, performance.now())) : { x, y }
    applyTransform(zoomIdentity.translate(w / 2 - target.x * scale, h / 2 - target.y * scale).scale(scale), true)
  }

  function fit(animate = true) {
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
      // Far out, clicking a planet flies in to it.
      if (transform.current.k < MOONS_AT) flyTo(hit.body.x, hit.body.y, Math.max(MOONS_AT * 1.5, 220 / (hit.body.extent + 40)), hit.body.body.bodyId)
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
      case 'Escape': propsRef.current.onSelectBody(null); break
      default: return
    }
    e.preventDefault()
  }

  return (
    <canvas ref={canvasRef} tabIndex={0} role="img"
      aria-label="Memory Cosmos. Jizza is the sun and each agent a planet; subjects, files and tools are moons, and each fact or note is a satellite. Use plus and minus to zoom, arrow keys to pan, or switch to the list for a keyboard-friendly view."
      onPointerMove={onPointerMove} onPointerLeave={() => { hovered.current = null; requestDraw() }}
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

function tooltip(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, pal: Palette, maxX: number) {
  ctx.font = '12px Inter, system-ui, sans-serif'
  const w = ctx.measureText(text).width + 12
  const left = Math.min(Math.max(4, x - w / 2), maxX - w - 4)
  ctx.globalAlpha = 0.95
  ctx.fillStyle = pal.bg
  ctx.strokeStyle = pal.orbit
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.rect(left, y - 22, w, 20)
  ctx.fill()
  ctx.stroke()
  ctx.globalAlpha = 1
  ctx.fillStyle = pal.text
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, left + 6, y - 12)
}
