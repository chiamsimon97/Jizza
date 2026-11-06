import { describe, expect, it } from 'vitest'
import { fromGraph, toGraph, type CanvasGraph } from './canvasModel'
import {
  CARD_H, deleteStep, distanceTo, dropOnStep, exitPoint, inPoint, previewPath, route, setWireLabel, stepNumbers, wireKey, wireLabel, wiresLayout,
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
})
