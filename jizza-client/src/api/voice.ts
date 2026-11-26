import { api } from '@/lib/api'

/** Whether one of the person's own AI keys can hear recordings (OpenAI, Gemini or OpenRouter). */
export interface VoiceSetup { listener: string | null; listenerName: string | null }

export const voiceApi = {
  setup: () => api.get<VoiceSetup>('/api/chat/voice'),

  /** A WAV recording to text, translated into English unless {@code translate} is false. */
  transcribe: (wav: Blob, language: string, translate: boolean) => {
    const form = new FormData()
    form.append('file', wav, 'speech.wav')
    if (language && language !== 'auto') form.append('language', language)
    form.append('translate', String(translate))
    return api.upload<{ text: string; provider: string }>('/api/chat/voice/transcribe', form)
  },

  /** What the browser heard in another language, in English. */
  translate: (text: string, language: string) =>
    api.post<{ text: string }>('/api/chat/voice/translate', { text, language }),
}
