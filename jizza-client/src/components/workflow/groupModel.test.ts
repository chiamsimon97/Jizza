import { describe, expect, it } from 'vitest'
import { applyStepEdit, fromGraph, removeNode, setNext, tidy, toGraph, type CanvasGraph, type CanvasNode } from './canvasModel'
import { createGroup, groupBounds, isLockedEdge, keepLockedArrangement, lockedChange, moveGroup, readGroups, removeFromGroup, ungroup, updateGroup } from './groupModel'

const node = (id: string, x: number, type: CanvasNode['type'] = 'TRANSFORM'): CanvasNode => ({ id, type, label: id, config: {}, x, y: 100 })

/** Start → coach → date → pay, the coach-then-date pair being what gets grouped. */
function flow(): CanvasGraph {
  return {
    nodes: [node('start', 0, 'TRIGGER'), node('coach', 250), node('date', 500), node('pay', 750)],
    edges: [
      { from: 'start', to: 'coach', branch: null },
      { from: 'coach', to: 'date', branch: null },
      { from: 'date', to: 'pay', branch: null },
    ],
  }
}

function locked(): CanvasGraph {
  const made = createGroup(flow(), ['date', 'coach'], 'Pick coach and date')!
  return updateGroup(made.graph, made.id, { locked: true })
}

describe('step groups', () => {
  it('groups steps in canvas order and never takes Start', () => {
    const made = createGroup(flow(), ['date', 'start', 'coach'])!
    expect(made.graph.groups).toEqual([{ id: made.id, title: 'Group 1', nodes: ['coach', 'date'], locked: false, collapsed: false }])
    expect(createGroup(flow(), ['start'])).toBeNull()
  })

  it('moves steps out of their old group, and leaves steps of a locked group alone', () => {
    const first = createGroup(flow(), ['coach', 'date'])!
    const second = createGroup(first.graph, ['date', 'pay'])!
    expect(second.graph.groups!.map(g => g.nodes)).toEqual([['coach'], ['date', 'pay']])
    expect(createGroup(locked(), ['date', 'pay'])!.graph.groups!.at(-1)!.nodes).toEqual(['pay'])
  })

  it('saves groups with the graph and reads them back', () => {
    const g = locked()
    const saved = toGraph(g)
    expect(saved.groups).toHaveLength(1)
    const back = fromGraph(JSON.parse(JSON.stringify(saved)))
    expect(back.groups).toEqual(g.groups)
    // A workflow without groups is saved exactly as before.
    expect('groups' in toGraph(flow())).toBe(false)
  })

  it('drops unknown and repeated steps when reading groups', () => {
    const g = readGroups([
      { id: 'a', title: 'A', nodes: ['coach', 'ghost', 'start'] },
      { id: 'b', title: 'B', nodes: ['coach'] },
    ], flow().nodes)
    expect(g).toEqual([{ id: 'a', title: 'A', nodes: ['coach'], locked: false, collapsed: false }])
  })

  it('keeps groups through ordinary edits and forgets deleted steps', () => {
    const made = createGroup(flow(), ['coach', 'date'])!
    expect(setNext(made.graph, 'pay', null, null).groups).toEqual(made.graph.groups)
    expect(applyStepEdit(made.graph, made.graph, 'coach').groups).toEqual(made.graph.groups)
    expect(removeNode(made.graph, 'coach').groups![0].nodes).toEqual(['date'])
    expect(removeNode(removeNode(made.graph, 'coach'), 'date').groups).toEqual([])
  })

  it('refuses changes inside a locked group: wiring, settings, deleting, ungrouping', () => {
    const g = locked()
    expect(lockedChange(g, setNext(g, 'coach', null, 'pay'))).toMatch(/wiring inside “Pick coach and date” is locked/)
    expect(lockedChange(g, { ...g, nodes: g.nodes.map(n => (n.id === 'date' ? { ...n, config: { x: 1 } } : n)) })).toMatch(/settings/)
    expect(lockedChange(g, removeNode(g, 'date'))).toMatch(/locked/)
    expect(ungroup(g, g.groups![0].id)).toBe(g)
    expect(removeFromGroup(g, 'date')).toBe(g)
  })

  it('lets the wires into and out of a locked group change, and the group be unlocked, folded or renamed', () => {
    const g = locked()
    const id = g.groups![0].id
    expect(lockedChange(g, setNext(g, 'date', null, null))).toBeNull()
    expect(lockedChange(g, setNext(g, 'start', null, 'date'))).toBeNull()
    expect(lockedChange(g, updateGroup(g, id, { locked: false }))).toBeNull()
    expect(lockedChange(g, updateGroup(g, id, { collapsed: true, title: 'Seats' }))).toBeNull()
    expect(lockedChange(g, moveGroup(g, id, 40, 20))).toBeNull()
    // Where the Wires view puts a step is not one of its settings.
    expect(lockedChange(g, { ...g, nodes: g.nodes.map(n => (n.id === 'date' ? { ...n, config: { wiresAt: { x: 8, y: 8 } } } : n)) })).toBeNull()
    // Unlocked, anything goes.
    const open = updateGroup(g, id, { locked: false })
    expect(lockedChange(open, setNext(open, 'coach', null, 'pay'))).toBeNull()
  })

  it('tells which wires are locked', () => {
    const g = locked()
    expect(isLockedEdge(g, { from: 'coach', to: 'date' })).toBe(true)
    expect(isLockedEdge(g, { from: 'date', to: 'pay' })).toBe(false)
    expect(isLockedEdge(g, { from: 'start', to: 'coach' })).toBe(false)
  })

  it('keeps a locked group in its arrangement when steps are tidied or one is dragged', () => {
    const g = { ...locked(), nodes: locked().nodes.map(n => (n.id === 'date' ? { ...n, y: 300 } : n)) }
    const tidied = keepLockedArrangement(g, tidy(g))
    const coach = tidied.nodes.find(n => n.id === 'coach')!
    const date = tidied.nodes.find(n => n.id === 'date')!
    expect([date.x - coach.x, date.y - coach.y]).toEqual([250, 200])
    const dragged = keepLockedArrangement(g, { ...g, nodes: g.nodes.map(n => (n.id === 'coach' ? { ...n, x: n.x + 30, y: n.y + 10 } : n)) })
    expect(dragged.nodes.find(n => n.id === 'date')).toMatchObject({ x: 530, y: 310 })
  })

  it('draws a box around its steps, or a small card when folded', () => {
    const g = locked()
    const size = () => ({ width: 200, height: 92 })
    const box = groupBounds(g, g.groups![0], size)!
    expect(box).toEqual({ x: 230, y: 46, width: 490, height: 166 })
    const folded = groupBounds(g, { ...g.groups![0], collapsed: true }, size)!
    expect([folded.x, folded.y, folded.width]).toEqual([230, 46, 240])
  })
})
