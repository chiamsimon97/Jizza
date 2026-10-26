import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import type { ConversationSummary, ThreadGuide } from '@/types'

vi.mock('@/api/conversations', () => ({
  conversationsApi: {
    list: vi.fn(),
    messages: vi.fn(async () => []),
    jobs: vi.fn(async () => []),
    guide: vi.fn(),
    updateGuide: vi.fn(),
    downloadGuide: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    create: vi.fn(async () => ({ id: 'threaded-1', threadsEnabled: true })),
    threads: vi.fn(async () => []),
    learned: vi.fn(async () => []),
    rewind: vi.fn(async () => undefined),
  },
}))

vi.mock('@/api/chat', () => ({
  chatApi: {
    send: vi.fn(async (body: { conversationId?: string }) => ({ jobId: 'job-9', conversationId: body.conversationId ?? 'plain-1' })),
    cancel: vi.fn(),
    job: vi.fn(),
  },
}))

import { conversationsApi } from '@/api/conversations'
import { chatApi } from '@/api/chat'
import { useAssistantStore } from './assistantStore'
import { Conversation } from './Conversation'
import { ConversationRail } from './workspace/ConversationRail'
import { ThreadGuidePanel } from './workspace/ThreadGuidePanel'
import { ThreadsPanel } from './workspace/ThreadsPanel'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

// jsdom has no layout, so nothing scrolls.
Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false, threadsNext: false })
  vi.clearAllMocks()
})

const at = new Date().toISOString()

describe('Threads', () => {
  it('groups threads by stage, and resolves one', async () => {
    const row = (id: string, title: string, stage: ConversationSummary['stage'], name: string): ConversationSummary => ({
      id, title, stage, createdAt: at, lastMessageAt: at, pinned: false, archived: stage === 'resolved',
      messageCount: 4, kind: 'thread', parentId: 'main-1', lead: { id: name, key: name.toLowerCase(), name },
    })
    vi.mocked(conversationsApi.threads).mockResolvedValue([
      row('t1', 'Monthly investing plan', 'waiting', 'Kai'),
      row('t2', 'Vitamin D follow-up', 'working', 'Vita'),
      row('t3', 'Sleep routine', 'idle', 'Vita'),
      row('t4', 'Dentist appointment', 'resolved', 'Vita'),
    ])
    vi.mocked(conversationsApi.update).mockResolvedValue(row('t3', 'Sleep routine', 'resolved', 'Vita'))
    wrap(<ThreadsPanel mainId="main-1" />)

    await screen.findByText('Monthly investing plan')
    expect(within(screen.getByRole('region', { name: 'Waiting on you' })).getByText('Monthly investing plan')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Working' })).getByText('Vitamin D follow-up')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Resolved · 1/ })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Dentist appointment')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Resolve Sleep routine' }))
    await waitFor(() => expect(conversationsApi.update).toHaveBeenCalledWith('t3', { archived: true }))
    fireEvent.click(screen.getByText('Monthly investing plan'))
    expect(useAssistantStore.getState().conversationId).toBe('t1')
  })


  it('are off in a new chat, and on only when the user starts one with threads', async () => {
    vi.mocked(conversationsApi.list).mockResolvedValue([])
    wrap(<ConversationRail />)

    await act(() => useAssistantStore.getState().send('Is my vitamin D low?'))
    expect(conversationsApi.create).not.toHaveBeenCalled()
    expect(vi.mocked(chatApi.send).mock.calls[0][0].conversationId).toBeUndefined()

    useAssistantStore.setState({ thinking: false })
    fireEvent.click(screen.getByRole('button', { name: 'New conversation with threads' }))
    expect(useAssistantStore.getState().threadsNext).toBe(true)
    await act(() => useAssistantStore.getState().send('Help me plan investing 500 a month'))
    expect(conversationsApi.create).toHaveBeenCalledWith({ threads: true })
    expect(vi.mocked(chatApi.send).mock.calls[1][0].conversationId).toBe('threaded-1')
    expect(useAssistantStore.getState().threadsNext).toBe(false)
  })

  it('shows where Jizza handed a request and opens that thread', async () => {
    useAssistantStore.setState({
      conversationId: 'main-1',
      activeJobId: 'job-1',
      thinking: true,
      jobs: { 'job-1': { id: 'job-1', status: 'running', startedAt: at, events: [], loaded: true } },
      messages: [
        { id: 1, role: 'user', text: 'Help me start investing 500 a month', jobId: 'job-1' },
        { id: 2, role: 'assistant', text: '', streaming: true, jobId: 'job-1' },
      ],
    })
    wrap(<Conversation />)

    act(() => useAssistantStore.getState().applyEvent({
      jobId: 'job-1', conversationId: 'main-1', seq: 2, type: 'job.completed', at,
      payload: {
        text: 'Kai has taken this to a new thread.', status: 'completed', contributors: [],
        actions: [{ type: 'thread_opened', payload: {
          conversationId: 'thread-1', title: 'Monthly index fund plan', topic: 'Investing 500 a month in index funds',
          leadKey: 'finance', leadName: 'Kai', resumed: false,
        } }],
      },
    } as never))

    expect(screen.getByText('New thread with Kai')).toBeInTheDocument()
    expect(screen.getByText('Monthly index fund plan')).toBeInTheDocument()
    // The user's message says where it went, as in a thread under a message.
    expect(screen.getByText('Sent to a thread')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Open thread Monthly index fund plan' }))
    expect(useAssistantStore.getState().conversationId).toBe('thread-1')
    await waitFor(() => expect(conversationsApi.messages).toHaveBeenCalledWith('thread-1'))
  })

  it('shows a message moved into the open thread as the user\'s own, answered by its lead', () => {
    useAssistantStore.setState({ conversationId: 'thread-1' })
    wrap(<Conversation />)

    act(() => useAssistantStore.getState().applyEvent({
      jobId: 'job-2', conversationId: 'thread-1', seq: 1, type: 'job.started', at,
      payload: { origin: 'handoff', title: 'Help me start investing', note: 'Help me start investing 500 a month' },
    } as never))
    act(() => useAssistantStore.getState().applyEvent({
      jobId: 'job-2', conversationId: 'thread-1', seq: 2, type: 'job.completed', at,
      payload: { text: 'Let us start with your goals.', status: 'completed', contributors: [], actions: [],
        lead: { agentId: 'kai', key: 'finance', name: 'Kai' } },
    } as never))

    const [asked, answered] = useAssistantStore.getState().messages
    expect(asked).toMatchObject({ role: 'user', text: 'Help me start investing 500 a month' })
    expect(answered).toMatchObject({ role: 'assistant', lead: { name: 'Kai' } })
    expect(screen.getByText('Kai')).toBeInTheDocument()
  })

  it('lists chats, not threads, and keeps an open thread\'s chat highlighted', async () => {
    vi.mocked(conversationsApi.list).mockResolvedValue([
      { id: 'main-1', title: 'Saturday plans', createdAt: at, lastMessageAt: at, pinned: false, archived: false, messageCount: 4, kind: 'main' },
    ])
    useAssistantStore.setState({ conversationId: 'thread-1' })
    wrap(<ConversationRail highlight="main-1" />)

    const row = await screen.findByRole('button', { name: 'Saturday plans' })
    expect(row.className).toContain('bg-brand-50')
    expect(screen.queryByRole('list', { name: /Threads from/ })).not.toBeInTheDocument()
    // Back to the chat the thread came from.
    fireEvent.click(row)
    expect(useAssistantStore.getState().conversationId).toBe('main-1')
  })

  it('shows only this chat\'s threads', async () => {
    wrap(<ThreadsPanel mainId="main-1" />)

    expect(await screen.findByText(/No threads in this chat yet/)).toBeInTheDocument()
    expect(conversationsApi.threads).toHaveBeenCalledWith('main-1')
    expect(screen.queryByRole('radio', { name: 'All chats' })).not.toBeInTheDocument()
  })

  it('shows a thread\'s guide and saves the user\'s edit', async () => {
    const guide: ThreadGuide = {
      conversationId: 'thread-1', title: 'Monthly index fund plan', lead: { id: 'kai', key: 'finance', name: 'Kai' },
      markdown: '# Monthly index fund plan\n\n## What we know\n- Invests 500 a month\n', updatedAt: at, source: 'jizza',
    }
    const edited: ThreadGuide = { ...guide, markdown: '# Edited\n', source: 'user' }
    vi.mocked(conversationsApi.guide).mockResolvedValueOnce(guide).mockResolvedValue(edited)
    vi.mocked(conversationsApi.updateGuide).mockResolvedValue(edited)
    vi.mocked(conversationsApi.learned).mockResolvedValue([
      { id: 'f1', content: 'Wants a Nifty 50 index fund', category: 'preference', confirmed: false, createdAt: at },
    ])
    wrap(<ThreadGuidePanel threadId="thread-1" />)

    expect(await screen.findByText('Invests 500 a month')).toBeInTheDocument()
    // The facts learned in this thread are its own memory, shown with its guide.
    const learned = await screen.findByRole('region', { name: 'Learned in this thread' })
    expect(await within(learned).findByText('Wants a Nifty 50 index fund')).toBeInTheDocument()
    expect(conversationsApi.learned).toHaveBeenCalledWith('thread-1')
    expect(screen.getByText(/What Kai and Jizza keep for this thread/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit the guide' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Thread guide' }), { target: { value: '# Edited' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save guide' }))

    await waitFor(() => expect(conversationsApi.updateGuide).toHaveBeenCalledWith('thread-1', '# Edited'))
    expect(await screen.findByText(/you edited it/)).toBeInTheDocument()
  })

  it('edits the user\'s message: what followed is replaced and the edit is sent', async () => {
    useAssistantStore.setState({
      conversationId: 'chat-1',
      messages: [
        { id: 1, role: 'user', text: 'Plan a trip to Paris', jobId: 'job-1', serverId: 'm-1' },
        { id: 2, role: 'assistant', text: 'Paris is lovely in spring.', jobId: 'job-1' },
      ],
    })
    wrap(<Conversation />)

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[0])
    fireEvent.change(screen.getByRole('textbox', { name: 'Edit your message' }), { target: { value: 'Plan a trip to Rome' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and send' }))

    await waitFor(() => expect(conversationsApi.rewind).toHaveBeenCalledWith('chat-1', 'm-1'))
    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(vi.mocked(chatApi.send).mock.calls[0][0]).toMatchObject({ message: 'Plan a trip to Rome', conversationId: 'chat-1' })
    expect(useAssistantStore.getState().messages.map(m => m.text)).not.toContain('Paris is lovely in spring.')
  })

  it('replies to a message by quoting it, and copies one', async () => {
    const writeText = vi.fn(async () => undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    useAssistantStore.setState({
      conversationId: 'chat-1',
      messages: [
        { id: 1, role: 'user', text: 'Is my vitamin D low?', jobId: 'job-1', serverId: 'm-1' },
        { id: 2, role: 'assistant', text: 'Yes, 19.8 ng/mL is low.', jobId: 'job-1' },
      ],
    })
    wrap(<Conversation />)

    fireEvent.click(screen.getAllByRole('button', { name: 'Copy' })[1])
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('Yes, 19.8 ng/mL is low.'))

    fireEvent.click(screen.getAllByRole('button', { name: 'Reply' })[1])
    expect(screen.getByText('Replying to Jizza')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText(/Ask Jizza/), { target: { value: 'What dose should I take?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(vi.mocked(chatApi.send).mock.calls[0][0].message).toBe('> Yes, 19.8 ng/mL is low.\n\nWhat dose should I take?')
    // The quote shows above the reply in the user's bubble.
    expect(await screen.findByText('Yes, 19.8 ng/mL is low.', { selector: 'span' })).toBeInTheDocument()
  })
})
