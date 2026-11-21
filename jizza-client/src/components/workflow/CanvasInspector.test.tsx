import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EditorView } from '@codemirror/view'
import { CanvasInspector, type WorkflowOption } from './CanvasInspector'
import { addAfter, emptyGraph, WEB_PALETTE, type CanvasGraph } from './canvasModel'
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

/** Opens every folded "More options" so the details behind them can be checked. */
async function unfold() {
  for (const b of await screen.findAllByRole('button', { name: /^More options/, expanded: false })) fireEvent.click(b)
}

describe('CanvasInspector', () => {
  it('offers a device\'s own tools with their fields, and its saved Shortcuts as a list', async () => {
    const spec = (over: Partial<DeviceActionSpec>): DeviceActionSpec => ({
      action: 'phone.shortcut', version: 1, capability: 'shortcuts', actionType: 'UPDATE', title: 'Run an iOS Shortcut',
      description: '', input: { type: 'object', properties: { name: { type: 'string', 'x-items': 'shortcuts' } }, required: ['name'] },
      output: null, conditions: [], platforms: [], defaultTimeoutSec: 120, ...over,
    })
    const laptop = {
      id: 'd1', name: 'Work laptop', platform: 'WINDOWS', online: true, actions: ['phone.shortcut@1', 'x-acme.export@1'],
      tools: [spec({ action: 'x-acme.export', capability: 'x-acme', actionType: 'MONEY', title: 'Export ledger',
        input: { type: 'object', properties: { ledger: { type: 'string' } }, required: ['ledger'] } })],
      items: { shortcuts: [{ id: 'Log water', name: 'Log water' }] },
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

    fireEvent.change(action, { target: { value: 'phone.shortcut' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'name' }), { target: { value: 'Log water' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config).toMatchObject({ action: 'phone.shortcut', inputs: { name: 'Log water' } })
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
    fireEvent.click(screen.getByRole('checkbox', { name: 'Start a new browser session' }))
    fireEvent.click(save)
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === id)!
    expect(saved.config).toMatchObject({
      workflowId: 'wf-double', newBrowserSession: true,
      inputs: { amount: '{{ input.total }}' },
    })
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
    fireEvent.click(screen.getByRole('button', { name: /^Authentication/ }))
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

  it('renders web schema inputs and round-trips a locator bundle without dropping metadata', async () => {
    queryClient.clear()
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const body = url.includes('/api/devices/catalog') ? [{
        action: 'browser.click', version: 2, capability: 'browser', actionType: 'UPDATE', title: 'Click',
        description: 'Clicks an element.', defaultTimeoutSec: 15, conditions: [], platforms: [],
        input: {
          type: 'object', required: ['element', 'button'],
          properties: {
            element: { type: 'object' },
            button: { enum: ['left', 'right', 'middle'] },
            clickCount: { type: 'integer', enum: [1, 2] },
            trusted: { type: 'boolean' },
            timeoutMs: { type: 'integer' },
            screenshotOnError: { type: 'boolean' },
          },
        },
        output: { type: 'object', properties: { clicked: { type: 'boolean' } } },
      }] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE[1])
    const metadata = {
      frames: [{ strategies: [{ by: 'css', value: 'iframe' }] }],
      shadow: [{ by: 'css', value: 'my-widget' }],
      fingerprint: { tag: 'button', text: 'Go' },
      label: 'Go button',
      strategies: [{ by: 'css', value: '#go' }],
    }
    const graph = {
      ...made.graph,
      nodes: made.graph.nodes.map(n => n.id === made.id
        ? { ...n, config: { ...n.config, inputs: { element: metadata } } } : n),
    }
    const { onSave } = open(graph, made.id)
    await screen.findByRole('combobox', { name: 'Button' })
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    fireEvent.change(screen.getByRole('combobox', { name: 'Button' }), { target: { value: 'right' } })
    fireEvent.change(screen.getByRole('combobox', { name: /Click count/i }), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Edit selectors' }))
    expect(screen.getByRole('textbox', { name: 'Element label' })).toHaveValue('Go button')
    type(screen.getByRole('textbox', { name: 'Selector value 1' }), '#continue')
    fireEvent.click(screen.getByRole('button', { name: 'Save selectors' }))
    fireEvent.click(screen.getByRole('button', { name: /^Advanced options/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Trusted input' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toEqual({
      button: 'right',
      clickCount: 2,
      trusted: true,
      element: { ...metadata, strategies: [{ by: 'css', value: '#continue' }] },
    })
    vi.unstubAllGlobals()
  })

  it('turns a recorded click into a choice made at run time, keeping the recorded card', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.click', version: 2, capability: 'browser', actionType: 'UPDATE', title: 'Click', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element'], properties: { element: locator } }, output: { type: 'object' } },
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element', 'item'], properties: { element: locator, item: { type: 'string' } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE[1])
    const card = { strategies: [{ by: 'text', value: 'Ramayana' }], label: 'Ramayana' }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs: { element: card } } } : n) }
    const { onSave } = open(graph, made.id)
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Choose the item at run time' }))
    type(await screen.findByRole('textbox', { name: 'Item to choose' }), '{{ input.movie }}')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config).toMatchObject({ action: 'browser.clickItem', version: 1, inputs: { element: card, item: '{{ input.movie }}' } })
    vi.unstubAllGlobals()
  })

  it('attaches notes, questions and item pictures to the approval card', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element', 'item'], properties: { element: locator, item: { type: 'string' } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
    const inputs = { element: { strategies: [{ by: 'css', value: '.train' }], label: 'Train' }, item: '{{ input.train }}' }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs } } : n) }
    const { onSave } = open(graph, made.id)

    fireEvent.click(await screen.findByRole('checkbox', { name: 'Send a picture of each item with the question' }))
    fireEvent.click(screen.getByRole('button', { name: /^Ask on the approval card/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Add a note' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Note 1' }), { target: { value: 'Check the running days' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add a question' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Question 2' }), { target: { value: 'Berth preference' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'Answer type of question 2' }), { target: { value: 'select' } })
    const choices = screen.getByRole('textbox', { name: 'Choices of question 2' })
    fireEvent.change(choices, { target: { value: 'Lower, Upper' } })
    fireEvent.blur(choices)

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.screenshots).toBe(true)
    expect(saved.config.details).toEqual({ blocks: [
      { kind: 'note', text: 'Check the running days' },
      { kind: 'field', label: 'Berth preference', name: 'berth_preference', type: 'select', options: ['Lower', 'Upper'] },
    ] })
    vi.unstubAllGlobals()
  })

  describe('a picture of each item, field by field', () => {
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }

    /** A class chosen inside a train: two lists, so two fields that can each have pictures. */
    function openInACard(screenshots?: unknown) {
      queryClient.clear()
      vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
        const body = String(input).includes('/api/devices/catalog') ? [
          { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
            conditions: [], platforms: [], input: { type: 'object', required: ['element', 'item'], properties: { element: locator, item: { type: 'string' } } }, output: { type: 'object' } },
        ] : []
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }))
      const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
      const inputs = {
        element: { strategies: [{ by: 'css', value: 'td' }], label: 'Coach' }, item: '{{ input.coach }}',
        within: { element: { strategies: [{ by: 'css', value: '.card' }], label: 'Train' }, item: '{{ input.train }}' },
      }
      const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs, ...(screenshots === undefined ? {} : { screenshots }) } } : n) }
      const { onSave } = open(graph, made.id)
      const saved = async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        await waitFor(() => expect(onSave).toHaveBeenCalled())
        return (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!.config.screenshots
      }
      return { saved }
    }

    const cards = () => screen.findByRole('checkbox', { name: 'Send a picture of each card with the question' })
    const items = () => screen.findByRole('checkbox', { name: 'Send a picture of each item with the question' })

    it('puts a switch under each field, and none shared at the foot', async () => {
      openInACard()
      expect(await cards()).not.toBeChecked()
      expect(await items()).not.toBeChecked()
      expect(screen.getAllByRole('checkbox', { name: /Send a picture of each/ })).toHaveLength(2)
      vi.unstubAllGlobals()
    })

    it('asks for the trains only', async () => {
      const { saved } = openInACard()
      fireEvent.click(await cards())
      expect(await items()).not.toBeChecked()
      expect(await saved()).toEqual({ 'within.item': true })
      vi.unstubAllGlobals()
    })

    it('asks for the classes only', async () => {
      const { saved } = openInACard()
      fireEvent.click(await items())
      expect(await cards()).not.toBeChecked()
      expect(await saved()).toEqual({ item: true })
      vi.unstubAllGlobals()
    })

    it('asks for both as one true', async () => {
      const { saved } = openInACard()
      fireEvent.click(await cards())
      fireEvent.click(await items())
      expect(await saved()).toBe(true)
      vi.unstubAllGlobals()
    })

    it('shows a step saved with the one switch as asking for both', async () => {
      const { saved } = openInACard(true)
      expect(await cards()).toBeChecked()
      expect(await items()).toBeChecked()
      // Turning one off leaves the other as it was.
      fireEvent.click(await cards())
      expect(await cards()).not.toBeChecked()
      expect(await items()).toBeChecked()
      expect(await saved()).toEqual({ item: true })
      vi.unstubAllGlobals()
    })

    it('tells what each switch does only while it is on', async () => {
      openInACard({ 'within.item': true })
      expect(await screen.findByText(/Reading the cards also takes a picture of each one/)).toBeInTheDocument()
      expect(screen.queryByText(/Reading the list also takes a picture of each card/)).not.toBeInTheDocument()
      fireEvent.click(await items())
      expect(await screen.findByText(/Reading the list also takes a picture of each card/)).toBeInTheDocument()
      vi.unstubAllGlobals()
    })
  })

  it('sets up a device layer and asks where a removed button\'s steps go', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.fillLayer', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Device layer', defaultTimeoutSec: 180,
          conditions: [], platforms: [], input: { type: 'object', required: ['fields', 'buttons'], properties: { fields: { type: 'array' }, buttons: { type: 'array' }, root: locator } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const el = { strategies: [{ by: 'css', value: '#x' }], label: 'X' }
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.fillLayer')!)
    const inputs = {
      groups: [{ id: 'g1', title: 'Details' }],
      fields: [
        { id: 'f1', label: 'Project', kind: 'select', section: 'config', group: 'g1', element: el, options: ['Jizza', 'Atlas'] },
        { id: 'f2', label: 'Milestone', kind: 'select', section: 'config', group: 'g1', element: el },
      ],
      buttons: [{ id: 'b1', name: 'Submit', kind: 'submit', element: el }, { id: 'b2', name: 'Cancel', kind: 'cancel', element: el }],
    }
    const withLayer = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs } } : n) }
    const after = addAfter(withLayer, made.id, 'b2', 'NOTIFY')
    const { onSave } = open(after.graph, made.id)

    // Each field shows its value and Mandatory up front; where it is on the page, its kind and
    // its group wait behind More options.
    expect(await screen.findByRole('checkbox', { name: /Project is mandatory/ })).not.toBeChecked()
    expect(screen.queryByRole('combobox', { name: 'Kind of Project' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit selectors' })).not.toBeInTheDocument()

    // A field that follows the one above it.
    await unfold()
    fireEvent.change(await screen.findByRole('combobox', { name: 'Milestone follows' }), { target: { value: 'f1' } })
    expect(screen.getByTestId('layer-follows')).toHaveTextContent('Loads after Project is chosen')

    // Cancel has a step after it: removing it asks first, and Move hands that step to Submit.
    fireEvent.click(screen.getByRole('button', { name: 'Remove button Cancel' }))
    const dialog = screen.getByRole('alertdialog', { name: 'Remove Cancel' })
    expect(dialog).toHaveTextContent('"Cancel" leads to')
    fireEvent.change(screen.getByRole('combobox', { name: 'Move its steps to' }), { target: { value: 'b1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Move' }))

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = onSave.mock.calls[0][0] as CanvasGraph
    const node = saved.nodes.find(n => n.id === made.id)!
    expect((node.config.inputs as { buttons: { id: string }[] }).buttons.map(b => b.id)).toEqual(['b1'])
    expect((node.config.inputs as { fields: { id: string; dependsOn?: string }[] }).fields[1]).toMatchObject({ dependsOn: 'f1' })
    expect(saved.edges).toContainEqual({ from: made.id, to: after.id, branch: 'b1' })
    vi.unstubAllGlobals()
  })

  it('chooses inside a card of another list and loads more of a growing list', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element'], properties: {
            element: locator, item: { type: 'string' }, within: { type: 'object' }, loadMore: { type: 'object' },
            prefer: { enum: ['ask', 'first', 'lowestPrice', 'highestPrice'] } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
    const card = { strategies: [{ by: 'text', value: '7:00 PM' }], label: 'Showtime' }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs: { element: card, item: '{{ input.time }}' } } } : n) }
    const { onSave } = open(graph, made.id)
    await unfold()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Choose inside a card of another list first' }))
    type(await screen.findByRole('textbox', { name: 'Which card' }), '{{ input.theatre }}')
    fireEvent.click(screen.getByRole('checkbox', { name: 'The list loads more as you scroll' }))
    expect(screen.getByRole('combobox', { name: 'When several match' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toMatchObject({ within: { item: '{{ input.theatre }}' }, loadMore: { rounds: 5 } })
    vi.unstubAllGlobals()
  })

  it('keeps the item a list to choose from once the card box is ticked with no cards loaded', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element'], properties: {
            element: locator, item: { type: 'string' }, within: { type: 'object' } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
    const button = { strategies: [{ by: 'css', value: 'button.btn' }], label: 'Book Now' }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config,
      inputs: { element: button, item: 'Book Now' },
      list: { title: 'Book Now', kind: 'text', count: 2, inputName: 'bookNow', named: true, options: ['Book Now', 'Other dates'] } } } : n) }
    const { onSave } = open(graph, made.id)
    expect(await screen.findByRole('combobox', { name: 'Book Now' })).toHaveValue('Book Now')
    await unfold()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Choose inside a card of another list first' }))
    // Still a list of the names it showed, not a mapping box.
    const item = await screen.findByRole('combobox', { name: 'Book Now' })
    expect(item).toHaveValue('Book Now')
    type(await screen.findByRole('textbox', { name: 'Which card' }), '{{ input.trains }}')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toMatchObject({ item: 'Book Now', within: { item: '{{ input.trains }}' } })
    vi.unstubAllGlobals()
  })

  it('loads the cards and items of a list from the page to choose from', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    const sent: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      let body: unknown = []
      if (url.includes('/api/devices/catalog')) body = [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element'], properties: {
            element: locator, item: { type: 'string' }, itemCss: { type: 'string' }, within: { type: 'object' } } }, output: { type: 'object' } },
      ]
      if (url.includes('/api/web/browsers')) body = [{ id: 'b1', workspaceId: null, name: 'Chrome', online: true, family: null, version: null, allowedSites: [], sessions: 1, lastSeenAt: null }]
      if (url.includes('/api/web/test-step')) {
        const request = JSON.parse(String(init?.body)) as { inputs: Record<string, unknown> }
        sent.push(request.inputs)
        body = { status: 'succeeded', files: [], output: { count: 2, items: request.inputs.within
          ? [{ index: 0, label: '03:40 PM' }, { index: 1, label: '07:15 PM' }]
          : [{ index: 0, label: 'Rakki RGB Laser 4K' }, { index: 1, label: 'Meenakshi Cinemas' }] } }
      }
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
    const show = { strategies: [{ by: 'text', value: '10:45 PM' }], label: 'Showtime' }
    const venue = { strategies: [{ by: 'css', value: 'div.venue' }], label: 'Theatre' }
    const inputs = { element: show, item: '10:45 PM', itemCss: 'div.times > div', within: { element: venue, item: '', itemCss: 'div.venue' } }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, target: 'b1', inputs } } : n) }
    const { onSave } = open(graph, made.id)
    await unfold()
    expect(await screen.findByRole('textbox', { name: 'Item pattern (CSS)' })).toHaveValue('div.times > div')
    fireEvent.click(await screen.findByRole('button', { name: 'Load cards from the page' }))
    // The names loaded become the choices of the list's one field.
    fireEvent.change(await screen.findByRole('combobox', { name: 'Which card' }), { target: { value: 'Meenakshi Cinemas' } })
    fireEvent.click(await screen.findByRole('button', { name: 'Load items from the page' }))
    const item = await screen.findByRole('combobox', { name: 'Item to choose' })
    expect(Array.from((item as HTMLSelectElement).options).map(o => o.value)).toEqual(['', '10:45 PM', '03:40 PM', '07:15 PM', '__map'])
    fireEvent.change(item, { target: { value: '07:15 PM' } })
    expect(sent[0]).toMatchObject({ element: venue, itemCss: 'div.venue' })
    expect(sent[1]).toMatchObject({ element: show, itemCss: 'div.times > div', within: { item: 'Meenakshi Cinemas', itemCss: 'div.venue' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toMatchObject({ item: '07:15 PM', itemCss: 'div.times > div', within: { item: 'Meenakshi Cinemas', itemCss: 'div.venue' } })
    vi.unstubAllGlobals()
  })

  it('shows the names given while recording as field names, with the recorded names to choose from', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      let body: unknown = []
      if (url.includes('/api/devices/catalog')) body = [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element'], properties: {
            element: locator, item: { type: 'string' }, itemCss: { type: 'string' }, titleCss: { type: 'string' }, within: { type: 'object' } } }, output: { type: 'object' } },
      ]
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
    const show = { strategies: [{ by: 'text', value: '10:45 PM' }] }
    const venue = { strategies: [{ by: 'css', value: 'div.venue' }] }
    const inputs = { element: show, item: '10:45 PM', within: { element: venue, item: 'PVR Phoenix', itemCss: 'div.venue', titleCss: 'h3' } }
    const list = { named: true, title: 'Showtime', withinTitle: 'Theatre',
      groups: [{ label: 'PVR Phoenix', items: ['10:00 AM', '10:45 PM'] }, { label: 'INOX Nexus', items: ['11:30 AM', '07:30 PM'] }] }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs, list } } : n) }
    const { onSave } = open(graph, made.id)
    // One field per list: no separate recorded dropdown beside a text field.
    expect(screen.queryByRole('textbox', { name: 'Item to choose' })).toBeNull()
    expect(screen.queryByRole('textbox', { name: 'Which card' })).toBeNull()
    const theatre = await screen.findByRole('combobox', { name: 'Theatre' })
    expect(theatre).toHaveValue('PVR Phoenix')
    await unfold()
    expect(screen.getByRole('textbox', { name: 'Card name pattern (CSS)' })).toHaveValue('h3')
    expect(screen.getByText('Showtime', { selector: 'label, span, p' })).toBeInTheDocument()
    fireEvent.change(theatre, { target: { value: 'INOX Nexus' } })
    const showtime = await screen.findByRole('combobox', { name: 'Showtime' })
    // The showtimes follow the theatre: the old one (10:45 PM, at PVR) is gone.
    expect(showtime).toHaveValue('')
    expect(Array.from((showtime as HTMLSelectElement).options).map(o => o.value)).toEqual(['', '11:30 AM', '07:30 PM', '__map'])
    fireEvent.change(showtime, { target: { value: '07:30 PM' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toMatchObject({ item: '07:30 PM', within: { item: 'INOX Nexus', titleCss: 'h3' } })
    vi.unstubAllGlobals()
  })

  it("offers a dropdown's kept items as its choices, and Dynamic reads them on each run instead", async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.selectOption', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose in a dropdown', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element', 'item'], properties: {
            element: locator, item: { type: 'string' }, options: { type: 'array' }, mode: { enum: ['static', 'dynamic'] } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.selectOption')!)
    const inputs = { element: { strategies: [{ by: 'css', value: '#quota-box' }], label: 'Quota' }, item: 'GENERAL', mode: 'static', options: ['GENERAL', 'TATKAL'] }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs } } : n) }
    const { onSave } = open(graph, made.id)
    const quota = await screen.findByRole('combobox', { name: 'Quota' })
    expect(Array.from((quota as HTMLSelectElement).options).map(o => o.value)).toEqual(['', 'GENERAL', 'TATKAL', '__data'])
    expect(screen.getByRole('textbox', { name: 'Dropdown items' })).toHaveValue('GENERAL, TATKAL')
    fireEvent.click(screen.getByRole('radio', { name: 'Dynamic items' }))
    expect(screen.queryByRole('textbox', { name: 'Dropdown items' })).toBeNull()
    expect(await screen.findByRole('textbox', { name: 'Quota' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toMatchObject({ item: 'GENERAL', mode: 'dynamic' })
    expect((saved.config.inputs as Record<string, unknown>).options).toBeUndefined()
    vi.unstubAllGlobals()
  })

  it('gives a list one dropdown that can be mapped instead, and says which list it follows', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element', 'item'], properties: {
            element: locator, item: { type: 'string' }, itemCss: { type: 'string' } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!)
    const venue = { strategies: [{ by: 'css', value: 'div.venue' }] }
    const inputs = { element: venue, item: 'Rakki Cinemas', itemCss: 'div.venue' }
    const list = { named: true, title: 'Theatre', options: ['Rakki Cinemas', 'Vijay Park'],
      dependsOn: { title: 'Date', inputName: 'date', relation: 'after' } }
    const graph = { ...made.graph, nodes: made.graph.nodes.map(n => n.id === made.id ? { ...n, config: { ...n.config, inputs, list } } : n) }
    const { onSave } = open(graph, made.id)
    const theatre = await screen.findByRole('combobox', { name: 'Theatre' })
    expect(theatre).toHaveValue('Rakki Cinemas')
    expect(screen.getAllByRole('combobox', { name: /Theatre/ })).toHaveLength(1)
    expect(screen.getByTestId('list-follows')).toHaveTextContent('Follows Date (input date)')
    fireEvent.change(theatre, { target: { value: '__map' } })
    type(await screen.findByRole('textbox', { name: 'Theatre' }), '{{ input.theatre }}')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
    expect(saved.config.inputs).toMatchObject({ item: '{{ input.theatre }}' })
    vi.unstubAllGlobals()
  })

  it('lets a list load after another list of the workflow, such as Dates after Coaches', async () => {
    queryClient.clear()
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).includes('/api/devices/catalog') ? [
        { action: 'browser.clickItem', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Choose from a list', defaultTimeoutSec: 15,
          conditions: [], platforms: [], input: { type: 'object', required: ['element', 'item'], properties: {
            element: locator, item: { type: 'string' }, within: { type: 'object' } } }, output: { type: 'object' } },
      ] : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const palette = WEB_PALETTE.find(p => p.config?.action === 'browser.clickItem')!
    const coach = addAfter(emptyGraph(), 'start', null, palette)
    const date = addAfter(coach.graph, coach.id, null, palette)
    const card = { strategies: [{ by: 'css', value: 'div.train' }] }
    const configs: Record<string, Record<string, unknown>> = {
      [coach.id]: { inputs: { element: card, item: '{{ input.coaches }}', within: { element: card, item: '{{ input.trains }}' } },
        list: { named: true, title: 'Coaches', withinTitle: 'Trains' } },
      [date.id]: { inputs: { element: card, item: '{{ input.dates }}', within: { element: card, item: '{{ input.trains }}' } },
        list: { named: true, title: 'Dates', withinTitle: 'Trains', dependsOn: { title: 'Trains', inputName: 'trains', relation: 'inside' } } },
    }
    const graph = { ...date.graph, nodes: date.graph.nodes.map(n => configs[n.id] ? { ...n, config: { ...n.config, ...configs[n.id] } } : n) }
    const { onSave } = open(graph, date.id)
    const after = await screen.findByTestId('list-loads-after')
    expect(after).toHaveValue('')
    expect(Array.from((after as HTMLSelectElement).options).map(o => o.value)).toEqual(['', 'Trains', 'Coaches'])
    fireEvent.change(after, { target: { value: 'Coaches' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === date.id)!
    expect(saved.config.list).toMatchObject({ title: 'Dates', withinTitle: 'Trains',
      dependsOn: { title: 'Coaches', inputName: 'coaches', relation: 'after' } })
    vi.unstubAllGlobals()
  })

  describe('Advanced web options', () => {
    const respond = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    const locator = { $ref: 'device-actions.json#/schemas/locatorBundle' }
    const specs = [
      {
        action: 'browser.dragDrop', version: 1, capability: 'browser', actionType: 'UPDATE', title: 'Drag and drop',
        description: '', defaultTimeoutSec: 60, conditions: [], platforms: ['browser'],
        input: { type: 'object', required: ['element', 'to'], properties: {
          element: locator, to: locator, trusted: { type: 'boolean' }, timeoutMs: { type: 'integer' },
          screenshotOnError: { type: 'boolean' }, highlight: { type: 'boolean' },
        } },
        output: { type: 'object' },
      },
      {
        action: 'browser.wait', version: 1, capability: 'browser', actionType: 'READ', title: 'Wait',
        description: '', defaultTimeoutSec: 60, conditions: [], platforms: ['browser'],
        input: { type: 'object', required: ['for'], properties: {
          element: locator, for: { enum: ['element', 'time', 'text', 'url'] }, ms: { type: 'integer' },
          text: { type: 'string' }, urlContains: { type: 'string' }, timeoutMs: { type: 'integer' },
        } },
        output: { type: 'object' },
      },
    ]

    function stub() {
      vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
        respond(String(input).includes('/api/devices/catalog') ? specs : [])))
    }

    it('shows Drop onto as an element and saves highlight, retries and what to do on failure', async () => {
      queryClient.clear()
      stub()
      const entry = WEB_PALETTE.find(e => e.config?.action === 'browser.dragDrop')!
      const made = addAfter(emptyGraph(), 'start', null, entry)
      const graph = {
        ...made.graph,
        nodes: made.graph.nodes.map(n => n.id === made.id
          ? { ...n, config: { ...n.config, inputs: { element: { strategies: [{ by: 'css', value: '#card' }] }, to: { strategies: [{ by: 'css', value: '#zone' }] } } } } : n),
      }
      const { onSave } = open(graph, made.id)
      expect(await screen.findByText('Drop onto')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /^Advanced options/ }))
      fireEvent.click(await screen.findByRole('checkbox', { name: /Highlight the element/ }))
      fireEvent.change(screen.getByRole('spinbutton', { name: 'Tries before giving up' }), { target: { value: '5' } })
      fireEvent.change(screen.getByRole('spinbutton', { name: 'Seconds between tries' }), { target: { value: '2' } })
      fireEvent.change(screen.getByRole('combobox', { name: "If I don't answer in time" }), { target: { value: 'skip' } })
      fireEvent.change(screen.getByRole('combobox', { name: 'When it still fails' }), { target: { value: 'dead_letter' } })
      // Stopping straight away asks nobody, so there is no question to time out.
      expect(screen.queryByRole('combobox', { name: "If I don't answer in time" })).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      await waitFor(() => expect(onSave).toHaveBeenCalled())
      const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
      expect(saved.config.inputs).toMatchObject({ highlight: true, to: { strategies: [{ by: 'css', value: '#zone' }] } })
      expect(saved.config.retry).toEqual({ maxAttempts: 5, backoffMillis: 2000 })
      expect(saved.config.onFailure).toBe('dead_letter')
      expect(saved.config.ifNoAnswer).toBe('skip')
      vi.unstubAllGlobals()
    })

    it('shows only the Wait inputs that matter for what it waits for', async () => {
      queryClient.clear()
      stub()
      const entry = WEB_PALETTE.find(e => e.config?.action === 'browser.wait')!
      const made = addAfter(emptyGraph(), 'start', null, entry)
      open(made.graph, made.id)
      const mode = await screen.findByRole('combobox', { name: 'For' })
      expect(screen.queryByText('Address contains')).not.toBeInTheDocument()
      fireEvent.change(mode, { target: { value: 'url' } })
      expect(await screen.findByText('Address contains')).toBeInTheDocument()
      expect(screen.queryByText('Ms')).not.toBeInTheDocument()
      fireEvent.change(mode, { target: { value: 'text' } })
      expect(await screen.findByText('Text')).toBeInTheDocument()
      expect(screen.queryByText('Address contains')).not.toBeInTheDocument()
      vi.unstubAllGlobals()
    })

    it('edits the frames and shadow roots of an element', async () => {
      queryClient.clear()
      stub()
      const entry = WEB_PALETTE.find(e => e.config?.action === 'browser.dragDrop')!
      const made = addAfter(emptyGraph(), 'start', null, entry)
      const graph = {
        ...made.graph,
        nodes: made.graph.nodes.map(n => n.id === made.id
          ? { ...n, config: { ...n.config, inputs: { to: { strategies: [{ by: 'css', value: '#zone' }] }, element: { strategies: [{ by: 'css', value: '#card' }], fingerprint: { tag: 'div' } } } } } : n),
      }
      const { onSave } = open(graph, made.id)
      fireEvent.click((await screen.findAllByRole('button', { name: 'Edit selectors' }))[0])
      fireEvent.click(screen.getByRole('button', { name: /Add frame/ }))
      type(screen.getByRole('textbox', { name: 'Frame 1' }), 'iframe#pay')
      fireEvent.click(screen.getByRole('button', { name: /Add shadow host/ }))
      type(screen.getByRole('textbox', { name: 'Shadow host 1' }), 'checkout-form')
      fireEvent.click(screen.getByRole('button', { name: 'Save selectors' }))
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      await waitFor(() => expect(onSave).toHaveBeenCalled())
      const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
      expect((saved.config.inputs as Record<string, unknown>).element).toEqual({
        strategies: [{ by: 'css', value: '#card' }],
        fingerprint: { tag: 'div' },
        frames: [{ strategies: [{ by: 'css', value: 'iframe#pay' }] }],
        shadow: [{ by: 'css', value: 'checkout-form' }],
      })
      vi.unstubAllGlobals()
    })
  })

  describe('Pick on page', () => {
    const clickSpec = {
      action: 'browser.click', version: 2, capability: 'browser', actionType: 'UPDATE', title: 'Click',
      description: '', defaultTimeoutSec: 15, conditions: [], platforms: [],
      input: { type: 'object', required: ['element'], properties: { element: { type: 'object' } } },
      output: { type: 'object' },
    }
    const browser = { id: 'dev-1', workspaceId: null, name: 'Chrome', online: true, family: 'chrome', version: '131', allowedSites: [], sessions: 1, lastSeenAt: null }
    const respond = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

    function stub(pickStatuses: unknown[]) {
      const calls: string[] = []
      const polls = [...pickStatuses]
      vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        calls.push(`${init?.method ?? 'GET'} ${url}`)
        if (url.includes('/api/devices/catalog')) return respond([clickSpec])
        if (url.includes('/api/web/browsers')) return respond([browser])
        if (url.endsWith('/api/web/pick')) return respond({ commandId: 'cmd-1' })
        if (url.includes('/api/web/pick/')) return respond(polls.length > 1 ? polls.shift() : polls[0])
        return respond({})
      }))
      return calls
    }

    it('shows the waiting state and cancels through the command route', async () => {
      queryClient.clear()
      const calls = stub([{ status: 'waiting' }])
      const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE[1])
      open(made.graph, made.id)
      const pick = await screen.findByRole('button', { name: 'Pick on page' })
      await waitFor(() => expect(pick).toBeEnabled())
      fireEvent.click(pick)
      expect(await screen.findByText(/Click the element in Chrome… Press Esc in Chrome to cancel/)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      await waitFor(() => expect(calls).toContain('POST /api/devices/dev-1/commands/cmd-1/cancel'))
      expect(screen.getByRole('button', { name: 'Pick on page' })).toBeInTheDocument()
      vi.unstubAllGlobals()
    })

    it('fills the element from the pick and keeps its preview', async () => {
      queryClient.clear()
      stub([{ status: 'waiting' }, { status: 'done', element: { strategies: [{ by: 'css', value: '#go' }], label: 'Go' }, preview: 'file-1', pagePreview: 'file-2', url: 'https://example.com' }])
      const made = addAfter(emptyGraph(), 'start', null, WEB_PALETTE[1])
      const { onSave } = open(made.graph, made.id)
      const pick = await screen.findByRole('button', { name: 'Pick on page' })
      await waitFor(() => expect(pick).toBeEnabled())
      fireEvent.click(pick)
      await waitFor(() => expect(screen.getByText('Go')).toBeInTheDocument(), { timeout: 5000 })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === made.id)!
      expect(saved.config.inputs).toMatchObject({ element: { strategies: [{ by: 'css', value: '#go' }], preview: 'file-1', pagePreview: 'file-2' } })
      vi.unstubAllGlobals()
    }, 10000)
  })

  it('branches by the button a group clicked: a path per button, more added from the buttons left', () => {
    const graph: CanvasGraph = {
      nodes: [
        { id: 'start', type: 'TRIGGER', label: 'Start', config: {}, x: 0, y: 0 },
        { id: 'lang', type: 'DEVICE_ACTION', label: 'Choose Language', x: 0, y: 130, config: {
          preset: 'web', action: 'browser.clickOneOf', version: 1,
          inputs: { options: [{ name: 'हिंदी', element: {} }, { name: 'English', element: {} }] },
        } },
        { id: 'paths', type: 'SWITCH', label: 'Which Language was clicked', x: 250, y: 130, config: {
          byButton: 'lang', cases: [{ id: 'case1', label: 'हिंदी', condition: 'nodes.lang.clicked == "हिंदी"' }],
        } },
        { id: 'next', type: 'NOTIFY', label: 'Tell me', config: { title: 'Done' }, x: 0, y: 260 },
      ],
      edges: [
        { from: 'start', to: 'lang', branch: null }, { from: 'lang', to: 'paths', branch: null },
        { from: 'paths', to: 'next', branch: 'case1' }, { from: 'paths', to: 'next', branch: 'else' },
      ],
    }
    const { onSave } = open(graph, 'paths')
    expect(screen.getByText(/Each path runs when its button was the one "Choose Language" clicked/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Add a path/ })).not.toBeInTheDocument()
    const add = screen.getByRole('combobox', { name: 'Add a path for button' })
    expect([...add.querySelectorAll('option')].map(o => o.textContent)).toEqual(['Choose a button…', 'English'])
    fireEvent.change(add, { target: { value: 'English' } })
    expect(screen.queryByRole('combobox', { name: 'Add a path for button' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = onSave.mock.calls[0][0] as CanvasGraph
    expect(saved.nodes.find(n => n.id === 'paths')!.config.cases).toEqual([
      { id: 'case1', label: 'हिंदी', condition: 'nodes.lang.clicked == "हिंदी"' },
      { id: 'case2', label: 'English', condition: 'nodes.lang.clicked == "English"' },
    ])
    expect(saved.edges).toContainEqual({ from: 'paths', to: 'next', branch: 'case2' })
  })
})
