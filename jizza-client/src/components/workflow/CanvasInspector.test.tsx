import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { CanvasInspector } from './CanvasInspector'
import { addAfter, emptyGraph, type CanvasGraph } from './canvasModel'

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

    fireEvent.change(screen.getByRole('textbox', { name: 'URL' }), { target: { value: 'https://hooks.example.com' } })
    expect(save).toBeEnabled()

    // Signing in with a bearer token makes the token mandatory too.
    fireEvent.change(screen.getByRole('combobox', { name: 'Authentication' }), { target: { value: 'bearer' } })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/^Token/), { target: { value: 'abc' } })
    expect(save).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Add a field' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'key 1' }), { target: { value: 'order.id' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Value 1' }), { target: { value: '{{ input.id }}' } })

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
    fireEvent.change(screen.getByLabelText(/^Message/), { target: { value: 'Hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(confirm).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
    expect(onSave).not.toHaveBeenCalled()
    confirm.mockRestore()
  })
})
