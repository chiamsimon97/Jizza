import { describe, expect, it } from 'vitest'
import type { CelestialBody, CosmosSnapshot } from '@/types'
import { computeLayout, keplerOffset, periodFor, type Orbit } from './useCosmosLayout'

const body = (bodyId: string, type: CelestialBody['type'], parentBodyId: string | null, memoryCount = 3, agentKey = 'other'): CelestialBody => ({
  bodyId, type, parentBodyId, label: bodyId, agentKey, tier: type === 'AGENT' ? 1 : 2, memoryCount, sharedCount: 0, lastActivityAt: null, meta: null,
})

function snapshot(): CosmosSnapshot {
  const bodies: CelestialBody[] = [body('sun', 'SUN', null, 5, 'jizza')]
  for (const key of ['health', 'finance', 'professional', 'other']) {
    bodies.push(body(key, 'AGENT', 'sun', 10, key))
    for (let i = 0; i < 6; i++) bodies.push(body(`${key}-moon-${i}`, 'SUBJECT', key, 2 + i, key))
    bodies.push(body(`${key}-notes`, 'NOTES', key, 4, key))
    bodies.push(body(`${key}-flow`, 'WORKFLOW', key, 0, key))
  }
  return { bodies, links: [], generatedAt: '2026-10-23T00:00:00Z' }
}

const dist = (p: { x: number; y: number }) => Math.hypot(p.x, p.y)

describe('keplerOffset', () => {
  const orbit: Orbit = { a: 100, e: 0.5, omega: 0, m0: 0, period: 10 }

  it('starts at periapsis and reaches apoapsis half a period later', () => {
    expect(dist(keplerOffset(orbit, 0))).toBeCloseTo(50)
    expect(dist(keplerOffset(orbit, 5))).toBeCloseTo(150)
  })

  it('moves faster near the parent than far from it', () => {
    const step = (t: number) => Math.hypot(keplerOffset(orbit, t + 0.05).x - keplerOffset(orbit, t).x, keplerOffset(orbit, t + 0.05).y - keplerOffset(orbit, t).y)
    // Speed at periapsis over apoapsis is (1 + e) / (1 - e): three times for e = 0.5.
    expect(step(0) / step(5)).toBeCloseTo(3, 0)
  })

  it('comes back round after one period', () => {
    const a = keplerOffset({ ...orbit, m0: 1.3, omega: 2 }, 0.7)
    const b = keplerOffset({ ...orbit, m0: 1.3, omega: 2 }, 10.7)
    expect(b.x).toBeCloseTo(a.x)
    expect(b.y).toBeCloseTo(a.y)
  })
})

describe('periodFor', () => {
  it('follows Kepler’s third law', () => {
    expect(periodFor(880, true) / periodFor(220, true)).toBeCloseTo(8)
  })
})

describe('computeLayout', () => {
  const layout = computeLayout(snapshot())
  const planets = layout.bodies.filter(b => b.body.type === 'AGENT').sort((a, b) => a.orbit - b.orbit)

  it('keeps each planet’s closest approach clear of the one inside it', () => {
    for (let i = 1; i < planets.length; i++) {
      const inner = planets[i - 1], outer = planets[i]
      expect(outer.orbit * (1 - outer.kepler.e) - outer.extent).toBeGreaterThan(inner.orbit * (1 + inner.kepler.e) + inner.extent)
    }
  })

  it('gives outer planets longer years', () => {
    for (let i = 1; i < planets.length; i++) expect(planets[i].kepler.period).toBeGreaterThan(planets[i - 1].kepler.period)
  })

  it('sends workflows round on comet-like ellipses that stay clear of their planet', () => {
    const comet = layout.byId.get('health-flow')!
    const planet = layout.byId.get('health')!
    expect(comet.kepler.e).toBeGreaterThan(0.2)
    expect(comet.orbit * (1 - comet.kepler.e)).toBeGreaterThan(planet.satExtent)
  })

  it('is the same on every load', () => {
    const again = computeLayout(snapshot())
    expect(again.bodies.map(b => [b.x, b.y, b.kepler.e])).toEqual(layout.bodies.map(b => [b.x, b.y, b.kepler.e]))
  })
})
