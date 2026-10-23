import { describe, expect, it } from 'vitest'
import { stepsFromGraph } from '@/components/forms/WorkflowForm'
import {
  addAfter, addNode, applyStepEdit, arrangeFrom, branchesFrom, cloneNodes, descendantsOf, emptyGraph, exitsOf, fieldIssues, fromDuration, fromGraph, insertOnEdge, issues, nextCaseId, problems, setNext, targetsFor, tidy,
  toDuration, toGraph, unreachable, updateNode, NODE_META, PALETTE, PALETTE_GROUPS,
} from './canvasModel'

describe('canvasModel', () => {
  it('lays out a saved graph without positions and keeps positions it was given', () => {
    const graph = fromGraph({
      nodes: [
        { id: 'start', type: 'TRIGGER', config: {} },
        { id: 'check', type: 'BRANCH', config: { condition: 'x > 1' } },
        { id: 'yes', type: 'AGENT_STEP', config: { agentId: 'a', instruction: 'go', ui: { x: 900, y: 10 } } },
        { id: 'no', type: 'END', config: {} },
      ],
      edges: [{ from: 'start', to: 'check' }, { from: 'check', to: 'yes', branch: 'true' }, { from: 'check', to: 'no', branch: 'false' }],
    })
    const at = Object.fromEntries(graph.nodes.map(n => [n.id, [n.x, n.y]]))
    expect(at.start[0]).toBeLessThan(at.check[0])
    expect(at.yes).toEqual([900, 10])
    expect(graph.nodes.find(n => n.id === 'yes')!.config).not.toHaveProperty('ui')
    expect(problems(graph)).toEqual([])
  })

  it('round-trips through the engine graph with positions in the config', () => {
    const { graph } = addNode(emptyGraph(), 'HUMAN_INTERVENTION', 300, 150, 'start')
    const saved = toGraph(graph)
    expect(saved.edges).toEqual([{ from: 'start', to: graph.nodes[1].id, branch: null }])
    expect(saved.nodes[1].config).toEqual({ ui: { x: 300, y: 150 } })
    expect(fromGraph(saved).nodes[1]).toMatchObject({ x: 300, y: 150, type: 'HUMAN_INTERVENTION' })
    // A straight line of agent and ask steps still opens in the step list.
    expect(stepsFromGraph(saved)).toEqual([{ kind: 'ask', instruction: undefined }])
  })

  it('says what a branch is missing and never offers a connection that would loop', () => {
    let g = addNode(emptyGraph(), 'BRANCH', 300, 150, 'start').graph
    const branch = g.nodes[1].id
    expect(problems(g)).toEqual(expect.arrayContaining([
      expect.stringContaining('needs a condition'), expect.stringContaining('"yes"'), expect.stringContaining('"no"'),
    ]))
    const end = addNode(g, 'END', 600, 150, branch)
    g = setNext(end.graph, branch, 'false', end.id)
    expect(g.edges.filter(e => e.from === branch).map(e => e.branch).sort()).toEqual(['false', 'true'])
    expect(targetsFor(g, end.id).map(n => n.id)).not.toContain(branch)
    expect(targetsFor(g, branch).map(n => n.id)).not.toContain('start')
  })

  it('inserts a step on a wire, keeping a branch exit on the way in', () => {
    let g = addNode(emptyGraph(), 'BRANCH', 300, 150, 'start').graph
    const branch = g.nodes[1].id
    g = addAfter(g, branch, 'false', 'END').graph
    const wire = g.edges.find(e => e.branch === 'false')!
    const r = insertOnEdge(g, wire, 'HUMAN_INTERVENTION')
    expect(r.graph.edges).toContainEqual({ from: branch, to: r.id, branch: 'false' })
    expect(r.graph.edges).toContainEqual({ from: r.id, to: wire.to, branch: null })
    expect(r.graph.edges).not.toContainEqual(wire)
  })

  it('adds after an exit by replacing what it pointed at', () => {
    const first = addAfter(emptyGraph(), 'start', null, 'END')
    const second = addAfter(first.graph, 'start', null, 'TRANSFORM')
    expect(second.graph.edges.filter(e => e.from === 'start')).toEqual([{ from: 'start', to: second.id, branch: null }])
  })

  it('copies steps with the wires between them, never Start', () => {
    const a = addAfter(emptyGraph(), 'start', null, 'TRANSFORM')
    const b = addAfter(a.graph, a.id, null, 'END')
    const copy = cloneNodes(b.graph, ['start', a.id, b.id])
    expect(copy.ids).toHaveLength(2)
    expect(copy.graph.nodes.filter(n => n.type === 'TRIGGER')).toHaveLength(1)
    expect(copy.graph.edges).toContainEqual({ from: copy.ids[0], to: copy.ids[1], branch: null })
  })

  it('ties problems to their step and finds steps nothing leads to', () => {
    const a = addAfter(emptyGraph(), 'start', null, 'TRANSFORM')
    const loose = addNode({ ...a.graph, edges: [] }, 'END', 900, 900, null)
    expect(issues(a.graph)).toEqual([{ nodeId: a.id, message: '"Transform" needs an expression.' }])
    expect(unreachable({ ...a.graph, edges: [] })).toEqual(new Set([a.id]))
    expect(loose.graph.edges).toHaveLength(1)
    const tidied = tidy(a.graph)
    expect(tidied.nodes.find(n => n.id === a.id)!.x).toBeGreaterThan(tidied.nodes.find(n => n.id === 'start')!.x)
  })

  it('gives a parallel step lanes that each run, and keeps them through a save', () => {
    let g = addAfter(emptyGraph(), 'start', null, 'PARALLEL')
    const split = g.id
    expect(problems(g.graph)).toEqual([expect.stringContaining('at least two paths')])
    const left = addAfter(g.graph, split, 'a', 'TRANSFORM')
    const right = addAfter(left.graph, split, 'b', 'WAIT')
    g = right
    const saved = toGraph(g.graph)
    expect(saved.edges.filter(e => e.from === split).map(e => e.branch).sort()).toEqual(['a', 'b'])
    expect(fromGraph(saved).edges.filter(e => e.from === split).map(e => e.branch).sort()).toEqual(['a', 'b'])
    expect(problems(g.graph)).not.toContainEqual(expect.stringContaining('at least two paths'))
    // A new wait starts at an hour, so it has nothing to fill in.
    expect(problems(g.graph)).not.toContainEqual(expect.stringContaining('how long to wait'))
    // A graph with parallel paths can't be shown as a step list.
    expect(stepsFromGraph(saved)).toBeNull()
  })

  it('checks wait lengths and the workflow a step runs', () => {
    const wait = addAfter(emptyGraph(), 'start', null, 'WAIT')
    const bad = { ...wait.graph, nodes: wait.graph.nodes.map(n => n.id === wait.id ? { ...n, config: { duration: '2 hours' } } : n) }
    expect(problems(bad)).toContainEqual(expect.stringContaining("can't read"))
    const good = { ...wait.graph, nodes: wait.graph.nodes.map(n => n.id === wait.id ? { ...n, config: { duration: toDuration(2, 'hours') } } : n) }
    expect(problems(good)).toEqual([])
    expect(fromDuration('PT2H')).toEqual({ amount: 2, unit: 'hours' })
    expect(fromDuration('P3D')).toEqual({ amount: 3, unit: 'days' })

    const each = addAfter(emptyGraph(), 'start', null, 'FOR_EACH')
    expect(problems(each.graph)).toEqual(expect.arrayContaining([
      expect.stringContaining('needs a workflow'), expect.stringContaining('list to go through'),
    ]))
  })

  it('gives a paths step one exit per case plus "else", and keeps case wires through a save', () => {
    const sw = addAfter(emptyGraph(), 'start', null, 'SWITCH')
    const node = sw.graph.nodes.find(n => n.id === sw.id)!
    expect(exitsOf(node)).toEqual(['case1', 'else'])
    const cases = [
      { id: 'case1', label: 'Big', condition: 'input.total > 1000' },
      { id: nextCaseId([{ id: 'case1', label: 'Big', condition: '' }]), label: 'Small', condition: 'input.total < 10' },
    ]
    let g = updateNode(sw.graph, sw.id, { config: { cases } })
    expect(exitsOf(g.nodes.find(n => n.id === sw.id)!)).toEqual(['case1', 'case2', 'else'])
    g = addAfter(g, sw.id, 'case2', 'END').graph
    g = addAfter(g, sw.id, 'else', 'NOTIFY').graph
    const saved = fromGraph(toGraph(g))
    expect(saved.edges.filter(e => e.from === sw.id).map(e => e.branch).sort()).toEqual(['case2', 'else'])
    // Exits stay tied to ids, so renaming a case keeps its wire; dropping it leaves a wire to flag.
    expect(exitsOf({ type: 'SWITCH', config: { cases: [{ ...cases[0], label: 'Huge' }, cases[1]] } })).toEqual(['case1', 'case2', 'else'])
    const dropped = updateNode(g, sw.id, { config: { cases: [cases[0]] } })
    expect(problems(dropped)).toContainEqual(expect.stringContaining('path that no longer exists'))
    expect(exitsOf({ type: 'BRANCH', config: {} })).toEqual(['true', 'false'])
    expect(exitsOf({ type: 'PARALLEL', config: {} })).toEqual(['a', 'b', 'c'])
  })

  it('says what a paths step is missing', () => {
    const sw = addAfter(emptyGraph(), 'start', null, 'SWITCH')
    expect(problems(sw.graph)).toEqual([expect.stringContaining('needs a condition for "Path 1"')])
    const none = updateNode(sw.graph, sw.id, { config: { cases: [] } })
    expect(problems(none)).toContainEqual(expect.stringContaining('at least one path'))
    const twins = updateNode(sw.graph, sw.id, { config: { cases: [{ id: 'case1', label: 'A', condition: 'x' }, { id: 'case1', label: 'B', condition: 'y' }] } })
    expect(problems(twins)).toContainEqual(expect.stringContaining('same id'))
  })

  it('gives an approval approved and rejected exits and needs the approved one', () => {
    const ok = addAfter(emptyGraph(), 'start', null, 'APPROVAL')
    expect(exitsOf({ type: 'APPROVAL', config: {} })).toEqual(['approved', 'rejected'])
    expect(problems(ok.graph)).toEqual([expect.stringContaining('needs a step for "approved"')])
    const rejectedOnly = addAfter(ok.graph, ok.id, 'rejected', 'END').graph
    expect(problems(rejectedOnly)).toEqual([expect.stringContaining('needs a step for "approved"')])
    const both = addAfter(rejectedOnly, ok.id, 'approved', 'NOTIFY')
    expect(problems(both.graph)).toEqual([expect.stringContaining('needs a message')])
    expect(fromGraph(toGraph(both.graph)).edges.filter(e => e.from === ok.id).map(e => e.branch).sort()).toEqual(['approved', 'rejected'])
    // A step put on the way in carries on from "approved".
    const inserted = insertOnEdge(both.graph, { from: 'start', to: ok.id, branch: null }, 'APPROVAL')
    expect(inserted.graph.edges).toContainEqual({ from: inserted.id, to: ok.id, branch: 'approved' })
  })

  it('checks webhook URLs and the other new steps\' required fields', () => {
    const set = (type: Parameters<typeof addAfter>[3], config: Record<string, unknown>) => {
      const r = addAfter(emptyGraph(), 'start', null, type)
      return problems(updateNode(r.graph, r.id, { config }))
    }
    expect(set('HTTP_REQUEST', {})).toEqual([expect.stringContaining('needs a URL.')])
    expect(set('HTTP_REQUEST', { url: 'example.com/hook' })).toEqual([expect.stringContaining('http:// or https://')])
    expect(set('HTTP_REQUEST', { url: 'https://example.com/hook' })).toEqual([])
    expect(set('HTTP_REQUEST', { url: '{{ vars.hook }}' })).toEqual([])
    expect(set('FILTER', {})).toEqual([expect.stringContaining('needs a condition')])
    expect(set('STORAGE', { action: 'set', key: 'count' })).toEqual([expect.stringContaining('value to store')])
    expect(set('STORAGE', { key: 'count' })).toEqual([])
    expect(set('SET_VARIABLE', { variables: [{ name: 'total', expr: 'nodes.a.sum' }] })).toEqual([])
    expect(set('SET_VARIABLE', { variables: [{ name: 'bad name', expr: '1' }] })).toEqual([expect.stringContaining('needs a name')])
  })

  it('selects and arranges the steps after a split, as a tree that keeps its paths apart', () => {
    const branch = addAfter(emptyGraph(), 'start', null, 'BRANCH')
    const yes = addAfter(branch.graph, branch.id, 'true', 'NOTIFY')
    const no = addAfter(yes.graph, branch.id, 'false', 'TRANSFORM')
    const after = addAfter(no.graph, no.id, null, 'END')
    const g = after.graph
    expect(branchesFrom(g, branch.id)).toBe(true)
    expect(branchesFrom(g, 'start')).toBe(false)
    expect(descendantsOf(g, branch.id).sort()).toEqual([yes.id, no.id, after.id].sort())

    const scattered = updateNode(updateNode(g, yes.id, { x: 5, y: 900 }), no.id, { x: 5, y: 905 })
    const arranged = arrangeFrom(scattered, branch.id)
    const at = (id: string) => arranged.nodes.find(n => n.id === id)!
    const root = at(branch.id)
    expect(root).toEqual(scattered.nodes.find(n => n.id === branch.id))
    expect(at(yes.id).x).toBeGreaterThan(root.x)
    expect(at(yes.id).x).toBe(at(no.id).x)
    expect(Math.abs(at(yes.id).y - at(no.id).y)).toBeGreaterThanOrEqual(130)
    expect(at(after.id).x).toBeGreaterThan(at(no.id).x)
    // Start is outside what was arranged and stays put.
    expect(at('start')).toEqual(scattered.nodes.find(n => n.id === 'start'))
  })

  it('asks an MCP tool step for a server, then for one of its tools', () => {
    const hook = addAfter(emptyGraph(), 'start', null, 'MCP_TOOL')
    const node = hook.graph.nodes.find(n => n.id === hook.id)!
    expect(fieldIssues(node).map(f => f.field)).toEqual(['serverId'])
    expect(fieldIssues({ ...node, config: { serverId: 's1' } }).map(f => f.field)).toEqual(['tool'])
    expect(fieldIssues({ ...node, config: { serverId: 's1', tool: 'get_holdings' } })).toEqual([])
    expect(fromGraph(toGraph(hook.graph)).nodes.find(n => n.id === hook.id)?.type).toBe('MCP_TOOL')
  })

  it('lists a step\'s mandatory fields and saves an edit without losing canvas changes', () => {
    const hook = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const node = hook.graph.nodes.find(n => n.id === hook.id)!
    expect(fieldIssues(node).map(f => f.field)).toEqual(['url'])
    const bearer = { ...node, config: { ...node.config, url: 'https://x.test', auth: { type: 'bearer' } } }
    expect(fieldIssues(bearer).map(f => f.field)).toEqual(['auth.token'])

    const edited = updateNode(hook.graph, hook.id, { config: { url: 'https://x.test' }, label: 'Ping' })
    const moved = updateNode(hook.graph, hook.id, { x: 777 })
    const saved = applyStepEdit(moved, edited, hook.id)
    const after = saved.nodes.find(n => n.id === hook.id)!
    expect(after.label).toBe('Ping')
    expect(after.x).toBe(777)
    expect(saved.edges).toEqual(moved.edges)
  })
})

describe('palette groups', () => {
  it('lists every step type once, under a title', () => {
    expect(new Set(PALETTE).size).toBe(PALETTE.length)
    expect(PALETTE).toHaveLength(Object.keys(NODE_META).length - 1)
    expect(PALETTE).not.toContain('TRIGGER')
    expect(PALETTE_GROUPS.every(g => g.title && g.types.length)).toBe(true)
  })
})
