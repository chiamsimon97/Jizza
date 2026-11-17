import { describe, expect, it } from 'vitest'
import {
  addNode, dropLayerButton, exitsOf, fieldIssues, issues, layerButtonsOf, nextLayerId, orderLayerFields,
  type CanvasGraph, type CanvasNode, type LayerField,
} from './canvasModel'
import { exitLabel } from './canvasParts'

const el = { strategies: [{ by: 'css', value: '#x' }] }

const layer = (buttons: Array<{ id: string; name: string }>): CanvasNode => ({
  id: 'layer', type: 'DEVICE_ACTION', label: 'Create task', x: 0, y: 0,
  config: {
    preset: 'web', action: 'browser.fillLayer', version: 1, target: 'sameBrowser',
    inputs: { groups: [{ id: 'g1', title: 'Details' }], fields: [{ id: 'f1', kind: 'text', element: el }], buttons: buttons.map(b => ({ ...b, element: el })) },
  },
})

const graph = (node: CanvasNode): CanvasGraph => ({
  nodes: [
    { id: 'start', type: 'TRIGGER', label: 'Start', x: 0, y: 0, config: {} },
    node,
    { id: 'saved', type: 'NOTIFY', label: 'Saved', x: 0, y: 0, config: { message: 'saved' } },
    { id: 'dropped', type: 'NOTIFY', label: 'Dropped', x: 0, y: 0, config: { message: 'dropped' } },
  ],
  edges: [
    { from: 'start', to: 'layer', branch: null },
    { from: 'layer', to: 'saved', branch: 'b1' },
    { from: 'layer', to: 'dropped', branch: 'b2' },
  ],
})

describe('device layer on the canvas', () => {
  it('has one way out per button, named after it', () => {
    const node = layer([{ id: 'b1', name: 'Submit' }, { id: 'b2', name: 'Cancel' }])
    expect(exitsOf(node)).toEqual(['b1', 'b2'])
    expect(exitLabel('b2', node)).toBe('Cancel')
    expect(layerButtonsOf(node).map(b => b.name)).toEqual(['Submit', 'Cancel'])
    expect(issues(graph(node))).toEqual([])
  })

  it('connects a new step to the first button with no path', () => {
    const node = layer([{ id: 'b1', name: 'Submit' }, { id: 'b2', name: 'Cancel' }, { id: 'b3', name: 'Save draft' }])
    const { graph: g, id } = addNode(graph(node), 'NOTIFY', 0, 0, 'layer')
    expect(g.edges).toContainEqual({ from: 'layer', to: id, branch: 'b3' })
  })

  it('moves a removed button\'s steps to another button, or disconnects them', () => {
    const node = layer([{ id: 'b1', name: 'Submit' }, { id: 'b2', name: 'Cancel' }, { id: 'b3', name: 'Close' }])
    const moved = dropLayerButton(graph(node), 'layer', 'b2', 'b3')
    expect(moved.edges).toContainEqual({ from: 'layer', to: 'dropped', branch: 'b3' })
    const cut = dropLayerButton(graph(node), 'layer', 'b2')
    expect(cut.edges.some(e => e.to === 'dropped')).toBe(false)
    // Nothing is deleted: the step stays on the canvas.
    expect(cut.nodes.some(n => n.id === 'dropped')).toBe(true)
  })

  it('flags a path from a button that is gone, and a layer with no buttons', () => {
    const node = layer([{ id: 'b1', name: 'Submit' }])
    expect(issues(graph(node)).map(i => i.message)).toContain('"Create task" has a connection from a path that no longer exists.')
    expect(fieldIssues(layer([])).map(i => i.field)).toContain('inputs.buttons')
  })

  it('runs fixed values first, groups in order, and a field after the one it follows', () => {
    const fields: LayerField[] = [
      { id: 'm', kind: 'select', section: 'config', group: 'g1', dependsOn: 'p' },
      { id: 'a', kind: 'text', section: 'config', group: 'g2' },
      { id: 's', kind: 'select', section: 'static' },
      { id: 'p', kind: 'select', section: 'config', group: 'g1' },
    ]
    const order = orderLayerFields(fields, [{ id: 'g1' }, { id: 'g2' }])
    expect(order.map(f => f.id)).toEqual(['s', 'p', 'm', 'a'])
  })

  it('never reuses an id', () => {
    expect(nextLayerId('b', ['b1', 'b3'])).toBe('b4')
    expect(nextLayerId('f', [])).toBe('f1')
  })
})
