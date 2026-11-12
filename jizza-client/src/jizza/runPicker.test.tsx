import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('@/api/chat', () => ({
  chatApi: {
    send: vi.fn(async (body: { conversationId?: string }) => ({ jobId: 'job-1', conversationId: body.conversationId ?? 'plain-1' })),
    cancel: vi.fn(),
    job: vi.fn(),
  },
}))
vi.mock('@/api/workflows', () => ({
  workflowsApi: {
    runnable: vi.fn(async () => [
      { workflowId: 'w1', name: 'Simon workflow', status: 'DRAFT', version: 1, profile: { inputs: [] } },
      { workflowId: 'w2', name: 'Ticket booking', status: 'APPROVED', version: 1, profile: { inputs: [{ name: 'movie', required: true }] } },
    ]),
  },
}))

import { chatApi } from '@/api/chat'
import { useAssistantStore } from './assistantStore'
import { Conversation } from './Conversation'

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  useAssistantStore.setState({ messages: [], jobs: {}, jobOrder: [], conversationId: undefined, activeJobId: undefined, thinking: false })
  vi.clearAllMocks()
})

const box = () => screen.getByRole('textbox') as HTMLTextAreaElement

describe('/run in chat', () => {
  it('offers the workflows and sends the picked one as a command', async () => {
    wrap(<Conversation />)
    fireEvent.change(box(), { target: { value: '/run sim' } })
    await screen.findByText('Simon workflow')
    const list = screen.getByRole('listbox', { name: 'Workflows' })
    expect(within(list).queryByText('Ticket booking')).not.toBeInTheDocument()
    fireEvent.keyDown(box(), { key: 'Enter' })
    await waitFor(() => expect(chatApi.send).toHaveBeenCalledWith(expect.objectContaining({ message: '/run Simon workflow' })))
  })

  it('puts the inputs a workflow takes in the box to fill in first', async () => {
    wrap(<Conversation />)
    fireEvent.change(box(), { target: { value: '/run tick' } })
    fireEvent.click(await screen.findByText('Ticket booking'))
    expect(box().value).toBe('/run Ticket booking\nmovie: ')
    expect(chatApi.send).not.toHaveBeenCalled()
  })

  it('is one of the slash commands', async () => {
    wrap(<Conversation />)
    fireEvent.change(box(), { target: { value: '/ru' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    expect(box().value).toBe('/run ')
    expect(await screen.findByRole('listbox', { name: 'Workflows' })).toBeInTheDocument()
  })
})
