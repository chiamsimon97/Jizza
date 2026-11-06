import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createEvent, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { WiresBoard } from './WiresBoard'
import { fromGraph, type CanvasGraph } from './canvasModel'
import { CARD_H, CARD_W, wiresLayout } from './wiresModel'
import { api } from '@/lib/api'
import { queryClient } from '@/lib/queryClient'

function booking(): CanvasGraph {
  return fromGraph({
    nodes: [
      { id: 'start', type: 'TRIGGER', label: 'Start', config: {} },
      { id: 'open', type: 'NOTIFY', label: 'Open the site', config: { message: 'open' } },
      { id: 'ok', type: 'APPROVAL', label: 'OK these seats?', config: {} },
      { id: 'hold', type: 'NOTIFY', label: 'Hold seats', config: { message: 'hold' } },
      { id: 'skip', type: 'NOTIFY', label: 'Tell me it was skipped', config: { message: 'skip' } },
    ],
    edges: [
      { from: 'start', to: 'open' }, { from: 'open', to: 'ok' },
      { from: 'ok', to: 'hold', branch: 'approved' }, { from: 'ok', to: 'skip', branch: 'rejected' },
    ],
  })
}

function show(graph = booking(), onChange = vi.fn()) {
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><WiresBoard graph={graph} onChange={onChange} /></MemoryRouter>
    </QueryClientProvider>,
  )
  return { onChange, view, last: () => onChange.mock.calls.at(-1)![0] as CanvasGraph }
}

/** The invisible wide stroke over a wire that takes the hover, for the wire leaving {@code from}. */
function hitArea(container: HTMLElement, index: number) {
  return container.querySelectorAll('path[stroke="transparent"]')[index] as SVGPathElement
}

/** jsdom's drag events carry no pointer position, so it is set on the event by hand. */
function dragAt(kind: 'dragOver' | 'drop', el: HTMLElement, x: number, y: number, dataTransfer: ReturnType<typeof transfer>) {
  const ev = createEvent[kind](el, { dataTransfer })
  Object.defineProperty(ev, 'clientX', { value: x })
  Object.defineProperty(ev, 'clientY', { value: y })
  fireEvent(el, ev)
}

const transfer = () => {
  const data = new Map<string, string>()
  return { setData: (k: string, v: string) => data.set(k, v), getData: (k: string) => data.get(k) ?? '', dropEffect: '', effectAllowed: '' }
}

describe('WiresBoard', () => {
  beforeEach(() => {
    queryClient.clear()
    vi.spyOn(api, 'get').mockResolvedValue([])
  })

  it('shows every step with its exits, and says it is ready to save', () => {
    show()
    expect(screen.getByRole('button', { name: 'Approval: OK these seats?' })).toBeInTheDocument()
    expect(screen.getByText('approved')).toBeInTheDocument()
    expect(screen.getByText('rejected')).toBeInTheDocument()
    expect(screen.getByText('Ready to save')).toBeInTheDocument()
  })

  it('offers delete, add step and label on a hovered wire', () => {
    const { onChange, last, view } = show()
    fireEvent.mouseEnter(hitArea(view.container, 0))
    expect(screen.getByRole('button', { name: 'Add step' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add label' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete wire' }))
    expect(onChange).toHaveBeenCalled()
    expect(last().edges.some(e => e.from === 'start')).toBe(false)
  })

  it('labels a wire from its hover toolbar', () => {
    const { last, view } = show()
    fireEvent.mouseEnter(hitArea(view.container, 1))
    fireEvent.click(screen.getByRole('button', { name: 'Add label' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Wire label' }), { target: { value: 'page open' } })
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Wire label' })).getByRole('button', { name: 'Save' }))
    expect(last().nodes.find(n => n.id === 'open')!.config.wireLabels).toEqual({ out: 'page open' })
  })

  it('opens the step settings only from Edit, and clones and deletes from the hover toolbar', () => {
    const { last } = show()
    const step = screen.getByRole('button', { name: 'Notify me: Open the site' })
    fireEvent.click(step)
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()

    fireEvent.mouseEnter(step)
    fireEvent.click(screen.getByRole('button', { name: 'Edit step' }))
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()

    fireEvent.mouseEnter(step)
    fireEvent.click(screen.getByRole('button', { name: 'Clone' }))
    expect(last().nodes.filter(n => n.label === 'Open the site')).toHaveLength(2)

    fireEvent.mouseEnter(step)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(last().edges).toContainEqual({ from: 'start', to: 'ok', branch: null })
  })

  it('wires a step dropped onto another step in after it', () => {
    const graph = booking()
    const { last } = show(graph)
    const box = wiresLayout(graph).at.get('open')!
    const data = transfer()
    fireEvent.dragStart(screen.getByRole('button', { name: /^Notify me ?Sends you/ }), { dataTransfer: data })
    const board = screen.getByRole('region', { name: 'Workflow wires' })
    dragAt('dragOver', board, box.x + CARD_W / 2, box.y + CARD_H / 2, data)
    expect(screen.getByText('Drop to wire it in after this step')).toBeInTheDocument()
    dragAt('drop', board, box.x + CARD_W / 2, box.y + CARD_H / 2, data)
    const added = last().nodes.find(n => !graph.nodes.some(o => o.id === n.id))!
    expect(added.type).toBe('NOTIFY')
    expect(last().edges).toContainEqual({ from: 'open', to: added.id, branch: null })
    expect(last().edges).toContainEqual({ from: added.id, to: 'ok', branch: null })
  })

  it('wires an exit by hand: click the exit, then the step', () => {
    const graph = booking()
    graph.nodes.push({ id: 'loose', type: 'NOTIFY', label: 'Loose end', config: { message: 'x' }, x: 0, y: 0 })
    const { last } = show(graph)
    fireEvent.click(screen.getByRole('button', { name: /Exit of Hold seats/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Notify me: Loose end' }))
    expect(last().edges).toContainEqual({ from: 'hold', to: 'loose', branch: null })
  })
})
