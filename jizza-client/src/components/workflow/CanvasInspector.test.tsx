import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EditorView } from '@codemirror/view'
import { CanvasInspector, type WorkflowOption } from './CanvasInspector'
import { addAfter, emptyGraph, type CanvasGraph } from './canvasModel'
import type { ScriptFunction } from '@/api/functions'
import { devicesApi } from '@/api/devices'
import { queryClient } from '@/lib/queryClient'
import type { Device, DeviceActionSpec } from '@/types'

/** Types into a field where earlier steps' data shows as chips (a CodeMirror editor). */
function type(box: HTMLElement, text: string) {
  const view = EditorView.findFromDOM(box)!
  act(() => { view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text }, userEvent: 'input.type' }) })
}

function open(graph: CanvasGraph, id: string, onSave = vi.fn(), onClose = vi.fn(), workflows?: WorkflowOption[]) {
  render(
    <MemoryRouter>
      <CanvasInspector node={graph.nodes.find(n => n.id === id)!} graph={graph} issues={[]} readOnly={false} workflows={workflows}
        onDirty={() => {}} onSave={onSave} onClose={onClose} />
    </MemoryRouter>,
  )
  return { onSave, onClose }
}

describe('CanvasInspector', () => {
  it('offers a device\'s own tools with their fields, and its saved recordings as a list', async () => {
    const spec = (over: Partial<DeviceActionSpec>): DeviceActionSpec => ({
      action: 'browser.runRecording', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Run a recording',
      description: '', input: { type: 'object', properties: { recordingId: { type: 'string', 'x-items': 'recordings' } }, required: ['recordingId'] },
      output: null, conditions: [], platforms: [], defaultTimeoutSec: 120, ...over,
    })
    const laptop = {
      id: 'd1', name: 'Work laptop', platform: 'WINDOWS', online: true, actions: ['browser.runRecording@1', 'x-acme.export@1'],
      tools: [spec({ action: 'x-acme.export', capability: 'x-acme', actionType: 'MONEY', title: 'Export ledger',
        input: { type: 'object', properties: { ledger: { type: 'string' } }, required: ['ledger'] } })],
      items: { recordings: [{ id: 'rec-1', name: 'Download invoices' }] },
    } as unknown as Device
    queryClient.clear()
    vi.spyOn(devicesApi, 'catalog').mockResolvedValue([spec({})])
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'DEVICE_ACTION')
    const onSave = vi.fn()
    open(graph, id, onSave)

    const action = screen.getByRole('combobox', { name: /^Action/ })
    expect(await screen.findByRole('option', { name: 'Export ledger' })).toBeInTheDocument()
    fireEvent.change(action, { target: { value: 'x-acme.export' } })
    expect(screen.getByText(/counts as moving money/)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'ledger' })).toBeInTheDocument()

    fireEvent.change(action, { target: { value: 'browser.runRecording' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'recordingId' }), { target: { value: 'rec-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config).toMatchObject({ action: 'browser.runRecording', inputs: { recordingId: 'rec-1' } })
  })

  it('runs a saved function from a Script step: pick it, map its inputs, see the result keys', () => {
    const fn: ScriptFunction = {
      id: 'fn-1', name: 'orderTotal', description: 'Adds up the lines', script: 'return {}', returnType: 'map',
      inputs: [{ name: 'lines', type: 'list', required: true }, { name: 'rate', type: 'number', default: 0.1 }],
      returnFields: [{ path: 'total', type: 'number' }, { path: 'customer.email', type: 'text' }],
      usedBy: [], createdAt: '', updatedAt: '', scope: 'own',
    }
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'SCRIPT')
    const onSave = vi.fn()
    render(
      <MemoryRouter>
        <CanvasInspector node={graph.nodes.find(n => n.id === id)!} graph={graph} issues={[]} readOnly={false} functions={[fn]}
          onDirty={() => {}} onSave={onSave} onClose={() => {}} />
      </MemoryRouter>,
    )
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByRole('combobox', { name: 'Function' }), { target: { value: 'fn-1' } })
    expect(screen.getByText('customer.email')).toBeInTheDocument()
    // lines is required and has no default, so it must be mapped; rate has a default.
    expect(save).toBeDisabled()
    type(screen.getByRole('textbox', { name: 'Input lines' }), '{{ input.lines }}')
    expect(save).toBeEnabled()
    fireEvent.click(save)
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config).toEqual({ functionId: 'fn-1', functionName: 'orderTotal', inputs: { lines: '{{ input.lines }}' } })
  })

  it('maps a module\'s inputs one field each and waits for the required ones', () => {
    const module: WorkflowOption = {
      id: 'wf-double', name: 'Double it', status: 'APPROVED', graph: {
        nodes: [
          { id: 'start', type: 'TRIGGER', config: { inputs: [{ name: 'amount', type: 'number', required: true }, { name: 'label', type: 'text', default: 'total' }] } },
          { id: 'end', type: 'END', config: { outputs: [{ name: 'doubled', type: 'number', value: '{{ input.amount * 2 }}' }] } },
        ],
      },
    }
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'SUB_WORKFLOW')
    const withModule = { ...graph, nodes: graph.nodes.map(n => (n.id === id ? { ...n, config: { workflowId: 'wf-double' } } : n)) }
    const { onSave } = open(withModule, id, vi.fn(), vi.fn(), [module])
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    expect(screen.getByText('doubled')).toBeInTheDocument()

    type(screen.getByRole('textbox', { name: 'Input amount' }), '{{ input.total }}')
    expect(save).toBeEnabled()
    fireEvent.click(save)
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config).toMatchObject({ workflowId: 'wf-double', inputs: { amount: '{{ input.total }}' } })
  })

  it('declares a workflow\'s inputs on its Start step', () => {
    const graph = emptyGraph()
    const { onSave } = open(graph, 'start')
    fireEvent.click(screen.getByRole('button', { name: 'Add an input' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Name of input 1' }), { target: { value: 'customer email' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Required' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect((onSave.mock.calls[0][0] as CanvasGraph).nodes[0].config.inputs).toEqual([{ name: 'customer_email', type: 'text', required: true }])
  })

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

  it('inserts a function from the function list', () => {
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'NOTIFY')
    const withMessage = { ...graph, nodes: graph.nodes.map(n => (n.id === id ? { ...n, config: { message: 'Hi {{ input.name }}' } } : n)) }
    const { onSave } = open(withMessage, id)
    fireEvent.click(screen.getAllByRole('button', { name: /Functions/ })[1])
    fireEvent.change(screen.getByPlaceholderText('Search functions'), { target: { value: 'upper' } })
    fireEvent.click(screen.getByRole('button', { name: /^ƒ\s*upper/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config.message).toBe('Hi {{ input.name }}{{ upper() }}')
  })
})
