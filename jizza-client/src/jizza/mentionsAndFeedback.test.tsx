import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
    feedback: vi.fn(async () => undefined),
    update: vi.fn(async () => ({})),
  },
}))

vi.mock('@/api/chat', () => ({
  chatApi: {
    send: vi.fn(async (body: { conversationId?: string }) => ({ jobId: 'job-2', conversationId: body.conversationId ?? 'c1' })),
    cancel: vi.fn(),
    job: vi.fn(),
  },
}))

const AGENTS = [
  { id: 'a-kai', key: 'kai', name: 'Kai', enabled: true, orchestrator: false, status: 'active', domain: 'Finance' },
  { id: 'a-kai-tax', key: 'kai_tax', name: 'Kai Tax', enabled: true, orchestrator: false, status: 'active' },
  { id: 'a-off', key: 'mo', name: 'Mo', enabled: false, orchestrator: false, status: 'active' },
  { id: 'a-jizza', key: 'jizza', name: 'Jizza', enabled: true, orchestrator: true, status: 'active' },
]

vi.mock('@/api/agents', () => ({
  agentsApi: { list: vi.fn(async () => AGENTS) },
}))

import { conversationsApi } from '@/api/conversations'
import { chatApi } from '@/api/chat'
import { useAssistantStore, type AssistantMessage } from './assistantStore'
import { Conversation } from './Conversation'
import { matchAgents, mentionOf, mentionQuery } from './mentions'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false, incognito: false, threadsNext: false })
  vi.clearAllMocks()
})

function chatWithOneReply(extra: Partial<AssistantMessage> = {}) {
  useAssistantStore.setState({
    conversationId: 'c1',
    messages: [
      { id: 1, role: 'user', text: 'What is new?', serverId: 'm1', jobId: 'job-1' },
      { id: 2, role: 'assistant', text: 'Here is the news.', serverId: 'm2', jobId: 'job-1', ...extra },
    ],
    jobs: { 'job-1': { id: 'job-1', status: 'completed', startedAt: '2026-11-21T10:00:00.000Z', events: [], loaded: false } },
    jobOrder: ['job-1'],
  })
}

const box = () => screen.getByPlaceholderText(/Ask Jizza|Ask about this/)

describe('mentions', () => {
  it('reads what is typed after a leading @', () => {
    expect(mentionQuery('@ka')).toBe('ka')
    expect(mentionQuery('@')).toBe('')
    expect(mentionQuery('@Kai what')).toBeNull()
    expect(mentionQuery('hello @ka')).toBeNull()
  })

  it('offers switched-on specialists only, never Jizza itself', () => {
    expect(matchAgents(AGENTS, '').map(a => a.name)).toEqual(['Kai', 'Kai Tax'])
    expect(matchAgents(AGENTS, 'tax').map(a => a.name)).toEqual(['Kai Tax'])
  })

  it('finds who a message is for, the longest name first', () => {
    expect(mentionOf('@Kai Tax how much do I owe?', AGENTS)?.id).toBe('a-kai-tax')
    expect(mentionOf('@kai, what about stocks?', AGENTS)?.id).toBe('a-kai')
    expect(mentionOf('@Kaitlyn hi', AGENTS)).toBeUndefined()
    expect(mentionOf('@Mo hi', AGENTS)).toBeUndefined()
    expect(mentionOf('@Jizza hi', AGENTS)).toBeUndefined()
  })

  it('picks a specialist from the @ menu and sends the message to them', async () => {
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.change(box(), { target: { value: '@ka' } })
    fireEvent.click(await screen.findByRole('button', { name: /Kai Finance/ }))
    expect(box()).toHaveValue('@Kai ')

    fireEvent.change(box(), { target: { value: '@Kai how are my stocks?' } })
    fireEvent.keyDown(box(), { key: 'Enter' })

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    const body = vi.mocked(chatApi.send).mock.calls[0][0]
    expect(body.message).toBe('@Kai how are my stocks?')
    expect(body.context).toMatchObject({ mention: 'a-kai' })
  })
})

describe('slash commands', () => {
  it('/model sends the next message to the deeper model, then switches back', async () => {
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.change(box(), { target: { value: '/model' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    expect(screen.getByText('Deeper model for the next message')).toBeInTheDocument()

    fireEvent.change(box(), { target: { value: 'Think hard about this' } })
    fireEvent.keyDown(box(), { key: 'Enter' })

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(vi.mocked(chatApi.send).mock.calls[0][0].context).toMatchObject({ depth: 'deep' })
    expect(screen.queryByText('Deeper model for the next message')).not.toBeInTheDocument()
  })

  it('/remember starts the sentence for a memory', () => {
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.change(box(), { target: { value: '/remember' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    expect(box()).toHaveValue('Remember that ')
  })

  it('/thread switches threads on for the chat and starts the request', async () => {
    chatWithOneReply()
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.change(box(), { target: { value: '/thread' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    await waitFor(() => expect(conversationsApi.update).toHaveBeenCalledWith('c1', { threads: true }))
    await waitFor(() => expect(box()).toHaveValue('Start a new thread about '))
  })

  it('/thread before the first message turns threads on for the new chat', async () => {
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.change(box(), { target: { value: '/thread' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    await waitFor(() => expect(box()).toHaveValue('Start a new thread about '))
    expect(useAssistantStore.getState().threadsNext).toBe(true)
    expect(conversationsApi.update).not.toHaveBeenCalled()
  })
})

describe('web sources', () => {
  it('lists the pages a reply drew on as links under it', () => {
    chatWithOneReply({ sources: [{ url: 'https://example.com/news', title: 'Example news' }] })
    wrap(<Conversation onOpenJob={() => {}} />)
    const link = screen.getByRole('link', { name: /Example news/ })
    expect(link).toHaveAttribute('href', 'https://example.com/news')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('rel')).toContain('noopener')
  })
})

describe('reply feedback', () => {
  it('saves a thumbs down at once, then the reason if one is given', async () => {
    chatWithOneReply()
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Bad reply' }))
    await waitFor(() => expect(conversationsApi.feedback).toHaveBeenCalledWith('c1', 'm2', 'down', undefined))

    fireEvent.change(screen.getByLabelText('What was wrong with this reply'), { target: { value: 'Too long' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send feedback' }))
    await waitFor(() => expect(conversationsApi.feedback).toHaveBeenCalledWith('c1', 'm2', 'down', 'Too long'))
    expect(useAssistantStore.getState().messages[1].feedback).toEqual({ rating: 'down', reason: 'Too long' })
  })

  it('takes a thumb back when it is tapped again', async () => {
    chatWithOneReply({ feedback: { rating: 'up' } })
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Remove "good reply"' }))
    await waitFor(() => expect(conversationsApi.feedback).toHaveBeenCalledWith('c1', 'm2', 'none', undefined))
    expect(useAssistantStore.getState().messages[1].feedback).toBeUndefined()
  })

  it('puts the thumb back as it was when saving fails', async () => {
    vi.mocked(conversationsApi.feedback).mockRejectedValueOnce(new Error('offline'))
    chatWithOneReply()
    wrap(<Conversation onOpenJob={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Good reply' }))
    expect(await screen.findByText('That did not save. Try again.')).toBeInTheDocument()
    expect(useAssistantStore.getState().messages[1].feedback).toBeUndefined()
  })

  it('is not offered in incognito, where nothing is kept', () => {
    chatWithOneReply()
    useAssistantStore.setState({ incognito: true })
    wrap(<Conversation onOpenJob={() => {}} />)
    expect(screen.queryByRole('button', { name: 'Bad reply' })).not.toBeInTheDocument()
  })
})
