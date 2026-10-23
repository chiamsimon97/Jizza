import { hash } from './useCosmosLayout'

/**
 * How each kind of body is painted: a star for Jizza, a planet per agent (earth-like, banded, ringed,
 * icy or rocky), and for everything round them a moon, ice moon, asteroid, space station, comet or
 * pulsar. Everything is drawn in world units on an already-transformed context, lit from the sun.
 */

export type RGB = [number, number, number]
const TAU = Math.PI * 2

export const mix = (a: RGB, b: RGB, t: number): RGB => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
export const rgba = (c: RGB, a = 1) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`
const WHITE: RGB = [255, 255, 255]
const BLACK: RGB = [0, 0, 0]

/** A small seeded generator, so a body's craters and continents are the same on every frame. */
function seeded(key: string) {
  let a = hash(key) || 1
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** What each kind of body appears as in the sky. */
export const OBJECT_NAME: Record<string, string> = {
  SUN: 'Star', AGENT: 'Planet', SUBJECT: 'Moon', NOTES: 'Ice moon', FILE: 'Asteroid',
  CONNECTOR: 'Space station', WORKFLOW: 'Comet', SKILL: 'Pulsar',
}

export type PlanetStyle = 'terran' | 'gas' | 'ringed' | 'ice' | 'rocky'
const KNOWN_STYLES: Record<string, PlanetStyle> = { health: 'terran', finance: 'ringed', professional: 'ice', other: 'rocky' }
const STYLES: PlanetStyle[] = ['terran', 'gas', 'ringed', 'ice', 'rocky']

export function planetStyle(agentKey: string, bodyId: string): PlanetStyle {
  return KNOWN_STYLES[agentKey] ?? STYLES[hash(bodyId) % STYLES.length]
}

// ── the sky behind ──────────────────────────────────────────────────────────────────────────────

interface Star { x: number; y: number; r: number; depth: number; phase: number; rate: number; color: RGB; glint: boolean }
interface Nebula { x: number; y: number; r: number; depth: number; color: RGB; a: number }

const STAR_COLORS: RGB[] = [[255, 255, 255], [200, 220, 255], [255, 240, 210], [255, 214, 170], [180, 200, 255]]
const { STARS, NEBULAE } = (() => {
  const rnd = seeded('jizza-sky')
  const stars: Star[] = Array.from({ length: 320 }, (_, i) => ({
    x: rnd(), y: rnd(), r: 0.3 + Math.pow(rnd(), 3) * 1.6, depth: 0.01 + rnd() * 0.06, phase: rnd() * TAU,
    rate: 0.5 + rnd() * 2, color: STAR_COLORS[Math.floor(rnd() * STAR_COLORS.length)], glint: i < 8,
  }))
  const nebulae: Nebula[] = [
    { x: 0.2, y: 0.25, r: 0.55, depth: 0.015, color: [124, 58, 237], a: 0.16 },
    { x: 0.85, y: 0.7, r: 0.6, depth: 0.02, color: [14, 165, 233], a: 0.11 },
    { x: 0.6, y: 0.1, r: 0.4, depth: 0.025, color: [236, 72, 153], a: 0.08 },
    { x: 0.1, y: 0.9, r: 0.45, depth: 0.018, color: [45, 212, 191], a: 0.06 },
  ]
  return { STARS: stars, NEBULAE: nebulae }
})()

/** Deep space, nebulae and twinkling stars, in screen space; they drift a little with the pan. */
export function drawSpace(ctx: CanvasRenderingContext2D, w: number, h: number, panX: number, panY: number, now: number, moving: boolean) {
  const g = ctx.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, Math.hypot(w, h) / 2)
  g.addColorStop(0, '#0d1230')
  g.addColorStop(1, '#03040a')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, w, h)
  const span = Math.max(w, h)
  ctx.globalCompositeOperation = 'lighter'
  for (const n of NEBULAE) {
    const nx = n.x * w + panX * n.depth, ny = n.y * h + panY * n.depth
    const ng = ctx.createRadialGradient(nx, ny, 0, nx, ny, n.r * span)
    ng.addColorStop(0, rgba(n.color, n.a))
    ng.addColorStop(0.5, rgba(n.color, n.a * 0.35))
    ng.addColorStop(1, rgba(n.color, 0))
    ctx.fillStyle = ng
    ctx.fillRect(0, 0, w, h)
  }
  for (const st of STARS) {
    const sx = (((st.x * w + panX * st.depth) % w) + w) % w
    const sy = (((st.y * h + panY * st.depth) % h) + h) % h
    const tw = moving ? 0.55 + 0.45 * Math.sin(now / 1000 * st.rate + st.phase) : 0.8
    ctx.globalAlpha = Math.min(1, (0.35 + st.r * 0.4) * tw)
    ctx.fillStyle = rgba(st.color)
    ctx.beginPath()
    ctx.arc(sx, sy, st.r, 0, TAU)
    ctx.fill()
    if (st.glint) {
      // A few bright stars catch the lens.
      const len = 5 + 4 * tw
      ctx.globalAlpha = 0.35 * tw
      ctx.strokeStyle = rgba(st.color)
      ctx.lineWidth = 0.6
      ctx.beginPath()
      ctx.moveTo(sx - len, sy); ctx.lineTo(sx + len, sy)
      ctx.moveTo(sx, sy - len); ctx.lineTo(sx, sy + len)
      ctx.stroke()
    }
  }
  ctx.globalAlpha = 1
  ctx.globalCompositeOperation = 'source-over'
}

// ── shared pieces ───────────────────────────────────────────────────────────────────────────────

/** Night side and a soft highlight, from the direction of the sun. */
function shade(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, light: number, depth = 0.85) {
  const lx = x + Math.cos(light) * r * 0.55, ly = y + Math.sin(light) * r * 0.55
  const g = ctx.createRadialGradient(lx, ly, r * 0.05, lx, ly, r * 1.9)
  g.addColorStop(0, 'rgba(255,255,255,0.22)')
  g.addColorStop(0.35, 'rgba(255,255,255,0)')
  g.addColorStop(0.62, `rgba(0,0,0,${depth * 0.45})`)
  g.addColorStop(1, `rgba(0,0,0,${depth})`)
  ctx.fillStyle = g
  ctx.fillRect(x - r, y - r, 2 * r, 2 * r)
}

function clipCircle(ctx: CanvasRenderingContext2D, x: number, y: number, r: number) {
  ctx.beginPath()
  ctx.arc(x, y, r, 0, TAU)
  ctx.clip()
}

function craters(ctx: CanvasRenderingContext2D, key: string, x: number, y: number, r: number, base: RGB, count: number) {
  const rnd = seeded(`${key}craters`)
  for (let i = 0; i < count; i++) {
    const a = rnd() * TAU, d = Math.sqrt(rnd()) * r * 0.8, cr = r * (0.08 + rnd() * 0.18)
    const cx = x + Math.cos(a) * d, cy = y + Math.sin(a) * d
    ctx.fillStyle = rgba(mix(base, BLACK, 0.3), 0.75)
    ctx.beginPath(); ctx.arc(cx, cy, cr, 0, TAU); ctx.fill()
    ctx.fillStyle = rgba(mix(base, WHITE, 0.25), 0.5)
    ctx.beginPath(); ctx.arc(cx - cr * 0.2, cy - cr * 0.2, cr * 0.7, 0, TAU); ctx.fill()
    ctx.fillStyle = rgba(mix(base, BLACK, 0.15), 0.9)
    ctx.beginPath(); ctx.arc(cx + cr * 0.08, cy + cr * 0.08, cr * 0.6, 0, TAU); ctx.fill()
  }
}

/** Draws something that scrolls round a planet of radius r, at x and wrapped onto the far side. */
function wrapped(x: number, r: number, draw: (px: number) => void) {
  const px = (((x % (2 * r)) + 2 * r) % (2 * r)) - r
  draw(px - 2 * r); draw(px); draw(px + 2 * r)
}

function glow(ctx: CanvasRenderingContext2D, x: number, y: number, inner: number, outer: number, c: RGB, a: number) {
  const g = ctx.createRadialGradient(x, y, inner, x, y, outer)
  g.addColorStop(0, rgba(c, a))
  g.addColorStop(1, rgba(c, 0))
  ctx.fillStyle = g
  ctx.beginPath()
  ctx.arc(x, y, outer, 0, TAU)
  ctx.fill()
}

// ── the bodies ──────────────────────────────────────────────────────────────────────────────────

/** Jizza's star: a white-hot core, an orange limb, sunspots, and a corona that breathes. */
export function drawStar(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, now: number, moving: boolean) {
  const breathe = moving ? Math.sin(now / 1600) : 0
  ctx.globalCompositeOperation = 'lighter'
  glow(ctx, x, y, r * 0.6, r * (4.2 + 0.25 * breathe), [255, 170, 60], 0.32)
  glow(ctx, x, y, r * 0.9, r * (1.9 + 0.1 * breathe), [255, 220, 130], 0.5)
  // Corona streamers, turning slowly.
  const spin = moving ? now / 40_000 : 0
  ctx.fillStyle = 'rgba(255,200,110,0.10)'
  for (let i = 0; i < 12; i++) {
    const a = spin + (i * TAU) / 12
    const len = r * (2.1 + 0.5 * Math.sin(i * 1.7 + (moving ? now / 900 : 0)))
    ctx.beginPath()
    ctx.moveTo(x + Math.cos(a - 0.14) * r * 0.9, y + Math.sin(a - 0.14) * r * 0.9)
    ctx.lineTo(x + Math.cos(a) * len, y + Math.sin(a) * len)
    ctx.lineTo(x + Math.cos(a + 0.14) * r * 0.9, y + Math.sin(a + 0.14) * r * 0.9)
    ctx.fill()
  }
  ctx.globalCompositeOperation = 'source-over'
  const g = ctx.createRadialGradient(x - r * 0.15, y - r * 0.15, 0, x, y, r)
  g.addColorStop(0, '#fffdf2')
  g.addColorStop(0.45, '#ffe58a')
  g.addColorStop(0.85, '#ffab36')
  g.addColorStop(1, '#f2741c')
  ctx.fillStyle = g
  ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill()
  ctx.save()
  clipCircle(ctx, x, y, r)
  const rnd = seeded('sunspots')
  for (let i = 0; i < 5; i++) {
    const a = rnd() * TAU + (moving ? now / 30_000 : 0), d = rnd() * r * 0.7
    ctx.fillStyle = 'rgba(190,80,20,0.25)'
    ctx.beginPath(); ctx.arc(x + Math.cos(a) * d, y + Math.sin(a) * d * 0.6, r * (0.05 + rnd() * 0.08), 0, TAU); ctx.fill()
  }
  ctx.restore()
}

function planetRings(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, tilt: number, base: RGB, front: boolean) {
  const bands: [number, number, number][] = [[1.45, 0.10, 0.35], [1.68, 0.18, 0.6], [1.95, 0.12, 0.45], [2.2, 0.06, 0.25]]
  for (const [f, wf, a] of bands) {
    ctx.strokeStyle = rgba(mix(base, [235, 215, 175], 0.6), a)
    ctx.lineWidth = r * wf
    ctx.beginPath()
    // The near half of the rings crosses in front of the planet, the far half passes behind it.
    ctx.ellipse(x, y, r * f, r * f * 0.32, tilt, front ? 0 : Math.PI, front ? Math.PI : TAU)
    ctx.stroke()
  }
}

/** An agent's planet, styled from its agent: earth-like, banded gas giant, ringed, icy or rocky. */
export function drawPlanet(ctx: CanvasRenderingContext2D, key: string, style: PlanetStyle, x: number, y: number, r: number,
  base: RGB, light: number, now: number, moving: boolean) {
  const rnd = seeded(key)
  const tilt = (rnd() - 0.5) * 0.7
  // Surface features drift across the disk as the planet turns on its axis.
  const turn = moving ? (now / 1000) / (30 + rnd() * 30) : 0
  const ringed = style === 'ringed'
  if (ringed) planetRings(ctx, x, y, r, tilt, base, false)
  if (style !== 'rocky') glow(ctx, x, y, r * 0.95, r * 1.3, mix(base, [150, 200, 255], style === 'terran' ? 0.6 : 0.2), 0.45)

  ctx.save()
  clipCircle(ctx, x, y, r)
  ctx.translate(x, y)
  ctx.rotate(tilt)
  if (style === 'terran') {
    const ocean = mix(base, [25, 85, 170], 0.8)
    ctx.fillStyle = rgba(ocean); ctx.fillRect(-r, -r, 2 * r, 2 * r)
    const land = mix(base, [70, 135, 70], 0.75)
    for (let i = 0; i < 6; i++) {
      const cx = rnd() * 2 * r, cy = (rnd() - 0.5) * 1.4 * r, rx = r * (0.2 + rnd() * 0.35), ry = r * (0.12 + rnd() * 0.22)
      const rot = (rnd() - 0.5) * 1.2
      ctx.fillStyle = rgba(mix(land, [150, 120, 70], rnd() * 0.5))
      wrapped(cx + turn * 2 * r, r, px => { ctx.beginPath(); ctx.ellipse(px, cy, rx, ry, rot, 0, TAU); ctx.fill() })
    }
    ctx.fillStyle = 'rgba(255,255,255,0.85)'
    ctx.beginPath(); ctx.ellipse(0, -r, r * 0.7, r * 0.18, 0, 0, TAU); ctx.fill()
    ctx.beginPath(); ctx.ellipse(0, r, r * 0.6, r * 0.15, 0, 0, TAU); ctx.fill()
    ctx.fillStyle = 'rgba(255,255,255,0.38)'
    for (let i = 0; i < 7; i++) {
      const cx = rnd() * 2 * r, cy = (rnd() - 0.5) * 1.6 * r, rx = r * (0.25 + rnd() * 0.4), ry = r * 0.05 + rnd() * r * 0.06
      wrapped(cx + turn * 3.1 * r, r, px => { ctx.beginPath(); ctx.ellipse(px, cy, rx, ry, 0, 0, TAU); ctx.fill() })
    }
  } else if (style === 'gas' || style === 'ringed') {
    const light1 = mix(base, [245, 225, 190], 0.45), dark1 = mix(base, [120, 70, 40], 0.35)
    ctx.fillStyle = rgba(light1); ctx.fillRect(-r, -r, 2 * r, 2 * r)
    let yy = -r
    let i = 0
    while (yy < r) {
      const hgt = r * (0.07 + rnd() * 0.16)
      const c = i % 2 ? mix(light1, WHITE, 0.15 + rnd() * 0.2) : mix(dark1, light1, rnd() * 0.5)
      ctx.fillStyle = rgba(c, 0.85)
      // Bands ripple gently as the planet turns.
      const wob = Math.sin(turn * 5 + i) * r * 0.015
      ctx.fillRect(-r, yy + wob, 2 * r, hgt)
      yy += hgt
      i++
    }
    // A great storm, carried round with the clouds.
    const sx = ((turn * 1.6 * r + rnd() * 2 * r) % (2.6 * r)) - 1.3 * r
    const sy = r * (0.2 + rnd() * 0.25)
    ctx.fillStyle = rgba(mix(base, [200, 90, 60], 0.55), 0.85)
    ctx.beginPath(); ctx.ellipse(sx, sy, r * 0.22, r * 0.11, 0, 0, TAU); ctx.fill()
    ctx.fillStyle = rgba(mix(base, [255, 230, 210], 0.6), 0.6)
    ctx.beginPath(); ctx.ellipse(sx, sy, r * 0.12, r * 0.05, 0, 0, TAU); ctx.fill()
  } else if (style === 'ice') {
    const c = mix(base, [170, 220, 250], 0.55)
    const g = ctx.createLinearGradient(0, -r, 0, r)
    g.addColorStop(0, rgba(mix(c, WHITE, 0.3))); g.addColorStop(0.5, rgba(c)); g.addColorStop(1, rgba(mix(c, [30, 60, 140], 0.35)))
    ctx.fillStyle = g; ctx.fillRect(-r, -r, 2 * r, 2 * r)
    for (let i = 0; i < 5; i++) {
      ctx.fillStyle = rgba(mix(c, WHITE, 0.4), 0.18)
      ctx.fillRect(-r, -r + (i + 0.5) * (2 * r / 5) + Math.sin(turn * 4 + i) * r * 0.03, 2 * r, r * 0.08)
    }
    const sx = ((turn * 1.2 * r) % (2.6 * r)) - 1.3 * r
    ctx.fillStyle = 'rgba(20,40,110,0.35)'
    ctx.beginPath(); ctx.ellipse(sx, -r * 0.3, r * 0.18, r * 0.1, 0, 0, TAU); ctx.fill()
  } else {
    const c = mix(base, [175, 95, 60], 0.55)
    ctx.fillStyle = rgba(c); ctx.fillRect(-r, -r, 2 * r, 2 * r)
    for (let i = 0; i < 6; i++) {
      ctx.fillStyle = rgba(mix(c, BLACK, 0.25), 0.4)
      ctx.beginPath(); ctx.ellipse((rnd() - 0.5) * 1.6 * r, (rnd() - 0.5) * 1.6 * r, r * (0.2 + rnd() * 0.3), r * (0.1 + rnd() * 0.15), rnd() * TAU, 0, TAU); ctx.fill()
    }
    ctx.rotate(-tilt)
    craters(ctx, key, 0, 0, r, c, 7)
    ctx.fillStyle = 'rgba(255,255,255,0.7)'
    ctx.beginPath(); ctx.ellipse(0, -r * 0.98, r * 0.4, r * 0.12, 0, 0, TAU); ctx.fill()
  }
  ctx.restore()
  ctx.save()
  clipCircle(ctx, x, y, r)
  shade(ctx, x, y, r, light)
  ctx.restore()
  if (ringed) planetRings(ctx, x, y, r, tilt, base, true)
}

/** A person, place or thing: a grey, cratered moon with a tint of its agent's colour. */
export function drawMoon(ctx: CanvasRenderingContext2D, key: string, x: number, y: number, r: number, base: RGB, light: number) {
  const c = mix(base, [175, 175, 182], 0.75)
  ctx.save()
  clipCircle(ctx, x, y, r)
  ctx.fillStyle = rgba(c); ctx.fillRect(x - r, y - r, 2 * r, 2 * r)
  const rnd = seeded(`${key}maria`)
  for (let i = 0; i < 3; i++) {
    ctx.fillStyle = rgba(mix(c, BLACK, 0.2), 0.45)
    ctx.beginPath(); ctx.ellipse(x + (rnd() - 0.5) * r, y + (rnd() - 0.5) * r, r * (0.2 + rnd() * 0.25), r * (0.15 + rnd() * 0.2), rnd() * TAU, 0, TAU); ctx.fill()
  }
  craters(ctx, key, x, y, r, c, 6)
  shade(ctx, x, y, r, light, 0.9)
  ctx.restore()
}

/** An agent's own notes: a bright icy moon streaked with cracks, with a faint frosty halo. */
export function drawIceMoon(ctx: CanvasRenderingContext2D, key: string, x: number, y: number, r: number, base: RGB, light: number) {
  const c = mix(base, [225, 240, 255], 0.72)
  glow(ctx, x, y, r * 0.9, r * 1.6, [200, 230, 255], 0.25)
  ctx.save()
  clipCircle(ctx, x, y, r)
  ctx.fillStyle = rgba(c); ctx.fillRect(x - r, y - r, 2 * r, 2 * r)
  const rnd = seeded(`${key}lineae`)
  ctx.strokeStyle = rgba(mix(base, [150, 85, 60], 0.55), 0.55)
  ctx.lineWidth = r * 0.06
  for (let i = 0; i < 6; i++) {
    const a = rnd() * TAU
    ctx.beginPath()
    ctx.moveTo(x + Math.cos(a) * r, y + Math.sin(a) * r)
    ctx.quadraticCurveTo(x + (rnd() - 0.5) * r, y + (rnd() - 0.5) * r, x + Math.cos(a + 2 + rnd()) * r, y + Math.sin(a + 2 + rnd()) * r)
    ctx.stroke()
  }
  shade(ctx, x, y, r, light, 0.75)
  ctx.restore()
}

/** A file: a lumpy, tumbling asteroid. */
export function drawAsteroid(ctx: CanvasRenderingContext2D, key: string, x: number, y: number, r: number, base: RGB, light: number, spin: number) {
  const rnd = seeded(`${key}shape`)
  const n = 11
  const radii = Array.from({ length: n }, () => 0.68 + rnd() * 0.32)
  const c = mix(base, [125, 108, 92], 0.62)
  ctx.save()
  ctx.beginPath()
  for (let i = 0; i <= n; i++) {
    const a = spin + (i * TAU) / n
    const rr = r * radii[i % n]
    if (i === 0) ctx.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr); else ctx.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr)
  }
  ctx.closePath()
  ctx.fillStyle = rgba(c)
  ctx.fill()
  ctx.clip()
  ctx.translate(x, y); ctx.rotate(spin); ctx.translate(-x, -y)
  craters(ctx, key, x, y, r * 0.8, c, 3)
  ctx.restore()
  ctx.save()
  ctx.beginPath()
  for (let i = 0; i <= n; i++) {
    const a = spin + (i * TAU) / n
    const rr = r * radii[i % n]
    if (i === 0) ctx.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr); else ctx.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr)
  }
  ctx.closePath()
  ctx.clip()
  shade(ctx, x, y, r, light, 0.85)
  ctx.restore()
}

/** A connector: a space station with solar wings, turning slowly, its beacon blinking. */
export function drawStation(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, base: RGB, spin: number, now: number, moving: boolean) {
  ctx.save()
  ctx.translate(x, y)
  ctx.rotate(spin)
  ctx.strokeStyle = 'rgba(200,210,225,0.9)'
  ctx.lineWidth = r * 0.1
  ctx.beginPath(); ctx.moveTo(-r * 1.25, 0); ctx.lineTo(r * 1.25, 0); ctx.stroke()
  for (const side of [-1, 1]) {
    const px = side * r * 0.62, pw = r * 0.62, ph = r * 0.95
    ctx.fillStyle = 'rgb(32,62,140)'
    ctx.fillRect(side > 0 ? px : px - pw, -ph / 2, pw, ph)
    ctx.strokeStyle = 'rgba(140,180,255,0.7)'
    ctx.lineWidth = r * 0.04
    ctx.strokeRect(side > 0 ? px : px - pw, -ph / 2, pw, ph)
    for (let i = 1; i < 3; i++) {
      const gx = (side > 0 ? px : px - pw) + (pw * i) / 3
      ctx.beginPath(); ctx.moveTo(gx, -ph / 2); ctx.lineTo(gx, ph / 2); ctx.stroke()
    }
    ctx.beginPath(); ctx.moveTo(side > 0 ? px : px - pw, 0); ctx.lineTo(side > 0 ? px + pw : px, 0); ctx.stroke()
  }
  // The hub: a metal drum in its agent's colour.
  const g = ctx.createLinearGradient(-r * 0.4, -r * 0.4, r * 0.4, r * 0.4)
  g.addColorStop(0, rgba(mix(base, WHITE, 0.7)))
  g.addColorStop(1, rgba(mix(base, [60, 70, 90], 0.6)))
  ctx.fillStyle = g
  ctx.beginPath(); ctx.arc(0, 0, r * 0.42, 0, TAU); ctx.fill()
  ctx.strokeStyle = 'rgba(230,235,245,0.8)'
  ctx.lineWidth = r * 0.06
  ctx.beginPath(); ctx.ellipse(0, 0, r * 0.42, r * 0.16, Math.PI / 2, 0, TAU); ctx.stroke()
  ctx.restore()
  const on = !moving || Math.floor(now / 700) % 2 === 0
  if (on) glow(ctx, x + Math.cos(spin) * r * 1.25, y + Math.sin(spin) * r * 1.25, 0, r * 0.35, [255, 80, 80], 0.95)
}

/**
 * A workflow: a comet. Its tails point away from the sun, the ion tail straight and blue, the dust
 * tail broader and curving back along its path, and both grow as it nears periapsis (`heat` 0 → 1).
 */
export function drawComet(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, base: RGB,
  away: { x: number; y: number }, back: { x: number; y: number }, heat: number) {
  const len = r * (5 + 11 * heat)
  const nx = -away.y, ny = away.x
  ctx.globalCompositeOperation = 'lighter'
  // Dust tail: a curved fan between "away from the sun" and "back along the path".
  const dx = away.x * 0.6 + back.x * 0.4, dy = away.y * 0.6 + back.y * 0.4
  const dl = Math.hypot(dx, dy) || 1
  const ex = x + (dx / dl) * len * 0.8, ey = y + (dy / dl) * len * 0.8
  const dust = ctx.createLinearGradient(x, y, ex, ey)
  dust.addColorStop(0, rgba(mix(base, [255, 235, 190], 0.6), 0.55))
  dust.addColorStop(1, rgba([255, 235, 190], 0))
  ctx.fillStyle = dust
  ctx.beginPath()
  ctx.moveTo(x + nx * r * 0.8, y + ny * r * 0.8)
  ctx.quadraticCurveTo(x + away.x * len * 0.4 + nx * r * 1.6, y + away.y * len * 0.4 + ny * r * 1.6, ex + (-dy / dl) * r * 2.2, ey + (dx / dl) * r * 2.2)
  ctx.lineTo(ex - (-dy / dl) * r * 1.2, ey - (dx / dl) * r * 1.2)
  ctx.lineTo(x - nx * r * 0.8, y - ny * r * 0.8)
  ctx.fill()
  // Ion tail: narrow, straight out from the sun.
  const ix = x + away.x * len, iy = y + away.y * len
  const ion = ctx.createLinearGradient(x, y, ix, iy)
  ion.addColorStop(0, 'rgba(150,200,255,0.75)')
  ion.addColorStop(1, 'rgba(120,170,255,0)')
  ctx.fillStyle = ion
  ctx.beginPath()
  ctx.moveTo(x + nx * r * 0.45, y + ny * r * 0.45)
  ctx.lineTo(ix + nx * r * 0.25, iy + ny * r * 0.25)
  ctx.lineTo(ix - nx * r * 0.25, iy - ny * r * 0.25)
  ctx.lineTo(x - nx * r * 0.45, y - ny * r * 0.45)
  ctx.fill()
  glow(ctx, x, y, 0, r * (2.2 + heat), mix(base, [170, 230, 255], 0.6), 0.6)
  ctx.globalCompositeOperation = 'source-over'
  ctx.fillStyle = '#fbfdff'
  ctx.beginPath(); ctx.arc(x, y, r * 0.5, 0, TAU); ctx.fill()
}

/** A skill: a pulsar, beating and sweeping two beams round like a lighthouse. */
export function drawPulsar(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, base: RGB, now: number, moving: boolean) {
  const beat = moving ? 0.5 + 0.5 * Math.sin(now / 180) : 0.7
  const sweep = moving ? now / 1400 : 0.6
  const c = mix(base, [200, 230, 255], 0.5)
  ctx.globalCompositeOperation = 'lighter'
  for (const side of [0, Math.PI]) {
    const a = sweep + side
    const len = r * 4.5
    const g = ctx.createLinearGradient(x, y, x + Math.cos(a) * len, y + Math.sin(a) * len)
    g.addColorStop(0, rgba(c, 0.55))
    g.addColorStop(1, rgba(c, 0))
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.moveTo(x, y)
    ctx.lineTo(x + Math.cos(a - 0.13) * len, y + Math.sin(a - 0.13) * len)
    ctx.lineTo(x + Math.cos(a + 0.13) * len, y + Math.sin(a + 0.13) * len)
    ctx.fill()
  }
  glow(ctx, x, y, 0, r * (1.8 + beat * 0.8), c, 0.55 + 0.3 * beat)
  ctx.globalCompositeOperation = 'source-over'
  ctx.fillStyle = rgba(mix(c, WHITE, 0.7))
  ctx.beginPath(); ctx.arc(x, y, r * 0.55, 0, TAU); ctx.fill()
}
