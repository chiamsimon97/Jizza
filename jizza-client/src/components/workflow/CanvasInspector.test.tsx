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
    fireEvent.click(screen.getByRole('button', { name: 'Show options' }))
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
      fireEvent.click(screen.getByRole('button', { name: 'Show options' }))
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
})
