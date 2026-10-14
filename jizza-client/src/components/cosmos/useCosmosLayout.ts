import { useMemo } from 'react'
import type { CelestialBody, CosmosSnapshot, MemoryNode } from '@/types'

/**
 * Where everything sits on the Cosmos canvas, in world units. The layout is a pure function of the
 * snapshot, so the same memory always draws the same sky: no physics, nothing jumps on reload, and
 * loading a body's items never moves a body (its rings are sized from its count up front).
 */

export const SUN_R = 48
const SAT_GAP = 14
const SAT_RING_GAP = 8
const SAT_PER_RING = 60

export interface PlacedBody {
  body: CelestialBody
  x: number
  y: number
  r: number
  /** Distance from the parent's centre; 0 for the sun. */
  orbit: number
  parentX: number
  parentY: number
  /** Facts and notes at this body and its moons (for the sun, not the planets). */
  total: number
  /** How far this body's own satellites reach from its centre. */
  satExtent: number
  /** How far this body's whole system (moons included) reaches. */
  extent: number
  /** Angle offset for this body's satellites, so neighbours don't line up. */
  satPhase: number
}

export interface PlacedItem {
  node: MemoryNode
  x: number
  y: number
  r: number
  parentX: number
  parentY: number
}

export interface CosmosLayout {
  bodies: PlacedBody[]
  byId: Map<string, PlacedBody>
  children: Map<string, PlacedBody[]>
  sunId: string | null
  /** Outer edge of the whole sky, for fitting it to the screen. */
  radius: number
}

/** FNV-1a: a small, stable hash so a body's angle never depends on load order. */
export function hash(s: string) {
  let h = 0x811c9dc5
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

const TYPE_ORDER: Record<string, number> = {
  SUBJECT: 0, NOTES: 1, FILE: 2, CONNECTOR: 3, WORKFLOW: 4, SKILL: 5, AGENT: 6, SUN: 7,
}

function radiusOf(b: CelestialBody, total: number) {
  if (b.type === 'SUN') return SUN_R
  if (b.type === 'AGENT') return Math.min(48, 18 + 6 * Math.log2(1 + total))
  if (b.tier <= 2) return Math.min(16, 6 + 2 * Math.log2(1 + total))
  return Math.min(10, 4 + Math.log2(1 + total))
}

function satRings(count: number) {
  return Math.ceil(count / SAT_PER_RING)
}

export function computeLayout(snapshot: CosmosSnapshot | undefined): CosmosLayout {
  const empty: CosmosLayout = { bodies: [], byId: new Map(), children: new Map(), sunId: null, radius: 400 }
  if (!snapshot || snapshot.bodies.length === 0) return empty

  const raw = new Map(snapshot.bodies.map(b => [b.bodyId, b]))
  const kids = new Map<string, CelestialBody[]>()
  let sun: CelestialBody | undefined
  for (const b of snapshot.bodies) {
    if (b.type === 'SUN') { sun = b; continue }
    const parent = b.parentBodyId && raw.has(b.parentBodyId) ? b.parentBodyId : null
    if (!parent) continue
    const list = kids.get(parent) ?? []
    list.push(b)
    kids.set(parent, list)
  }
  if (!sun) return empty
  for (const list of kids.values()) {
    list.sort((a, b) => (TYPE_ORDER[a.type] - TYPE_ORDER[b.type])
      || (a.type === 'AGENT' ? a.agentKey.localeCompare(b.agentKey) : a.label.localeCompare(b.label))
      || a.bodyId.localeCompare(b.bodyId))
  }

  // Totals and sizes, bottom up.
  const totals = new Map<string, number>()
  const totalOf = (b: CelestialBody): number => {
    const cached = totals.get(b.bodyId)
    if (cached != null) return cached
    let t = b.memoryCount
    // The sun counts its own orbit; each planet counts its own system.
    for (const c of kids.get(b.bodyId) ?? []) if (c.type !== 'AGENT') t += totalOf(c)
    totals.set(b.bodyId, t)
    return t
  }

  interface Sized { body: CelestialBody; r: number; satExtent: number; extent: number; rings: { orbit: number; members: Sized[] }[] }
  const sizeOf = (b: CelestialBody): Sized => {
    const total = totalOf(b)
    const r = radiusOf(b, total)
    const satExtent = b.memoryCount > 0 ? r + SAT_GAP + (satRings(b.memoryCount) - 1) * SAT_RING_GAP + 3 : r
    // Moons (not planets: the sun's planets are laid out separately) in rings around this body.
    const moons = (kids.get(b.bodyId) ?? []).filter(c => c.type !== 'AGENT').map(sizeOf)
    const rings: Sized['rings'] = []
    let orbit = Math.max(r + 40, satExtent + 18)
    let i = 0
    while (i < moons.length) {
      const members: Sized[] = []
      let used = 0
      const room = 2 * Math.PI * orbit
      while (i < moons.length && (members.length === 0 || used + 2 * moons[i].extent + 10 <= room)) {
        used += 2 * moons[i].extent + 10
        members.push(moons[i++])
      }
      rings.push({ orbit, members })
      const widest = Math.max(...members.map(m => m.extent))
      orbit += Math.max(22, 2 * widest + 10)
    }
    const last = rings[rings.length - 1]
    const extent = last ? last.orbit + Math.max(...last.members.map(m => m.extent)) : satExtent
    return { body: b, r, satExtent, extent, rings }
  }

  const placed: PlacedBody[] = []
  const byId = new Map<string, PlacedBody>()
  const children = new Map<string, PlacedBody[]>()
  const place = (s: Sized, x: number, y: number, orbit: number, px: number, py: number) => {
    const p: PlacedBody = {
      body: s.body, x, y, r: s.r, orbit, parentX: px, parentY: py, total: totalOf(s.body),
      satExtent: s.satExtent, extent: s.extent, satPhase: (hash(s.body.bodyId) % 360) * Math.PI / 180,
    }
    placed.push(p)
    byId.set(p.body.bodyId, p)
    if (s.body.parentBodyId) {
      const list = children.get(s.body.parentBodyId) ?? []
      list.push(p)
      children.set(s.body.parentBodyId, list)
    }
    s.rings.forEach((ring, ri) => {
      const phase = (hash(`${s.body.bodyId}#${ri}`) % 360) * Math.PI / 180
      ring.members.forEach((m, mi) => {
        const a = phase + (2 * Math.PI * mi) / ring.members.length
        place(m, x + Math.cos(a) * ring.orbit, y + Math.sin(a) * ring.orbit, ring.orbit, x, y)
      })
    })
  }

  const sunSized = sizeOf(sun)
  place(sunSized, 0, 0, 0, 0, 0)

  // Planets: each on its own orbit, far enough out that neighbouring systems never overlap, at an
  // angle taken from its id so adding an agent doesn't spin the others round.
  const planets = (kids.get(sun.bodyId) ?? []).filter(c => c.type === 'AGENT').map(sizeOf)
  let prevOrbit = 0
  let prevExtent = sunSized.extent
  planets.forEach((p, i) => {
    const orbit = Math.max(220 + i * 180, prevOrbit + prevExtent + p.extent + 40)
    const a = (hash(p.body.bodyId) % 360) * Math.PI / 180
    place(p, Math.cos(a) * orbit, Math.sin(a) * orbit, orbit, 0, 0)
    prevOrbit = orbit
    prevExtent = p.extent
  })

  return { bodies: placed, byId, children, sunId: sun.bodyId, radius: Math.max(400, prevOrbit + prevExtent + 40) }
}

export function useCosmosLayout(snapshot: CosmosSnapshot | undefined) {
  return useMemo(() => computeLayout(snapshot), [snapshot])
}

/** A satellite's size: the more an agent has read it, the bigger it is. */
export function itemRadius(n: MemoryNode) {
  return 2 + Math.min(4, Math.log2(1 + n.accessCount))
}

/** Places a body's loaded items in rings just outside it, in the order the server gave them. */
export function placeItems(body: PlacedBody, items: MemoryNode[]): PlacedItem[] {
  const out: PlacedItem[] = []
  for (let i = 0; i < items.length; i++) {
    const ring = Math.floor(i / SAT_PER_RING)
    const inRing = Math.min(SAT_PER_RING, items.length - ring * SAT_PER_RING)
    const orbit = body.r + SAT_GAP + ring * SAT_RING_GAP
    const a = body.satPhase + (ring % 2 ? Math.PI / SAT_PER_RING : 0) + (2 * Math.PI * (i % SAT_PER_RING)) / inRing
    out.push({
      node: items[i], x: body.x + Math.cos(a) * orbit, y: body.y + Math.sin(a) * orbit,
      r: itemRadius(items[i]), parentX: body.x, parentY: body.y,
    })
  }
  return out
}

/** Recently used memories glow; ones nobody has touched in months fade back. */
export function recencyAlpha(n: MemoryNode, now = Date.now()) {
  const at = Date.parse(n.lastAccessedAt ?? n.createdAt)
  if (Number.isNaN(at)) return 0.6
  const days = Math.max(0, (now - at) / 86_400_000)
  return 0.35 + 0.65 * Math.exp(-days / 30)
}
