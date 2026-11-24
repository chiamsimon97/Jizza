import { describe, expect, it } from 'vitest'
import { fromGraph, toGraph, type CanvasGraph } from './canvasModel'
import {
  CARD_H, CARD_W, autoAlign, deleteStep, distanceTo, dropOnStep, exitPoint, inPoint, isArranged, keepWiresSettings, keepPlaces, placeStep, placedAt, previewPath, rewire, rewireTargets, route, setWireLabel, stepNumbers,
  wireKey, wireLabel, wiresLayout,
} from './wiresModel'

/** Start → open → approval; approved → hold → notify, rejected → skip. */
function booking(): CanvasGraph {
  return fromGraph({
    nodes: [
      { id: 'start', type: 'TRIGGER', config: { ui: { x: 0, y: 0 } } },
      { id: 'open', type: 'DEVICE_ACTION', config: { action: 'browser.open', ui: { x: 300, y: 0 } } },
      { id: 'ok', type: 'APPROVAL', config: { ui: { x: 600, y: 0 } } },
      { id: 'hold', type: 'AGENT_STEP', config: { agentId: 'a', instruction: 'hold', ui: { x: 900, y: 0 } } },
      { id: 'skip', type: 'NOTIFY', config: { message: 'skipped', ui: { x: 900, y: 200 } } },
      { id: 'tell', type: 'NOTIFY', config: { message: 'held', ui: { x: 1200, y: 0 } } },
    ],
    edges: [
      { from: 'start', to: 'open' }, { from: 'open', to: 'ok' },
      { from: 'ok', to: 'hold', branch: 'approved' }, { from: 'ok', to: 'skip', branch: 'rejected' },
      { from: 'hold', to: 'tell' },
    ],
  })
}

describe('wiresModel', () => {
  it('lays steps out top to bottom, approved on the left of rejected, parents over their children', () => {
    const { at } = wiresLayout(booking())
    const y = (id: string) => at.get(id)!.y
    const x = (id: string) => at.get(id)!.x
    expect(y('start')).toBeLessThan(y('open'))
    expect(y('open')).toBeLessThan(y('ok'))
    expect(y('hold')).toBe(y('skip'))
    expect(x('hold')).toBeLessThan(x('skip'))
    expect(x('ok')).toBe((x('hold') + x('skip')) / 2)
    expect(x('tell')).toBe(x('hold'))
  })

  it('leaves the canvas positions alone', () => {
    const g = booking()
    wiresLayout(g)
    expect(toGraph(g).nodes.find(n => n.id === 'skip')!.config.ui).toEqual({ x: 900, y: 200 })
  })

  it('puts steps nothing leads to beside the flow instead of on top of it', () => {
    const g = booking()
    g.nodes.push({ id: 'loose', type: 'NOTIFY', label: 'Loose', config: {}, x: 0, y: 0 })
    const { at } = wiresLayout(g)
    const others = [...at].filter(([id]) => id !== 'loose').map(([, b]) => b.x)
    expect(at.get('loose')!.x).toBeGreaterThan(Math.max(...others))
  })

  it('routes wires down from an exit and into the top of the next step', () => {
    const g = booking()
    const { at } = wiresLayout(g)
    const ok = g.nodes.find(n => n.id === 'ok')!
    const a = exitPoint(['approved', 'rejected'], at.get('ok')!, 'approved')
    const b = inPoint(at.get('hold')!)
    expect(a[1]).toBe(at.get('ok')!.y + CARD_H)
    const r = route(a, b, true)
    expect(r.pts[0]).toEqual(a)
    expect(r.pts[r.pts.length - 1]).toEqual(b)
    expect(r.d.startsWith(`M${a[0]} ${a[1]}`)).toBe(true)
    expect(distanceTo(r.pts, r.mid[0], r.mid[1])).toBeLessThan(1)
    expect(ok.type).toBe('APPROVAL')
  })

  it('wires a dropped step onto a free exit, or slots it in when the exit is taken', () => {
    const g = booking()
    const free = dropOnStep(g, 'tell', 'NOTIFY')!
    expect(free.graph.edges).toContainEqual({ from: 'tell', to: free.id, branch: null })

    const between = dropOnStep(g, 'open', 'WAIT')!
    expect(between.graph.edges).toContainEqual({ from: 'open', to: between.id, branch: null })
    expect(between.graph.edges).toContainEqual({ from: between.id, to: 'ok', branch: null })
    expect(between.graph.edges.some(e => e.from === 'open' && e.to === 'ok')).toBe(false)
  })

  it('does not add after End', () => {
    const g = fromGraph({ nodes: [{ id: 'start', type: 'TRIGGER' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'end' }] })
    expect(dropOnStep(g, 'end', 'NOTIFY')).toBeNull()
  })

  it('joins the steps either side of a deleted step in a straight line', () => {
    const r = deleteStep(booking(), 'open')
    expect(r.joined).toBe(true)
    expect(r.graph.edges).toContainEqual({ from: 'start', to: 'ok', branch: null })
    // An approval has two exits, so deleting it can't pick one to keep.
    const branch = deleteStep(booking(), 'ok')
    expect(branch.joined).toBe(false)
    expect(branch.graph.nodes.some(n => n.id === 'ok')).toBe(false)
    expect(deleteStep(booking(), 'start').graph.nodes).toHaveLength(6)
  })

  it('keeps wire labels in the settings of the step the wire leaves', () => {
    const edge = { from: 'ok', branch: 'approved' }
    let g = setWireLabel(booking(), edge, '  seats ok ')
    expect(wireLabel(g, edge)).toBe('seats ok')
    expect(wireLabel(g, { from: 'ok', branch: 'rejected' })).toBe('')
    expect((toGraph(g).nodes.find(n => n.id === 'ok')!.config as Record<string, unknown>).wireLabels).toEqual({ approved: 'seats ok' })
    g = setWireLabel(g, edge, '')
    expect(g.nodes.find(n => n.id === 'ok')!.config).not.toHaveProperty('wireLabels')
    expect(wireKey({ from: 'hold', branch: null })).toBe('hold:out')
  })

  it('previews the approved path and every lane of a parallel step', () => {
    const p = previewPath(booking())
    expect([...p.step.keys()]).toEqual(['start', 'open', 'ok', 'hold', 'tell'])
    expect(p.edges.has('ok:approved')).toBe(true)
    expect(p.edges.has('ok:rejected')).toBe(false)
    expect(p.last).toBe(4)

    const par = fromGraph({
      nodes: [{ id: 'start', type: 'TRIGGER' }, { id: 'p', type: 'PARALLEL' }, { id: 'a', type: 'NOTIFY' }, { id: 'b', type: 'NOTIFY' }],
      edges: [{ from: 'start', to: 'p' }, { from: 'p', to: 'a', branch: 'a' }, { from: 'p', to: 'b', branch: 'b' }],
    })
    const pp = previewPath(par)
    expect(pp.step.get('a')).toBe(2)
    expect(pp.step.get('b')).toBe(2)
  })

  it('numbers steps in the order they run', () => {
    expect([...stepNumbers(booking())]).toEqual([['start', 1], ['open', 2], ['ok', 3], ['hold', 4], ['skip', 5], ['tell', 6]])
  })

  it('keeps a step where it was dragged, on the grid, without touching its canvas position', () => {
    const moved = placeStep(booking(), 'skip', { x: 701, y: 37 })
    expect(wiresLayout(moved).at.get('skip')).toEqual({ x: 704, y: 40 })
    expect(moved.nodes.find(n => n.id === 'skip')).toMatchObject({ x: 900, y: 200 })
    // The board grows to fit a step dragged far out.
    expect(wiresLayout(placeStep(booking(), 'skip', { x: 3000, y: 2000 })).width).toBeGreaterThan(3000)
  })

  it('lines every step up again with auto align', () => {
    const g = booking()
    const moved = placeStep(placeStep(g, 'skip', { x: 900, y: 40 }), 'hold', { x: 16, y: 16 })
    expect(isArranged(moved)).toBe(true)
    const aligned = autoAlign(moved)
    expect(isArranged(aligned)).toBe(false)
    expect(wiresLayout(aligned).at).toEqual(wiresLayout(g).at)
    expect(autoAlign(g)).toBe(g)
  })

  it('keeps a moved position and wire labels when settings saved from an older copy come back', () => {
    const before = booking()
    const now = setWireLabel(placeStep(before, 'ok', { x: 400, y: 400 }), { from: 'ok', branch: 'approved' }, 'yes')
    const saved = keepWiresSettings(before, now, 'ok')
    expect(saved.nodes.find(n => n.id === 'ok')!.config).toMatchObject({ wiresAt: { x: 400, y: 400 }, wireLabels: { approved: 'yes' } })
  })

  it('re-wires an exit to another step, or takes the wire off', () => {
    const g = booking()
    const old = g.edges.find(e => e.from === 'ok' && e.branch === 'rejected')!
    const moved = rewire(g, 'ok', 'rejected', 'tell', old)
    expect(moved.edges).toContainEqual({ from: 'ok', to: 'tell', branch: 'rejected' })
    expect(moved.edges.some(e => e.to === 'skip')).toBe(false)
    expect(moved.edges.filter(e => e.from === 'ok')).toHaveLength(2)
    expect(rewire(g, 'ok', 'rejected', null, old).edges.some(e => e.from === 'ok' && e.branch === 'rejected')).toBe(false)
  })

  it('offers only steps that make no loop, never Start, and nothing inside a locked group', () => {
    const g = booking()
    const targets = rewireTargets(g, 'hold', null, g.edges.find(e => e.from === 'hold'))
    expect([...targets].sort()).toEqual(['skip', 'tell'])
    const locked: CanvasGraph = { ...g, groups: [{ id: 'g', title: 'Seats', nodes: ['open', 'ok', 'skip'], locked: true, collapsed: false }] }
    // A wire inside the locked group stays; a wire into the group can still be moved.
    expect(rewireTargets(locked, 'open', null, locked.edges.find(e => e.from === 'open')).size).toBe(0)
    expect(rewireTargets(locked, 'ok', 'approved', locked.edges.find(e => e.from === 'ok' && e.branch === 'approved')).has('skip')).toBe(false)
    expect(rewireTargets(locked, 'ok', 'approved', locked.edges.find(e => e.from === 'ok' && e.branch === 'approved')).has('tell')).toBe(true)
    expect(rewireTargets(locked, 'start', null, locked.edges.find(e => e.from === 'start')).has('ok')).toBe(true)
  })

  it('keeps every step where it is drawn through an edit, and puts an added step in a free spot', () => {
    const g = booking()
    const at = wiresLayout(g).at
    const rewired = keepPlaces(g, rewire(g, 'ok', 'rejected', 'tell', g.edges.find(e => e.branch === 'rejected')), at)
    for (const n of g.nodes) expect(placedAt(rewired.nodes.find(m => m.id === n.id)!.config)).toEqual(at.get(n.id))
    expect(wiresLayout(rewired).at.get('skip')).toEqual(at.get('skip'))

    const inserted = keepPlaces(g, dropOnStep(g, 'hold', 'NOTIFY')!.graph, at)
    const added = inserted.nodes.find(n => !g.nodes.some(m => m.id === n.id))!
    const spot = placedAt(added.config)!
    for (const n of g.nodes) {
      const b = at.get(n.id)!
      expect(Math.abs(b.x - spot.x) >= CARD_W || Math.abs(b.y - spot.y) >= CARD_H).toBe(true)
    }
    expect(placedAt(inserted.nodes.find(n => n.id === 'tell')!.config)).toEqual(at.get('tell'))
  })
})
