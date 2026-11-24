import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
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

  describe('re-wiring by dragging', () => {
    /** Board coordinates are client coordinates in these tests. */
    const flat = (view: ReturnType<typeof show>['view']) => {
      const board = view.container.querySelector('[role="region"] > div') as HTMLElement
      board.getBoundingClientRect = () => ({ left: 0, top: 0, right: 2000, bottom: 2000, width: 2000, height: 2000, x: 0, y: 0, toJSON: () => ({}) })
    }
    const middle = (id: string, graph = booking()) => {
      const b = wiresLayout(graph).at.get(id)!
      return [b.x + CARD_W / 2, b.y + CARD_H / 2] as const
    }
    const withLoose = () => {
      const graph = booking()
      graph.nodes.push({ id: 'loose', type: 'NOTIFY', label: 'Loose end', config: { message: 'x' }, x: 0, y: 0 })
      return graph
    }

    it('drags from an exit dot to a step to wire it', () => {
      const graph = withLoose()
      const { view, last } = show(graph)
      flat(view)
      const dot = screen.getByRole('button', { name: /Exit of Hold seats/ })
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', dot, 10, 10)
      pointer('pointerMove', window, x, y)
      expect(screen.getByTestId('wire-drag')).toBeInTheDocument()
      pointer('pointerUp', window, x, y)
      expect(last().edges).toContainEqual({ from: 'hold', to: 'loose', branch: null })
      expect(screen.queryByTestId('wire-drag')).not.toBeInTheDocument()
    })

    it('drags a wired exit to another step, moving its wire', () => {
      const graph = withLoose()
      const { view, last } = show(graph)
      flat(view)
      const dot = screen.getByRole('button', { name: /"rejected" exit of OK these seats/ })
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', dot, 10, 10)
      pointer('pointerMove', window, x, y)
      pointer('pointerUp', window, x, y)
      expect(last().edges).toContainEqual({ from: 'ok', to: 'loose', branch: 'rejected' })
      expect(last().edges.some(e => e.to === 'skip')).toBe(false)
    })

    it('drags the end of a hovered wire to another step, or off onto empty space', () => {
      const graph = withLoose()
      const { view, last } = show(graph)
      flat(view)
      fireEvent.mouseEnter(hitArea(view.container, 0))
      const end = screen.getByRole('button', { name: 'Move the end of the wire from Start' })
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', end, 10, 10)
      pointer('pointerMove', window, x, y)
      pointer('pointerUp', window, x, y)
      expect(last().edges).toContainEqual({ from: 'start', to: 'loose', branch: null })
      expect(last().edges.some(e => e.from === 'start' && e.to === 'open')).toBe(false)
    })

    it('takes a wire off when its end is let go on empty space', () => {
      const { view, last } = show()
      flat(view)
      fireEvent.mouseEnter(hitArea(view.container, 0))
      pointer('pointerDown', screen.getByRole('button', { name: 'Move the end of the wire from Start' }), 10, 10)
      pointer('pointerMove', window, 1900, 1900)
      pointer('pointerUp', window, 1900, 1900)
      expect(last().edges.some(e => e.from === 'start')).toBe(false)
    })

    it('still wires when the browser drops the let-go, using the last step the wire was over', () => {
      const graph = withLoose()
      const { view, last } = show(graph)
      flat(view)
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', screen.getByRole('button', { name: /Exit of Hold seats/ }), 10, 10)
      pointer('pointerMove', window, x, y)
      fireEvent(window, createEvent.pointerCancel(window))
      expect(last().edges).toContainEqual({ from: 'hold', to: 'loose', branch: null })
      expect(screen.queryByTestId('wire-drag')).not.toBeInTheDocument()
    })

    it('finishes a drag whose button was let go without a let-go event', () => {
      const graph = withLoose()
      const { view, last } = show(graph)
      flat(view)
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', screen.getByRole('button', { name: /Exit of Hold seats/ }), 10, 10)
      pointer('pointerMove', window, x, y)
      const ev = createEvent.pointerMove(window)
      for (const [k, v] of Object.entries({ clientX: x + 2, clientY: y, buttons: 0 })) Object.defineProperty(ev, k, { value: v })
      fireEvent(window, ev)
      expect(last().edges).toContainEqual({ from: 'hold', to: 'loose', branch: null })
    })

    it('leaves every step where it is when a wire is moved', () => {
      const graph = withLoose()
      const before = wiresLayout(graph).at
      const { view, last } = show(graph)
      flat(view)
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', screen.getByRole('button', { name: /"rejected" exit of OK these seats/ }), 10, 10)
      pointer('pointerMove', window, x, y)
      pointer('pointerUp', window, x, y)
      const after = wiresLayout(last()).at
      for (const [id, box] of before) expect(after.get(id)).toEqual(box)
    })

    it('refuses a wire that would make a loop, and says why', () => {
      const { view, onChange } = show()
      flat(view)
      const [x, y] = middle('open')
      pointer('pointerDown', screen.getByRole('button', { name: /"approved" exit of OK these seats/ }), 10, 10)
      pointer('pointerMove', window, x, y)
      pointer('pointerUp', window, x, y)
      expect(onChange).not.toHaveBeenCalled()
      expect(screen.getByText(/would make a loop/)).toBeInTheDocument()
    })

    it('keeps wires inside a locked group, but lets the wire into it move', () => {
      const graph: CanvasGraph = { ...withLoose(), groups: [{ id: 'g1', title: 'Pick seats', nodes: ['open', 'ok'], locked: true, collapsed: false }] }
      const { view, onChange, last } = show(graph)
      flat(view)
      // Open the site → OK these seats? is inside the group: its exit dot is locked.
      const dot = screen.getByRole('button', { name: /Exit of Open the site/ })
      pointer('pointerDown', dot, 10, 10)
      pointer('pointerMove', window, 1900, 1900)
      pointer('pointerUp', window, 1900, 1900)
      expect(onChange).not.toHaveBeenCalled()
      // Start → Open the site comes into the group, so it can be re-wired.
      const [x, y] = middle('loose', graph)
      pointer('pointerDown', screen.getByRole('button', { name: /Exit of Start/ }), 10, 10)
      pointer('pointerMove', window, x, y)
      pointer('pointerUp', window, x, y)
      expect(last().edges).toContainEqual({ from: 'start', to: 'loose', branch: null })
    })

    it('re-wires from the hover toolbar: Re-wire, then click the step', () => {
      const graph = withLoose()
      const { view, last } = show(graph)
      fireEvent.mouseEnter(hitArea(view.container, 0))
      fireEvent.click(screen.getByRole('button', { name: 'Re-wire' }))
      fireEvent.click(screen.getByRole('button', { name: 'Notify me: Loose end' }))
      expect(last().edges).toContainEqual({ from: 'start', to: 'loose', branch: null })
      expect(last().edges.some(e => e.from === 'start' && e.to === 'open')).toBe(false)
    })
  })

  it('moves a step by dragging it, and puts it back in line with auto align', () => {
    const { onChange, last } = show()
    const step = screen.getByRole('button', { name: 'Notify me: Tell me it was skipped' })
    expect(screen.getByRole('button', { name: /Auto align/ })).toBeDisabled()
    pointer('pointerDown', step, 100, 100)
    pointer('pointerMove', step, 260, 180)
    pointer('pointerUp', step, 260, 180)
    const moved = last().nodes.find(n => n.id === 'skip')!
    expect(moved.config.wiresAt).toBeDefined()
    // Letting go after a drag is not a click on the step.
    fireEvent.click(step)
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('aligns hand-placed steps again', () => {
    const graph = booking()
    graph.nodes = graph.nodes.map(n => (n.id === 'skip' ? { ...n, config: { ...n.config, wiresAt: { x: 900, y: 40 } } } : n))
    const { last } = show(graph)
    fireEvent.click(screen.getByRole('button', { name: /Auto align/ }))
    expect(last().nodes.find(n => n.id === 'skip')!.config).not.toHaveProperty('wiresAt')
  })

  describe('step groups', () => {
    const grouped = (locked: boolean, collapsed = false): CanvasGraph =>
      ({ ...booking(), groups: [{ id: 'g1', title: 'Pick seats', nodes: ['open', 'ok'], locked, collapsed }] })

    it('groups Shift+clicked steps', () => {
      const { last } = show()
      expect(screen.getByRole('button', { name: /^Group$/ })).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: 'Notify me: Open the site' }), { shiftKey: true })
      fireEvent.click(screen.getByRole('button', { name: 'Approval: OK these seats?' }), { shiftKey: true })
      fireEvent.click(screen.getByRole('button', { name: /^Group 2$/ }))
      expect(last().groups).toEqual([expect.objectContaining({ title: 'Group 1', nodes: ['open', 'ok'], locked: false })])
    })

    it('draws a locked group whose inside wires and steps cannot be changed', () => {
      const { onChange } = show(grouped(true))
      expect(screen.getByText('Pick seats')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Exit of Open the site/ })).toBeDisabled()
      // The wire from Open the site into OK these seats? takes no hover; the one into the group still does.
      expect(document.querySelectorAll('path[stroke="transparent"]')).toHaveLength(3)
      fireEvent.mouseEnter(screen.getByRole('button', { name: 'Approval: OK these seats?' }))
      expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Ungroup/ })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Unlock group' }))
      expect((onChange.mock.calls.at(-1)![0] as CanvasGraph).groups![0].locked).toBe(false)
    })

    it('picks the steps inside a box dragged on empty space, then groups them', () => {
      const { view, last } = show()
      const board = view.container.querySelector('[role="region"] > div') as HTMLElement
      board.getBoundingClientRect = () => ({ left: 0, top: 0, right: 2000, bottom: 2000, width: 2000, height: 2000, x: 0, y: 0, toJSON: () => ({}) })
      const layout = wiresLayout(booking())
      const open = layout.at.get('open')!
      const ok = layout.at.get('ok')!
      pointer('pointerDown', board, open.x - 10, open.y - 10)
      pointer('pointerMove', board, ok.x + 20, ok.y + 20)
      pointer('pointerUp', board, ok.x + 20, ok.y + 20)
      fireEvent.click(screen.getByRole('button', { name: /^Group 2$/ }))
      expect(last().groups![0].nodes.sort()).toEqual(['ok', 'open'])
    })

    it('starts the box on the empty space beside the steps too, dragging either way', () => {
      const { view, last } = show()
      const region = view.container.querySelector('[role="region"]') as HTMLElement
      const board = region.firstElementChild as HTMLElement
      // The steps sit in the middle of a wider board: 300px of empty space on their left.
      board.getBoundingClientRect = () => ({ left: 300, top: 0, right: 2300, bottom: 2000, width: 2000, height: 2000, x: 300, y: 0, toJSON: () => ({}) })
      const ok = wiresLayout(booking()).at.get('ok')!
      pointer('pointerDown', region, 20, 0)
      pointer('pointerMove', region, 300 + ok.x + 20, ok.y + 20)
      pointer('pointerUp', region, 300 + ok.x + 20, ok.y + 20)
      fireEvent.click(screen.getByRole('button', { name: /^Group \d+$/ }))
      expect(last().groups![0].nodes).toEqual(expect.arrayContaining(['ok', 'open']))
    })

    it('renames a group from the pencil on its title bar', () => {
      const { last } = show(grouped(false))
      fireEvent.click(screen.getByRole('button', { name: 'Rename Pick seats' }))
      const input = screen.getByRole('textbox', { name: 'Group title' })
      fireEvent.change(input, { target: { value: 'Coach and date' } })
      fireEvent.keyDown(input, { key: 'Enter' })
      expect(last().groups![0].title).toBe('Coach and date')
    })

    it('offers the board actions on a right-click on empty space, and groups from there', () => {
      const { view, last } = show()
      const board = view.container.querySelector('[role="region"] > div') as HTMLElement
      fireEvent.contextMenu(board, { clientX: 10, clientY: 10 })
      const menu = screen.getByRole('menu')
      for (const item of ['Go to Start', 'Preview path', 'Auto align', 'Group steps', 'Pick all steps', 'Search steps']) {
        expect(within(menu).getByRole('menuitem', { name: new RegExp(item) })).toBeInTheDocument()
      }
      expect(within(menu).getByRole('menuitem', { name: /^Group steps/ })).toBeDisabled()
      fireEvent.click(within(menu).getByRole('menuitem', { name: /Pick all steps/ }))
      fireEvent.contextMenu(board, { clientX: 10, clientY: 10 })
      fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Group 4 steps/ }))
      expect(last().groups![0].nodes).toHaveLength(4)
    })

    it('offers step actions on a right-click on a step, and keeps a locked step from being deleted', () => {
      show(grouped(true))
      fireEvent.contextMenu(screen.getByRole('button', { name: 'Approval: OK these seats?' }), { clientX: 10, clientY: 10 })
      const menu = screen.getByRole('menu')
      expect(within(menu).getByRole('menuitem', { name: /Delete/ })).toBeDisabled()
      expect(within(menu).getByRole('menuitem', { name: /Unlock group/ })).toBeInTheDocument()
      expect(within(menu).queryByRole('menuitem', { name: /Remove from group/ })).not.toBeInTheDocument()
    })

    it('folds a group into one card that its wires meet', () => {
      const { last } = show(grouped(false, true))
      expect(screen.queryByRole('button', { name: 'Notify me: Open the site' })).not.toBeInTheDocument()
      expect(screen.getByText('Pick seats')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Unfold Pick seats' }))
      expect(last().groups![0].collapsed).toBe(false)
    })
  })
})

/** jsdom has no PointerEvent, so position, button and pointer are set on the event by hand. */
function pointer(kind: 'pointerDown' | 'pointerMove' | 'pointerUp', el: HTMLElement | Window, x: number, y: number) {
  const ev = createEvent[kind](el)
  for (const [k, v] of Object.entries({ clientX: x, clientY: y, button: 0, buttons: kind === 'pointerUp' ? 0 : 1, pointerId: 1 })) Object.defineProperty(ev, k, { value: v })
  fireEvent(el, ev)
}

/** A board that keeps its own graph, as the workflow form does, so undo and paste build on each other. */
function Live({ start, seen }: { start: CanvasGraph; seen: (g: CanvasGraph) => void }) {
  const [graph, setGraph] = useState(start)
  return <WiresBoard graph={graph} onChange={g => { seen(g); setGraph(g) }} />
}

describe('WiresBoard editing tools', () => {
  beforeEach(() => {
    queryClient.clear()
    vi.spyOn(api, 'get').mockResolvedValue([])
  })

  function live(graph = booking()) {
    const seen = vi.fn()
    const view = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><Live start={graph} seen={seen} /></MemoryRouter>
      </QueryClientProvider>,
    )
    // Shortcuts apply once the pointer has been in the board.
    fireEvent.pointerDown(view.container.querySelector('.jizza-wires')!)
    return { view, last: () => seen.mock.calls.at(-1)![0] as CanvasGraph }
  }

  it('undoes and redoes a change from the toolbar and the keyboard', () => {
    const { last } = live()
    const step = screen.getByRole('button', { name: 'Notify me: Open the site' })
    fireEvent.mouseEnter(step)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(last().nodes.some(n => n.id === 'open')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: /^Undo/ }))
    expect(last().nodes.some(n => n.id === 'open')).toBe(true)
    fireEvent.keyDown(window, { key: 'z', ctrlKey: true, shiftKey: true })
    expect(last().nodes.some(n => n.id === 'open')).toBe(false)
    fireEvent.keyDown(window, { key: 'z', ctrlKey: true })
    expect(last().nodes.some(n => n.id === 'open')).toBe(true)
  })

  it('copies, pastes and deletes picked steps with the keyboard', () => {
    const { last } = live()
    fireEvent.click(screen.getByRole('button', { name: 'Notify me: Hold seats' }))
    fireEvent.keyDown(window, { key: 'c', ctrlKey: true })
    fireEvent.keyDown(window, { key: 'v', ctrlKey: true })
    expect(last().nodes.filter(n => n.label === 'Hold seats')).toHaveLength(2)
    // The pasted copy is picked, so Delete takes it away again.
    fireEvent.keyDown(window, { key: 'Delete' })
    expect(last().nodes.filter(n => n.label === 'Hold seats')).toHaveLength(1)
  })

  it('offers copy, disconnecting and notes on a step, and adds a note', () => {
    const { last } = live()
    fireEvent.contextMenu(screen.getByRole('button', { name: 'Notify me: Open the site' }), { clientX: 10, clientY: 10 })
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Copy/ })).toBeInTheDocument()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Disconnect/ }))
    expect(last().edges.some(e => e.from === 'open' || e.to === 'open')).toBe(false)
    fireEvent.contextMenu(screen.getByRole('button', { name: 'Notify me: Open the site' }), { clientX: 10, clientY: 10 })
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Add note/ }))
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
  })

  it('goes full screen and back with Escape', () => {
    live()
    fireEvent.click(screen.getByRole('button', { name: 'Full screen' }))
    expect(document.body.querySelector('.fixed.inset-0 .jizza-wires')).not.toBeNull()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(document.body.querySelector('.fixed.inset-0 .jizza-wires')).toBeNull()
  })

  it('warns about a step whose agent is paused', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => (url.startsWith('/api/agents')
      ? [{ id: 'a1', key: 'mail', name: 'Mailer', status: 'active', enabled: false }]
      : []) as never)
    live(fromGraph({
      nodes: [{ id: 'start', type: 'TRIGGER', label: 'Start', config: {} }, { id: 'send', type: 'AGENT_STEP', label: 'Send', config: { agentId: 'a1', instruction: 'Send it' } }],
      edges: [{ from: 'start', to: 'send' }],
    }))
    const step = await screen.findByRole('button', { name: /Send$/ })
    await vi.waitFor(() => expect(step.getAttribute('title')).toContain('Mailer is paused'))
  })
})
