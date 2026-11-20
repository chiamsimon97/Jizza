import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { GoBackFields, stepsBefore } from './GoBackFields'
import type { CanvasGraph, CanvasNode } from './canvasModel'

const node = (id: string, type: string, label: string, config: Record<string, unknown> = {}): CanvasNode =>
  ({ id, type, label, config, x: 0, y: 0 }) as CanvasNode

const GRAPH: CanvasGraph = {
  nodes: [
    node('start', 'TRIGGER', 'Start'),
    node('train', 'DEVICE_ACTION', 'Choose train'),
    node('coach', 'DEVICE_ACTION', 'Choose coach'),
    node('check', 'DEVICE_ACTION', 'Check availability'),
    node('pay', 'DEVICE_ACTION', 'Pay'),
  ],
  edges: [
    { from: 'start', to: 'train', branch: null },
    { from: 'train', to: 'coach', branch: null },
    { from: 'coach', to: 'check', branch: null },
    { from: 'check', to: 'pay', branch: null },
  ],
} as unknown as CanvasGraph

describe('Allow going back', () => {
  it('lists only the steps before this one, nearest first', () => {
    expect(stepsBefore(GRAPH, 'check').map(n => n.id)).toEqual(['coach', 'train'])
  })

  it('turns on, and saves the step to go back to and the pages to go back', () => {
    const setConfig = vi.fn()
    const { rerender } = render(<GoBackFields node={GRAPH.nodes[3]} graph={GRAPH} setConfig={setConfig} />)
    expect(screen.queryByRole('combobox', { name: 'Go back to' })).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Allow going back' }))
    expect(setConfig).toHaveBeenLastCalledWith({ allowBack: true, backTo: undefined, backPages: undefined }, 'allowBack')

    const on = { ...GRAPH.nodes[3], config: { allowBack: true } }
    rerender(<GoBackFields node={on} graph={GRAPH} setConfig={setConfig} />)
    const to = screen.getByRole('combobox', { name: 'Go back to' })
    expect([...to.querySelectorAll('option')].map(o => o.textContent)).toEqual([
      'The steps that asked me (latest first, I pick on the card)', 'Choose coach', 'Choose train',
    ])
    fireEvent.change(to, { target: { value: 'train' } })
    expect(setConfig).toHaveBeenLastCalledWith({ backTo: 'train' }, 'backTo')
    fireEvent.change(screen.getByRole('combobox', { name: 'In the browser first' }), { target: { value: '1' } })
    expect(setConfig).toHaveBeenLastCalledWith({ backPages: 1 }, 'backPages')
  })
})
