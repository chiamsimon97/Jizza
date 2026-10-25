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
  },
}))

import { conversationsApi } from '@/api/conversations'
import { useAssistantStore } from './assistantStore'
import { Conversation } from './Conversation'
import { ConversationRail } from './workspace/ConversationRail'
import { ThreadGuidePanel } from './workspace/ThreadGuidePanel'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

// jsdom has no layout, so nothing scrolls.
Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false })
  vi.clearAllMocks()
})

const at = new Date().toISOString()

describe('Threads', () => {
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
    fireEvent.click(screen.getByRole('button', { name: 'Open thread' }))
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

  it('lists threads under the main chat they came from', async () => {
    const rows: ConversationSummary[] = [
      { id: 'main-1', title: 'Saturday plans', createdAt: at, lastMessageAt: at, pinned: false, archived: false, messageCount: 4, kind: 'main' },
      { id: 'thread-1', title: 'Monthly index fund plan', createdAt: at, lastMessageAt: at, pinned: false, archived: false, messageCount: 3,
        kind: 'thread', parentId: 'main-1', lead: { id: 'kai', key: 'finance', name: 'Kai' } },
    ]
    vi.mocked(conversationsApi.list).mockResolvedValue(rows)
    wrap(<ConversationRail />)

    const nested = await screen.findByRole('list', { name: 'Threads from Saturday plans' })
    expect(within(nested).getByText('Monthly index fund plan')).toBeInTheDocument()
    expect(within(nested).getByTitle('Led by Kai')).toBeInTheDocument()
  })

  it('shows a thread\'s guide and saves the user\'s edit', async () => {
    const guide: ThreadGuide = {
      conversationId: 'thread-1', title: 'Monthly index fund plan', lead: { id: 'kai', key: 'finance', name: 'Kai' },
      markdown: '# Monthly index fund plan\n\n## What we know\n- Invests 500 a month\n', updatedAt: at, source: 'jizza',
    }
    const edited: ThreadGuide = { ...guide, markdown: '# Edited\n', source: 'user' }
    vi.mocked(conversationsApi.guide).mockResolvedValueOnce(guide).mockResolvedValue(edited)
    vi.mocked(conversationsApi.updateGuide).mockResolvedValue(edited)
    wrap(<ThreadGuidePanel threadId="thread-1" />)

    expect(await screen.findByText('Invests 500 a month')).toBeInTheDocument()
    expect(screen.getByText(/What Kai and Jizza keep for this thread/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit the guide' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Thread guide' }), { target: { value: '# Edited' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save guide' }))

    await waitFor(() => expect(conversationsApi.updateGuide).toHaveBeenCalledWith('thread-1', '# Edited'))
    expect(await screen.findByText(/you edited it/)).toBeInTheDocument()
  })
})
