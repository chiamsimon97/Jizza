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
  },
}))

vi.mock('@/api/chat', () => ({
  chatApi: {
    send: vi.fn(async (body: { conversationId?: string }) => ({ jobId: 'job-2', conversationId: body.conversationId ?? 'c1' })),
    cancel: vi.fn(),
    job: vi.fn(),
  },
}))

import { conversationsApi } from '@/api/conversations'
import { chatApi } from '@/api/chat'
import { useAssistantStore, type AssistantMessage } from './assistantStore'
import { Conversation } from './Conversation'
import { earlierMarker, took } from './replyMeta'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false })
  vi.clearAllMocks()
})

const started = '2026-11-21T10:00:00.000Z'

function chatWithOneReply(extra: Partial<AssistantMessage> = {}) {
  useAssistantStore.setState({
    conversationId: 'c1',
    messages: [
      { id: 1, role: 'user', text: 'Plan my week', serverId: 'm1', jobId: 'job-1' },
      { id: 2, role: 'assistant', text: 'Here is a plan.', jobId: 'job-1', ...extra },
    ],
    jobs: { 'job-1': { id: 'job-1', status: 'completed', summary: 'Asked Kai. Used 2 memories.', startedAt: started, finishedAt: '2026-11-21T10:00:12.000Z', events: [], loaded: false } },
    jobOrder: ['job-1'],
  })
}

describe('Try again', () => {
  it('asks the same message again and keeps the replaced answer', async () => {
    chatWithOneReply()
    wrap(<Conversation onOpenJob={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(conversationsApi.rewind).toHaveBeenCalledWith('c1', 'm1')
    const body = vi.mocked(chatApi.send).mock.calls[0][0]
    expect(body.message).toBe('Plan my week')
    expect(body.context).toMatchObject({ versions: [{ text: 'Here is a plan.' }] })
    expect(body.context).toMatchObject({ depth: 'normal', model: 'auto' })
    const reply = useAssistantStore.getState().messages.at(-1)!
    expect(reply).toMatchObject({ role: 'assistant', jobId: 'job-2', versions: [{ text: 'Here is a plan.' }] })
  })

  it('can ask the deeper model instead', async () => {
    chatWithOneReply({ versions: [{ text: 'First try.' }] })
    wrap(<Conversation onOpenJob={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: 'Try again with a deeper model' }))

    await waitFor(() => expect(chatApi.send).toHaveBeenCalled())
    expect(vi.mocked(chatApi.send).mock.calls[0][0].context).toMatchObject({
      depth: 'deep', versions: [{ text: 'First try.' }, { text: 'Here is a plan.' }],
    })
    expect(useAssistantStore.getState().messages.at(-1)).toMatchObject({ deep: true })
  })

  it('pages back to the earlier answers', () => {
    chatWithOneReply({ versions: [{ text: 'First try.' }, { text: 'Second try.', deep: true }] })
    wrap(<Conversation onOpenJob={() => {}} />)

    expect(screen.getByText('3 / 3')).toBeInTheDocument()
    expect(screen.getByText('Here is a plan.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Earlier answer' }))
    expect(screen.getByText('Second try.')).toBeInTheDocument()
    expect(screen.getByText('Deeper model')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Earlier answer' }))
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Earlier answer' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Later answer' }))
    fireEvent.click(screen.getByRole('button', { name: 'Later answer' }))
    expect(screen.getByText('Here is a plan.')).toBeInTheDocument()
  })

  it('is offered on the latest reply only', () => {
    useAssistantStore.setState({
      conversationId: 'c1',
      messages: [
        { id: 1, role: 'user', text: 'One', jobId: 'j1' },
        { id: 2, role: 'assistant', text: 'First reply.', jobId: 'j1' },
        { id: 3, role: 'user', text: 'Two', jobId: 'j2' },
        { id: 4, role: 'assistant', text: 'Second reply.', jobId: 'j2' },
      ],
    })
    wrap(<Conversation />)
    expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1)
  })
})

describe('What I did', () => {
  it('shows what the reply took and how long, and opens the details', () => {
    chatWithOneReply()
    const open = vi.fn()
    wrap(<Conversation onOpenJob={open} />)

    fireEvent.click(screen.getByRole('button', { name: /Asked Kai\. Used 2 memories · 12s/ }))
    expect(open).toHaveBeenCalledWith('job-1')
  })
})

describe('Earlier messages summary', () => {
  it('marks where the summary takes over and shows it', async () => {
    vi.mocked(conversationsApi.earlier).mockResolvedValue({ summary: 'You planned a trip to Goa.', through: '2026-11-20T09:00:00Z' })
    useAssistantStore.setState({
      conversationId: 'c1',
      messages: [
        { id: 1, role: 'user', text: 'Old question', createdAt: '2026-11-20T08:00:00Z' },
        { id: 2, role: 'assistant', text: 'Old answer.', createdAt: '2026-11-20T08:00:05Z' },
        { id: 3, role: 'user', text: 'New question', createdAt: '2026-11-20T10:00:00Z' },
      ],
    })
    wrap(<Conversation />)

    const note = await screen.findByRole('button', { name: /remembers the messages above as a summary/ })
    fireEvent.click(note)
    expect(screen.getByText('You planned a trip to Goa.')).toBeInTheDocument()
  })

  it('places the marker before the first message the summary does not cover', () => {
    const m = (id: number, createdAt?: string): AssistantMessage => ({ id, role: 'user', text: String(id), createdAt })
    expect(earlierMarker([m(1, '2026-11-20T08:00:00Z'), m(2, '2026-11-20T10:00:00Z')], '2026-11-20T09:00:00Z')).toBe(1)
    expect(earlierMarker([m(1, '2026-11-20T08:00:00Z'), m(2)], '2026-11-20T09:00:00Z')).toBe(1)
    expect(earlierMarker([m(1), m(2)], '2026-11-20T09:00:00Z')).toBe(0)
    expect(earlierMarker([], '2026-11-20T09:00:00Z')).toBe(-1)
  })

  it('says how long a job took the way a person would', () => {
    expect(took(started, '2026-11-21T10:00:08.000Z')).toBe('8s')
    expect(took(started, '2026-11-21T10:02:05.000Z')).toBe('2m 5s')
    expect(took(started, '2026-11-21T11:04:00.000Z')).toBe('1h 4m')
    expect(took(started, undefined)).toBeUndefined()
  })
})
