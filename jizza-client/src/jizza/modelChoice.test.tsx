import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('@/api/conversations', () => ({
  conversationsApi: {
    list: vi.fn(),
    messages: vi.fn(async () => []),
    jobs: vi.fn(async () => []),
    threads: vi.fn(async () => []),
    learned: vi.fn(async () => []),
    rewind: vi.fn(async () => undefined),
    earlier: vi.fn(async () => null),
    choice: vi.fn(async () => ({ model: 'openai:gpt-5.1', depth: 'deep' })),
    setChoice: vi.fn(async (_id: string, c: unknown) => c),
    keep: vi.fn(async () => undefined),
  },
}))

vi.mock('@/api/chat', () => ({
  chatApi: {
    send: vi.fn(async (body: { conversationId?: string }) => ({ jobId: 'job-2', conversationId: body.conversationId ?? 'c1' })),
    cancel: vi.fn(),
    job: vi.fn(),
  },
}))

vi.mock('@/api/aiModels', async (original) => ({
  ...(await original<typeof import('@/api/aiModels')>()),
  aiModelsApi: {
    models: vi.fn(async () => [
      { ref: 'claude-sonnet-5-5', provider: 'anthropic', label: 'Claude Sonnet 5.5', note: 'Good for most things', ownKey: false, tools: true, vision: true, pdf: true, inputPerMillion: 3, outputPerMillion: 15 },
      { ref: 'openai:gpt-5.1', provider: 'openai', label: 'GPT-5.1', note: 'Your OpenAI key', ownKey: true, tools: true, vision: true, pdf: true },
      { ref: 'ollama:llama3', provider: 'ollama', label: 'Llama 3', note: 'Your Ollama key', ownKey: true, tools: false, vision: false, pdf: false, inputPerMillion: 0, outputPerMillion: 0 },
    ]),
  },
}))

import { conversationsApi } from '@/api/conversations'
import { chatApi } from '@/api/chat'
import { canRead, groupModels, priceHint, formatCost } from '@/api/aiModels'
import { keptAnswer, loadHistory, turnChoice, useAssistantStore, type AssistantMessage } from './assistantStore'
import { Conversation } from './Conversation'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false, choice: {} })
  vi.clearAllMocks()
})

function chatWithOneReply(extra: Partial<AssistantMessage> = {}) {
  useAssistantStore.setState({
    conversationId: 'c1',
    messages: [
      { id: 1, role: 'user', text: 'Plan my week', serverId: 'm1', jobId: 'job-1' },
      { id: 2, role: 'assistant', text: 'Here is a plan.', serverId: 'm2', jobId: 'job-1', ...extra },
    ],
  })
}

describe('the model picker', () => {
  it('lists Auto and each model by provider, and the pick goes with the chat and every message', async () => {
    useAssistantStore.setState({ conversationId: 'c1' })
    wrap(<Conversation />)

    fireEvent.click(screen.getByRole('button', { name: 'Model: Auto' }))
    const menu = await screen.findByRole('listbox', { name: 'Models' })
    expect(await within(menu).findByRole('group', { name: 'Jizza' })).toBeInTheDocument()
    expect(within(menu).getByRole('group', { name: 'OpenAI (your key)' })).toBeInTheDocument()
    expect(within(menu).getByText(/\$3 in \/ \$15 out per 1M tokens/)).toBeInTheDocument()
    expect(within(menu).getByText(/talk only, no tools/)).toBeInTheDocument()
    fireEvent.click(within(menu).getByText('GPT-5.1'))

    expect(conversationsApi.setChoice).toHaveBeenCalledWith('c1', { model: 'openai:gpt-5.1', depth: null })
    expect(screen.getByRole('button', { name: 'Model: GPT-5.1' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: 'Deep' }))
    expect(conversationsApi.setChoice).toHaveBeenLastCalledWith('c1', { model: 'openai:gpt-5.1', depth: 'deep' })

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Plan my week' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(vi.mocked(chatApi.send).mock.calls[0][0].context).toMatchObject({ model: 'openai:gpt-5.1', depth: 'deep' })
  })

  it('takes the chat’s saved choice when it is opened', async () => {
    useAssistantStore.setState({ conversationId: 'c9', messages: [] })
    await loadHistory()
    expect(useAssistantStore.getState().choice).toEqual({ model: 'openai:gpt-5.1', depth: 'deep' })
  })

  it('a new chat takes the picker’s choice with it', async () => {
    useAssistantStore.setState({ choice: { model: 'openai:gpt-5.1' } })
    await useAssistantStore.getState().send('hello')
    expect(conversationsApi.setChoice).toHaveBeenCalledWith('c1', { model: 'openai:gpt-5.1', depth: null })
  })

  it('a one-off model wins over the chat’s, and Auto and Normal are said out loud', () => {
    expect(turnChoice({}, undefined)).toEqual({ model: 'auto', depth: 'normal' })
    expect(turnChoice({ model: 'openai:gpt-5.1', depth: 'quick' }, { model: 'ollama:llama3' }))
      .toEqual({ model: 'ollama:llama3', depth: 'quick' })
  })
})

describe('Ask another model', () => {
  it('asks the same message of the picked model and keeps the answer with who wrote it', async () => {
    chatWithOneReply({ model: { ref: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5' } })
    wrap(<Conversation />)

    expect(screen.getByText('Claude Sonnet 5.5')).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: 'Ask another model' }))
    const menu = await screen.findByRole('listbox', { name: 'Ask another model' })
    expect(within(menu).queryByText('Claude Sonnet 5.5')).not.toBeInTheDocument()
    fireEvent.click(within(menu).getByText('GPT-5.1'))

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(vi.mocked(chatApi.send).mock.calls[0][0].context).toMatchObject({
      model: 'openai:gpt-5.1',
      versions: [{ text: 'Here is a plan.', model: { ref: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5' } }],
    })
  })
})

describe('comparing answers', () => {
  it('shows both side by side and keeping the earlier one makes the chat go on from it', async () => {
    chatWithOneReply({
      model: { ref: 'openai:gpt-5.1', label: 'GPT-5.1', ownKey: true },
      versions: [{ text: 'An older plan.', model: { ref: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5' } }],
    })
    wrap(<Conversation />)

    fireEvent.click(screen.getByRole('button', { name: 'Compare' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('An older plan.')).toBeInTheDocument()
    expect(within(dialog).getByText(/Now · GPT-5.1/)).toBeInTheDocument()
    const earlier = within(dialog).getByRole('region', { name: 'Earlier answer' })
    fireEvent.click(within(earlier).getByRole('button', { name: 'Keep this one' }))

    await waitFor(() => expect(conversationsApi.keep).toHaveBeenCalledWith('c1', 'm2', 0))
    const reply = useAssistantStore.getState().messages[1]
    expect(reply.text).toBe('An older plan.')
    expect(reply.model?.label).toBe('Claude Sonnet 5.5')
    expect(reply.versions).toEqual([{ text: 'Here is a plan.', model: { ref: 'openai:gpt-5.1', label: 'GPT-5.1', ownKey: true } }])
  })

  it('swaps the shown answer with the kept one, as the server does', () => {
    const m: AssistantMessage = { id: 1, role: 'assistant', text: 'C', deep: true, sources: [{ url: 'u', title: 't' }],
      versions: [{ text: 'A' }, { text: 'B', model: { ref: 'x:y', label: 'Y' } }] }
    const kept = keptAnswer(m, 1)
    expect(kept.text).toBe('B')
    expect(kept.deep).toBeUndefined()
    expect(kept.sources).toBeUndefined()
    expect(kept.model).toEqual({ ref: 'x:y', label: 'Y' })
    expect(kept.versions).toEqual([{ text: 'A' }, { text: 'C', deep: true }])
  })
})

describe('the team strip', () => {
  it('shows who worked on a reply, with their model and cost', () => {
    chatWithOneReply({ team: [
      { name: 'Kai', key: 'finance', task: 'Check the budget', model: 'GPT-5.1', ownKey: true, cost: 0.004 },
      { name: 'Vita', key: 'health', model: 'Claude Sonnet 5.5', cost: 0.03 },
    ] })
    wrap(<Conversation />)

    fireEvent.click(screen.getByRole('button', { name: /2 specialists worked on this/ }))
    const team = screen.getByRole('list', { name: 'Team' })
    expect(within(team).getByText('Check the budget')).toBeInTheDocument()
    expect(within(team).getByText(/GPT-5.1 · your key · <\$0.01/)).toBeInTheDocument()
    expect(within(team).getByText(/Claude Sonnet 5.5 · \$0.03/)).toBeInTheDocument()
  })

  it('comes in with the finished reply, with the model that answered', () => {
    useAssistantStore.setState({ conversationId: 'c1', messages: [{ id: 5, role: 'assistant', text: '', streaming: true, jobId: 'j5' }],
      jobs: { j5: { id: 'j5', status: 'running', startedAt: '2026-11-24T10:00:00Z', events: [], loaded: true } } })
    useAssistantStore.getState().applyEvent({ type: 'job.completed', jobId: 'j5', conversationId: 'c1', seq: 9, at: '2026-11-24T10:00:05Z',
      payload: { text: 'Done.', model: { ref: 'openai:gpt-5.1', label: 'GPT-5.1', ownKey: true }, team: [{ name: 'Kai', model: 'GPT-5.1', cost: 0.01 }] } } as never)
    const reply = useAssistantStore.getState().messages[0]
    expect(reply.model).toEqual({ ref: 'openai:gpt-5.1', label: 'GPT-5.1', ownKey: true })
    expect(reply.team).toHaveLength(1)
  })
})

describe('model helpers', () => {
  const m = { ref: 'a:b', provider: 'openai', label: 'B', ownKey: true, tools: true, vision: false, pdf: true }
  it('says which models can read what is attached', () => {
    expect(canRead(m, { image: true, pdf: false })).toBe(false)
    expect(canRead(m, { image: false, pdf: true })).toBe(true)
  })
  it('prices and groups', () => {
    expect(priceHint({ inputPerMillion: 0.15, outputPerMillion: 0.6 })).toBe('$0.15 in / $0.60 out per 1M tokens')
    expect(priceHint({})).toBe('')
    expect(priceHint({ inputPerMillion: 0, outputPerMillion: 0 })).toBe('Free')
    expect(formatCost(0.004)).toBe('<$0.01')
    expect(groupModels([m, { ...m, ref: 'p', ownKey: false, provider: 'anthropic' }]).map(g => g.group))
      .toEqual(['OpenAI (your key)', 'Jizza'])
  })
})
