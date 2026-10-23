import { useMemo } from 'react'
import type { CelestialBody, CosmosSnapshot, MemoryNode } from '@/types'

/**
 * Where everything sits on the Cosmos canvas, in world units. The layout is a pure function of the
 * snapshot, so the same memory always draws the same sky: nothing jumps on reload, and loading a
 * body's items never moves a body (its rings are sized from its count up front).
 *
 * Every body follows a Kepler orbit round its parent: an ellipse with the parent at one focus,
 * swept faster near periapsis, with a period that grows as the orbit's size to the power 1.5.
 */

export const SUN_R = 48
const SAT_GAP = 14
const SAT_RING_GAP = 8
const SAT_PER_RING = 60
const TAU = Math.PI * 2

/** Seconds per turn at 1×: a planet 220 units out, and a moon 60 units from its planet. */
const PLANET_PERIOD_S = 24
const MOON_PERIOD_S = 9

/** The shape and timing of one body's orbit round its parent. */
export interface Orbit {
  /** Semi-major axis, in world units. */
  a: number
  /** Eccentricity: 0 is a circle; comets run up to about 0.6. */
  e: number
  /** Direction of periapsis (closest approach), in radians. */
  omega: number
  /** Mean anomaly at sky time zero. */
  m0: number
  /** Seconds per turn at 1×. */
  period: number
}

/** Where a body is relative to its parent `t` seconds into sky time, solving Kepler's equation. */
export function keplerOffset(o: Orbit, t: number) {
  if (o.a === 0) return { x: 0, y: 0 }
  const M = ((o.m0 + (TAU * t) / o.period) % TAU + TAU) % TAU
  let E = o.e < 0.8 ? M : Math.PI
  for (let i = 0; i < 8; i++) {
    const d = (E - o.e * Math.sin(E) - M) / (1 - o.e * Math.cos(E))
    E -= d
    if (Math.abs(d) < 1e-6) break
  }
  const px = o.a * (Math.cos(E) - o.e)
  const py = o.a * Math.sqrt(1 - o.e * o.e) * Math.sin(E)
  const c = Math.cos(o.omega), s = Math.sin(o.omega)
  return { x: px * c - py * s, y: px * s + py * c }
}

/** Kepler's third law: the period grows as the orbit's size to the power 1.5. */
export function periodFor(a: number, aroundSun: boolean) {
  return aroundSun ? PLANET_PERIOD_S * Math.pow(a / 220, 1.5) : MOON_PERIOD_S * Math.pow(Math.max(a, 20) / 60, 1.5)
}

/** A stable number in [lo, hi) from a string, so a body's orbit never depends on load order. */
function spread(key: string, lo: number, hi: number) {
  return lo + (hash(key) % 10_000) / 10_000 * (hi - lo)
}

export interface PlacedBody {
  body: CelestialBody
  x: number
  y: number
  r: number
  /** Semi-major axis of its orbit round its parent; 0 for the sun. */
  orbit: number
  /** The full orbit; `orbit` is its semi-major axis. */
  kepler: Orbit
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
  /** Distance from the body it circles; inner rings turn faster. */
  orbit: number
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

  interface Ring { orbit: number; e: number; omega: number; members: Sized[] }
  interface Sized { body: CelestialBody; r: number; satExtent: number; extent: number; rings: Ring[] }
  const sizeOf = (b: CelestialBody): Sized => {
    const total = totalOf(b)
    const r = radiusOf(b, total)
    const satExtent = b.memoryCount > 0 ? r + SAT_GAP + (satRings(b.memoryCount) - 1) * SAT_RING_GAP + 3 : r
    // Moons (not planets: the sun's planets are laid out separately) in rings around this body. A
    // ring's moons share one ellipse, spaced by mean anomaly, so they never catch each other up;
    // rings sit far enough apart that one's farthest point clears the next one's nearest.
    const moons = (kids.get(b.bodyId) ?? []).filter(c => c.type !== 'AGENT').map(sizeOf)
    const rings: Ring[] = []
    let inner = satExtent + 6
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
      const key = `${b.bodyId}#${rings.length}`
      const e = spread(`${key}e`, 0.02, 0.08)
      const widest = Math.max(...members.map(m => m.extent))
      orbit = Math.max(orbit, (inner + widest + 4) / (1 - e))
      rings.push({ orbit, e, omega: spread(`${key}w`, 0, TAU), members })
      // Comets swing further out than their ring, so the next ring starts beyond them.
      const reach = Math.max(...members.map(m => orbit * (1 + eccentricityOf(m.body, orbit, e, satExtent, m.extent)) + m.extent))
      inner = reach + 6
      orbit = Math.max(orbit + 22, inner + widest)
    }
    const extent = rings.length ? inner - 6 : satExtent
    return { body: b, r, satExtent, extent, rings }
  }

  const placed: PlacedBody[] = []
  const byId = new Map<string, PlacedBody>()
  const children = new Map<string, PlacedBody[]>()
  const place = (s: Sized, kepler: Orbit, px: number, py: number) => {
    const at = keplerOffset(kepler, 0)
    const x = px + at.x, y = py + at.y
    const p: PlacedBody = {
      body: s.body, x, y, r: s.r, orbit: kepler.a, kepler, parentX: px, parentY: py, total: totalOf(s.body),
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
        const e = eccentricityOf(m.body, ring.orbit, ring.e, s.satExtent, m.extent)
        place(m, {
          a: ring.orbit, e,
          // A comet's long ellipse points its own way; everything else shares the ring's.
          omega: e === ring.e ? ring.omega : spread(`${m.body.bodyId}w`, 0, TAU),
          m0: phase + (TAU * mi) / ring.members.length,
          period: periodFor(ring.orbit, s.body.type === 'SUN'),
        }, x, y)
      })
    })
  }

  const sunSized = sizeOf(sun)
  place(sunSized, { a: 0, e: 0, omega: 0, m0: 0, period: 1 }, 0, 0)

  // Planets: each on its own slightly eccentric orbit, with its nearest approach still clear of the
  // previous planet's farthest, at an angle taken from its id so adding an agent doesn't spin the
  // others round.
  const planets = (kids.get(sun.bodyId) ?? []).filter(c => c.type === 'AGENT').map(sizeOf)
  let reach = sunSized.extent
  planets.forEach((p, i) => {
    const e = spread(`${p.body.bodyId}e`, 0.03, 0.12)
    const a = Math.max(220 + i * 180, (reach + p.extent + 40) / (1 - e))
    place(p, {
      a, e, omega: spread(`${p.body.bodyId}w`, 0, TAU), m0: (hash(p.body.bodyId) % 360) * Math.PI / 180,
      period: periodFor(a, true),
    }, 0, 0)
    reach = a * (1 + e) + p.extent
  })

  return { bodies: placed, byId, children, sunId: sun.bodyId, radius: Math.max(400, reach + 40) }
}

/**
 * Workflows fly as comets, on long ellipses that dive in close and swing far out; the dive stops
 * short of their planet's own satellites. Everything else keeps its ring's gentle eccentricity.
 */
function eccentricityOf(b: CelestialBody, a: number, ringE: number, parentSatExtent: number, extent: number) {
  if (b.type !== 'WORKFLOW') return ringE
  const deepest = 1 - (parentSatExtent + 8 + extent) / a
  return Math.max(ringE, Math.min(spread(`${b.bodyId}e`, 0.45, 0.62), deepest))
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
      r: itemRadius(items[i]), parentX: body.x, parentY: body.y, orbit,
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
