import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { AiModelsPage } from './AiModelsPage'
import { aiModelsApi, type AiModelsOverview, type ProviderView } from '@/api/aiModels'

const kinds: AiModelsOverview['kinds'] = [
  { provider: 'openai', name: 'OpenAI', note: 'GPT models', needsKey: true, needsAddress: false, defaultBaseUrl: 'https://api.openai.com/v1', keyLink: 'https://platform.openai.com/api-keys' },
  { provider: 'ollama', name: 'Ollama', note: 'Models on your own computer', needsKey: false, needsAddress: true, defaultBaseUrl: 'http://localhost:11434/v1' },
]

const openai: ProviderView = {
  id: 'p1', provider: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', keyHint: '••••abcd', enabled: true,
  models: [{ id: 'gpt-5.1', ref: 'openai:gpt-5.1', label: 'GPT-5.1', tools: true, vision: true, pdf: true, hidden: false }],
}

afterEach(() => vi.restoreAllMocks())

describe('AI models', () => {
  it('connects a provider with a key, then shows only the key’s last characters', async () => {
    const overview = vi.spyOn(aiModelsApi, 'overview')
      .mockResolvedValueOnce({ kinds, connected: [], models: [] })
      .mockResolvedValue({ kinds, connected: [openai], models: [] })
    const connect = vi.spyOn(aiModelsApi, 'connect').mockResolvedValue(openai)
    renderWithSnapshot(<AiModelsPage />, makeSnapshot())

    expect(await screen.findByText(/no AI key of its own/)).toBeInTheDocument()
    const card = (await screen.findByRole('heading', { name: 'OpenAI' })).closest('div.rounded-xl, div[class*="p-6"]') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: 'Connect' }))
    expect(within(card).getByRole('link', { name: /Get a key/ })).toHaveAttribute('href', 'https://platform.openai.com/api-keys')
    const submit = within(card).getByRole('button', { name: 'Check and connect' })
    expect(submit).toBeDisabled()
    fireEvent.change(within(card).getByLabelText('API key'), { target: { value: 'sk-my-key-abcd' } })
    fireEvent.click(submit)

    await waitFor(() => expect(connect).toHaveBeenCalledWith('openai', { apiKey: 'sk-my-key-abcd' }))
    expect(await screen.findByText(/Key ••••abcd/)).toBeInTheDocument()
    expect(screen.queryByDisplayValue('sk-my-key-abcd')).toBeNull()
    expect(overview).toHaveBeenCalledTimes(2)
  })

  it('hides a model from the picker and shows why a provider needs attention', async () => {
    vi.spyOn(aiModelsApi, 'overview').mockResolvedValue({
      kinds, connected: [{ ...openai, lastError: 'The key was not accepted.' }], models: [],
    })
    const update = vi.spyOn(aiModelsApi, 'updateModel').mockResolvedValue(openai)
    renderWithSnapshot(<AiModelsPage />, makeSnapshot())

    expect(await screen.findByText('The key was not accepted.')).toBeInTheDocument()
    expect(screen.getByText('Needs attention')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Models' }))
    fireEvent.click(screen.getByRole('button', { name: 'Hide GPT-5.1 from the picker' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('openai', 'gpt-5.1', { hidden: true }))
  })

  it('asks Ollama for its address, not a key', async () => {
    vi.spyOn(aiModelsApi, 'overview').mockResolvedValue({ kinds, connected: [], models: [] })
    const connect = vi.spyOn(aiModelsApi, 'connect').mockResolvedValue({ ...openai, provider: 'ollama', name: 'Ollama', keyHint: null })
    renderWithSnapshot(<AiModelsPage />, makeSnapshot())

    const card = (await screen.findByRole('heading', { name: 'Ollama' })).closest('div[class*="p-6"]') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: 'Connect' }))
    expect(within(card).queryByLabelText('API key')).toBeNull()
    fireEvent.change(within(card).getByLabelText('Address'), { target: { value: 'http://studio.local:11434/v1' } })
    fireEvent.click(within(card).getByRole('button', { name: 'Check and connect' }))
    await waitFor(() => expect(connect).toHaveBeenCalledWith('ollama', { baseUrl: 'http://studio.local:11434/v1' }))
  })
})
