import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EditorView } from '@codemirror/view'
import { CanvasInspector } from './CanvasInspector'
import { addAfter, emptyGraph, type CanvasGraph } from './canvasModel'

/** Types into a field where earlier steps' data shows as chips (a CodeMirror editor). */
function type(box: HTMLElement, text: string) {
  const view = EditorView.findFromDOM(box)!
  act(() => { view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text }, userEvent: 'input.type' }) })
}

function open(graph: CanvasGraph, id: string, onSave = vi.fn(), onClose = vi.fn()) {
  render(
    <MemoryRouter>
      <CanvasInspector node={graph.nodes.find(n => n.id === id)!} graph={graph} issues={[]} readOnly={false}
        onDirty={() => {}} onSave={onSave} onClose={onClose} />
    </MemoryRouter>,
  )
  return { onSave, onClose }
}

describe('CanvasInspector', () => {
  it('has Close and Save instead of Remove step, and saves only once the mandatory fields are filled', () => {
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const { onSave } = open(graph, id)
    expect(screen.queryByRole('button', { name: /remove step/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close' })).toBeEnabled()
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()

    type(screen.getByRole('textbox', { name: 'URL' }), 'https://hooks.example.com')
    expect(save).toBeEnabled()

    // Signing in with a bearer token makes the token mandatory too.
    fireEvent.change(screen.getByRole('combobox', { name: 'Authentication' }), { target: { value: 'bearer' } })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/^Token/), { target: { value: 'abc' } })
    expect(save).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Add a field' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'key 1' }), { target: { value: 'order.id' } })
    type(screen.getByRole('textbox', { name: 'Value 1' }), '{{ input.id }}')

    fireEvent.click(save)
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config).toMatchObject({
      url: 'https://hooks.example.com', bodyType: 'json', auth: { type: 'bearer', token: 'abc' }, fields: { 'order.id': '{{ input.id }}' },
    })
  })

  it('drops unsaved changes on Close, once confirmed', () => {
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'NOTIFY')
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { onSave, onClose } = open(graph, id)
    type(screen.getByRole('textbox', { name: 'Message' }), 'Hello')
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(confirm).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
    expect(onSave).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('has no "go to" pickers: connections are made on the canvas', () => {
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'NOTIFY')
    open(graph, id)
    expect(screen.queryByText(/go to/i)).not.toBeInTheDocument()
  })

  it('shows mapped values as chips and inserts one from the picker', () => {
    const first = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const { graph, id } = addAfter(first.graph, first.id, null, 'NOTIFY')
    const withMessage = { ...graph, nodes: graph.nodes.map(n => (n.id === id ? { ...n, config: { message: `Status {{ nodes.${first.id}.status }}` } } : n)) }
    const { onSave } = open(withMessage, id)
    const box = screen.getByRole('textbox', { name: 'Message' })
    expect(box.querySelector('[data-expr]')).toHaveTextContent('Send webhook›status')

    fireEvent.click(screen.getAllByRole('button', { name: 'Insert data from earlier steps' })[1])
    fireEvent.click(screen.getByRole('button', { name: 'Send webhook' }))
    fireEvent.click(screen.getByRole('button', { name: /^body/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config.message).toBe(`Status {{ nodes.${first.id}.status }}{{ nodes.${first.id}.body }}`)
  })

  it('opens the data picker with Ctrl+E inside a mapped field', () => {
    const first = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const { graph, id } = addAfter(first.graph, first.id, null, 'NOTIFY')
    open(graph, id)
    expect(screen.queryByRole('dialog', { name: 'Insert data' })).not.toBeInTheDocument()
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Message' }), { key: 'e', ctrlKey: true })
    expect(screen.getByRole('dialog', { name: 'Insert data' })).toBeInTheDocument()
  })

  it('writes a condition as a formula: references are chips, and a chip can be pointed at other data', () => {
    const first = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const { graph, id } = addAfter(first.graph, first.id, null, 'FILTER')
    const withCondition = { ...graph, nodes: graph.nodes.map(n => (n.id === id ? { ...n, config: { condition: `nodes.${first.id}.status.round(0) == 200` } } : n)) }
    const { onSave } = open(withCondition, id)
    const box = screen.getByRole('textbox', { name: 'Carry on only if' })
    const chip = box.querySelector<HTMLElement>('[data-chip]')!
    expect(chip).toHaveTextContent('Send webhook›status')
    // The function after the chip is script, coloured as a function.
    expect(box.querySelector('.cm-fx-fn')).toHaveTextContent('round')

    fireEvent.mouseDown(chip)
    expect(screen.getByText('Point this chip at other data')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Send webhook' }))
    fireEvent.click(screen.getByRole('button', { name: /^ok/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config.condition).toBe(`nodes.${first.id}.ok.round(0) == 200`)
  })
})
