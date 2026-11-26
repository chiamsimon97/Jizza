import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('@/api/voice', () => ({
  voiceApi: {
    setup: vi.fn(async () => ({ listener: null, listenerName: null })),
    transcribe: vi.fn(),
    translate: vi.fn(async (text: string) => ({ text: `EN: ${text}` })),
  },
}))
vi.mock('@/api/chat', () => ({ chatApi: { send: vi.fn(), cancel: vi.fn(), job: vi.fn() } }))

import { voiceApi } from '@/api/voice'
import { chatApi } from '@/api/chat'
import { useAssistantStore } from './assistantStore'
import { Conversation } from './Conversation'
import { wavBytes } from './wav'
import { browserErrorMessage } from './useSpeech'

/** A stand-in for the browser's speech service that the test drives. */
class FakeRecognition {
  static last: FakeRecognition | null = null
  lang = ''
  interimResults = false
  continuous = false
  onresult: ((e: unknown) => void) | null = null
  onend: (() => void) | null = null
  onerror: ((e: { error?: string }) => void) | null = null
  onstart: (() => void) | null = null
  onaudiostart: (() => void) | null = null
  static silent = false
  start = vi.fn(() => { if (!FakeRecognition.silent) this.onstart?.() })
  stop = vi.fn(() => this.onend?.())
  constructor() { FakeRecognition.last = this }
  hear(text: string, isFinal = true) {
    const result = Object.assign([{ transcript: text }], { isFinal })
    this.onresult?.({ results: [result] })
  }
}

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

Element.prototype.scrollIntoView = vi.fn()

beforeEach(() => {
  (window as unknown as { webkitSpeechRecognition: unknown }).webkitSpeechRecognition = FakeRecognition
  localStorage.clear()
})

afterEach(() => {
  delete (window as unknown as { webkitSpeechRecognition?: unknown }).webkitSpeechRecognition
  useAssistantStore.setState({ messages: [], conversationId: undefined, thinking: false })
  vi.clearAllMocks()
})

const box = () => screen.getByRole('textbox') as HTMLTextAreaElement

describe('speaking to Jizza', () => {
  it('puts what was said into the chat box instead of sending it', async () => {
    localStorage.setItem('jizza.voice', JSON.stringify({ language: 'en-US', english: true }))
    wrap(<Conversation />)
    fireEvent.change(box(), { target: { value: 'Please' } })
    fireEvent.click(screen.getByRole('button', { name: 'Speak to Jizza' }))
    const rec = FakeRecognition.last!
    expect(rec.lang).toBe('en-US')
    act(() => rec.hear('book a cab'))
    expect(box().value).toBe('book a cab')
    fireEvent.click(screen.getByRole('button', { name: 'Stop listening' }))
    await waitFor(() => expect(box().value).toBe('Please book a cab'))
    expect(chatApi.send).not.toHaveBeenCalled()
    expect(voiceApi.translate).not.toHaveBeenCalled()
  })

  it('translates another language into English for the box', async () => {
    localStorage.setItem('jizza.voice', JSON.stringify({ language: 'hi-IN', english: true }))
    wrap(<Conversation />)
    fireEvent.click(screen.getByRole('button', { name: 'Speak to Jizza' }))
    const rec = FakeRecognition.last!
    expect(rec.lang).toBe('hi-IN')
    act(() => rec.hear('कल की ट्रेन बुक करो'))
    fireEvent.click(screen.getByRole('button', { name: 'Stop listening' }))
    await waitFor(() => expect(box().value).toBe('EN: कल की ट्रेन बुक करो'))
    expect(voiceApi.translate).toHaveBeenCalledWith('कल की ट्रेन बुक करो', 'hi-IN')
  })

  it('says why when the browser has no speech service, instead of failing silently', async () => {
    wrap(<Conversation />)
    fireEvent.click(screen.getByRole('button', { name: 'Speak to Jizza' }))
    const rec = FakeRecognition.last!
    act(() => { rec.onerror?.({ error: 'network' }); rec.onend?.() })
    expect(await screen.findByRole('alert')).toHaveTextContent(/Ulaa/)
    expect(screen.getByRole('link', { name: 'Open AI models' })).toHaveAttribute('href', '/settings/ai-models')
  })

  it('notices a browser whose speech service never starts (like Ulaa)', async () => {
    FakeRecognition.silent = true
    try {
      wrap(<Conversation />)
      fireEvent.click(screen.getByRole('button', { name: 'Speak to Jizza' }))
      fireEvent.click(screen.getByRole('button', { name: 'Stop listening' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't reach its speech service/)
    } finally {
      FakeRecognition.silent = false
    }
  })

  it('offers the microphone even with no speech service, and points to a key', async () => {
    delete (window as unknown as { webkitSpeechRecognition?: unknown }).webkitSpeechRecognition
    wrap(<Conversation />)
    fireEvent.click(screen.getByRole('button', { name: 'Speak to Jizza' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Connect OpenAI, Google Gemini or OpenRouter/)
  })

  it('lets the person pick the language they speak', async () => {
    wrap(<Conversation />)
    fireEvent.click(screen.getByRole('button', { name: 'Voice language' }))
    fireEvent.change(screen.getByLabelText("I'll speak in"), { target: { value: 'ta-IN' } })
    expect(JSON.parse(localStorage.getItem('jizza.voice')!)).toEqual({ language: 'ta-IN', english: true })
    // Without a key that hears audio, "detect it" isn't offered: the browser needs to be told.
    expect(screen.queryByRole('option', { name: /detect it/ })).toBeNull()
  })
})

describe('the recording', () => {
  it('is a 16-bit mono WAV file', () => {
    const buffer = wavBytes(new Float32Array([0, 1, -1]), 16000)
    const bytes = new DataView(buffer)
    expect(buffer.byteLength).toBe(44 + 6)
    expect(String.fromCharCode(bytes.getUint8(0), bytes.getUint8(1), bytes.getUint8(2), bytes.getUint8(3))).toBe('RIFF')
    expect(bytes.getUint32(24, true)).toBe(16000)
    expect(bytes.getInt16(46, true)).toBe(32767)
    expect(bytes.getInt16(48, true)).toBe(-32768)
  })

  it('explains a blocked microphone', () => {
    expect(browserErrorMessage('not-allowed')).toMatch(/System Settings/)
  })
})
