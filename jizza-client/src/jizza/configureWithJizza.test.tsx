import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { CanvasInspector } from '@/components/workflow/CanvasInspector'
import { addAfter, emptyGraph, updateNode, type CanvasGraph } from '@/components/workflow/canvasModel'
import { useAssistantStore } from './assistantStore'
import { applyStepConfig, useContextStore } from './contextStore'
import { matchCommands } from './commands'
import { redact } from './builderContext'
import { Markdown } from './Markdown'

afterEach(() => {
  useContextStore.setState({ stack: [] })
  useAssistantStore.setState({ messages: [], dockOpen: false })
})

function open(graph: CanvasGraph, id: string, onSave = vi.fn()) {
  render(
    <MemoryRouter>
      <CanvasInspector node={graph.nodes.find(n => n.id === id)!} graph={graph} issues={[]} readOnly={false}
        onDirty={() => {}} onSave={onSave} onClose={() => {}} />
    </MemoryRouter>,
  )
  return onSave
}

describe('Configure using Jizza', () => {
  it('puts Jizza on the step, offers ways to start, and fills in the settings for the user to save', () => {
    const added = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const graph = updateNode(added.graph, added.id, { config: { method: 'POST', bodyType: 'json', auth: { type: 'bearer', token: 'secret-1' } } })
    const onSave = open(graph, added.id)

    fireEvent.click(screen.getByRole('button', { name: 'Configure using Jizza' }))

    const top = useContextStore.getState().stack.at(-1)!.ctx
    expect(top).toMatchObject({ kind: 'step', label: 'Send webhook step', stepType: 'HTTP_REQUEST', screen: 'workflow_step' })
    // Jizza is told a token is set, never what it is.
    expect(JSON.stringify(top.data)).not.toContain('secret-1')
    const intro = useAssistantStore.getState().messages[0]
    expect(useAssistantStore.getState().dockOpen).toBe(true)
    expect(intro.question?.options).toContain('From API documentation')
    expect(intro.question?.guided?.['From API documentation']).toMatch(/paste/i)

    act(() => {
      expect(applyStepConfig(added.id, {
        method: 'GET', url: 'https://api.example.com/rates', queryParams: { currency: 'INR' },
        auth: { type: 'bearer', token: '(set, hidden)' }, bodyType: null,
      }, 'Get gold rate')).toBe(true)
    })

    expect(screen.getByRole('textbox', { name: 'URL' })).toHaveTextContent('https://api.example.com/rates')
    expect(screen.getByRole('textbox', { name: 'name 1' })).toHaveValue('currency')
    expect(screen.getByText(/Jizza filled in these settings/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const saved = (onSave.mock.calls[0][0] as CanvasGraph).nodes.find(n => n.id === added.id)!
    expect(saved.label).toBe('Get gold rate')
    expect(saved.config).toMatchObject({ method: 'GET', url: 'https://api.example.com/rates', queryParams: { currency: 'INR' }, auth: { type: 'bearer', token: 'secret-1' } })
    expect(saved.config).not.toHaveProperty('bodyType')
  })

  it('choosing a guided start answers on the spot and keeps the choice for Jizza', () => {
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    open(graph, id)
    fireEvent.click(screen.getByRole('button', { name: 'Configure using Jizza' }))
    act(() => { useAssistantStore.getState().guide('From a cURL command', 'Paste the cURL command.') })
    const messages = useAssistantStore.getState().messages
    expect(messages.map(m => m.role)).toEqual(['assistant', 'user', 'assistant'])
    expect(messages[0].question).toBeUndefined()
    expect(useContextStore.getState().stack.at(-1)!.ctx.goal).toBe('From a cURL command')
  })

  it('cannot fill in a step whose settings are closed', () => {
    expect(applyStepConfig('nowhere', { url: 'https://x' })).toBe(false)
  })
})

describe('slash commands', () => {
  const names = (ctx: Parameters<typeof matchCommands>[0], typed = '') => matchCommands(ctx, typed).map(c => c.name)

  it('fit where Jizza is', () => {
    expect(names({ kind: 'step', label: 'Send webhook step', screen: 'workflow_step', stepType: 'HTTP_REQUEST' })).toEqual(
      expect.arrayContaining(['api-docs', 'curl', 'auth', 'configure', 'leave', 'help']))
    expect(names({ kind: 'step', label: 'Branch step', screen: 'workflow_step', stepType: 'BRANCH' })).not.toContain('api-docs')
    expect(names({ kind: 'builder', label: 'Builder', screen: 'workflow_builder' })).toEqual(expect.arrayContaining(['explain', 'check', 'next']))
    expect(names({ kind: 'page', label: 'Workflows page', screen: 'workflows' })).toContain('create')
  })

  it('narrow to what was typed', () => {
    expect(names({ kind: 'step', label: 'Send webhook step', screen: 'workflow_step', stepType: 'HTTP_REQUEST' }, 'cur')).toEqual(['curl'])
  })
})

describe('redact', () => {
  it('hides credentials but says they are set', () => {
    expect(redact({ url: 'u', auth: { type: 'apiKey', name: 'X-Key', value: 'k' }, ui: { x: 1 } }))
      .toEqual({ url: 'u', auth: { type: 'apiKey', name: 'X-Key', value: '(set, hidden)' } })
  })
})

describe('Markdown', () => {
  it('formats replies without running HTML', () => {
    const { container } = render(<Markdown text={'**Done.** Set `url`:\n\n- one\n- two\n\n```json\n{"a": 1}\n```\n<img src=x onerror=alert(1)>'} />)
    expect(container.querySelector('strong')).toHaveTextContent('Done.')
    expect(container.querySelector('code')).toHaveTextContent('url')
    expect(container.querySelectorAll('li')).toHaveLength(2)
    expect(container.querySelector('pre')).toHaveTextContent('{"a": 1}')
    expect(container.querySelector('img')).toBeNull()
  })
})
